//! Supertonic engine wrapper.
//!
//! - Keeps a process-local singleton [`SupertonicEngine`] loaded with the four
//!   ONNX sessions, `tts.json`, and the Unicode indexer.
//! - Synchronizes load state for idle/loading/ready/failed transitions.
//! - Serializes synthesis because the denoising loop mutates session/model
//!   state.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use ort::session::Session;

use crate::helper::{
    load_cfgs, load_voice_style, synthesize as run_synthesis, SupertonicSessions,
};
use crate::log;

/// Synchronized load state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)]
pub enum LoadStatus {
    Idle,
    Loading,
    Ready,
    Failed,
}

/// Outcome of a synthesis request.
#[derive(Debug)]
pub enum SynthesisOutcome {
    Success(Vec<f32>),
    ModelNotReady,
    Failure(EngineError),
    EmptyText,
}

#[derive(Debug, thiserror::Error)]
#[allow(dead_code)]
pub enum EngineError {
    #[error("ONNX Runtime initialization failed: {0}")]
    OrtInit(String),
    #[error("model load failed: {0}")]
    LoadFailed(String),
    #[error("synthesis failed: {0}")]
    SynthesisFailed(String),
    #[error("engine mutex poisoned")]
    Poisoned,
}

/// Synthesis request parameters.
pub struct SynthesisRequest<'a> {
    pub text: &'a str,
    pub voice_style_path: &'a Path,
    pub lang: &'a str,
    pub total_steps: usize,
    pub speed: f32,
    pub silence_duration: f32,
}

/// Process-local Supertonic engine.
///
/// Owned by the [`OnceLock`] in the crate root. Holds the loaded
/// [`SupertonicSessions`], the most recent load status, and any load error
/// message.
pub struct SupertonicEngine {
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    status: LoadStatus,
    sessions: Option<SupertonicSessions>,
    load_error: Option<String>,
}

impl SupertonicEngine {
    /// Start loading the Supertonic model from `model_dir` on a background
    /// thread. The caller passes the `OnceLock` that will hold this engine.
    pub fn start_loading(
        model_dir: &Path,
        holder: &OnceLock<SupertonicEngine>,
    ) -> Result<(), EngineError> {
        let inner = Arc::new(Mutex::new(Inner {
            status: LoadStatus::Loading,
            sessions: None,
            load_error: None,
        }));
        let engine = SupertonicEngine { inner: inner.clone() };
        if holder.set(engine).is_err() {
            log::debug("start_load called while engine already set; ignoring");
            return Ok(());
        }

        let dir: PathBuf = model_dir.to_path_buf();
        std::thread::spawn(move || {
            let result = load_sessions(&dir);
            let mut guard = match inner.lock() {
                Ok(g) => g,
                Err(_) => {
                    log::error("engine inner mutex poisoned during load");
                    return;
                }
            };
            match result {
                Ok(sessions) => {
                    guard.status = LoadStatus::Ready;
                    guard.sessions = Some(sessions);
                    guard.load_error = None;
                    log::info("Supertonic model ready");
                }
                Err(err) => {
                    let message = err.to_string();
                    log::error(&format!("Supertonic model load failed: {}", message));
                    guard.status = LoadStatus::Failed;
                    guard.sessions = None;
                    guard.load_error = Some(message);
                }
            }
        });

        Ok(())
    }

    /// Initialize ONNX Runtime by dynamically loading the library at
    /// `lib_path`. Must be called before [`SupertonicEngine::start_loading`].
    pub fn init_onnxruntime(lib_path: &Path) -> Result<(), EngineError> {
        if !lib_path.exists() {
            return Err(EngineError::OrtInit(format!(
                "missing library at {}",
                lib_path.display()
            )));
        }
        ort::init_from(lib_path)
            .map_err(|err| EngineError::OrtInit(err.to_string()))?
            .commit();
        log::info(&format!(
            "ONNX Runtime initialized from {}",
            lib_path.display()
        ));
        Ok(())
    }

    /// Current load status. Safe to poll from any thread.
    pub fn status(&self) -> LoadStatus {
        self.inner
            .lock()
            .map(|g| g.status)
            .unwrap_or(LoadStatus::Failed)
    }

    /// Most recent load failure message, if any.
    pub fn load_error(&self) -> Option<String> {
        self.inner
            .lock()
            .ok()
            .and_then(|g| g.load_error.clone())
    }

    /// Synthesize speech for [request]. Blocks until the model is ready (or
    /// loading fails) and synthesis completes. Callers MUST invoke this off
    /// the main thread.
    pub fn synthesize(&self, request: SynthesisRequest) -> SynthesisOutcome {
        if request.text.trim().is_empty() {
            return SynthesisOutcome::EmptyText;
        }

        let status = self.wait_for_load();
        if status != LoadStatus::Ready {
            return SynthesisOutcome::ModelNotReady;
        }

        let mut guard = match self.inner.lock() {
            Ok(g) => g,
            Err(_) => return SynthesisOutcome::Failure(EngineError::Poisoned),
        };
        let sessions = match guard.sessions.as_mut() {
            Some(s) => s,
            None => return SynthesisOutcome::ModelNotReady,
        };

        // Load the voice style for this request. Voice styles are not
        // preloaded because the set of preset voice styles may vary; loading
        // a single JSON is cheap relative to inference.
        let style = match load_voice_style(request.voice_style_path) {
            Ok(s) => s,
            Err(err) => {
                log::error(&format!("voice style load failed: {}", err));
                return SynthesisOutcome::Failure(EngineError::SynthesisFailed(err.to_string()));
            }
        };

        match run_synthesis(
            sessions,
            request.text,
            request.lang,
            &style,
            request.total_steps,
            request.speed,
            request.silence_duration,
        ) {
            Ok(samples) => {
                log::debug(&format!("synthesis success: {} samples", samples.len()));
                SynthesisOutcome::Success(samples)
            }
            Err(err) => {
                let message = err.to_string();
                log::error(&format!("synthesis failed: {}", message));
                SynthesisOutcome::Failure(EngineError::SynthesisFailed(message))
            }
        }
    }

    fn wait_for_load(&self) -> LoadStatus {
        // Spin-sleep until status is no longer Loading. This is intended to
        // be called off the main thread, so blocking is acceptable. We bound
        // the spin so a stuck loading thread doesn't hang the caller forever;
        // after 120 s we return the latest status and let the caller surface
        // whatever state exists.
        const MAX_WAIT_SECS: u64 = 120;
        let start = std::time::Instant::now();
        loop {
            let status = self.status();
            if status != LoadStatus::Loading {
                return status;
            }
            if start.elapsed().as_secs() >= MAX_WAIT_SECS {
                log::warn(&format!(
                    "wait_for_load exceeded {}s; returning",
                    MAX_WAIT_SECS
                ));
                return status;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    }
}

fn load_sessions(model_dir: &Path) -> Result<SupertonicSessions, EngineError> {
    let cfgs = load_cfgs(model_dir).map_err(|e| EngineError::LoadFailed(e.to_string()))?;

    let dp_path = model_dir.join("duration_predictor.onnx");
    let text_enc_path = model_dir.join("text_encoder.onnx");
    let vector_est_path = model_dir.join("vector_estimator.onnx");
    let vocoder_path = model_dir.join("vocoder.onnx");

    let dp_ort = Session::builder()
        .and_then(|mut b| b.commit_from_file(&dp_path))
        .map_err(|e| EngineError::LoadFailed(format!("duration_predictor: {e}")))?;
    let text_enc_ort = Session::builder()
        .and_then(|mut b| b.commit_from_file(&text_enc_path))
        .map_err(|e| EngineError::LoadFailed(format!("text_encoder: {e}")))?;
    let vector_est_ort = Session::builder()
        .and_then(|mut b| b.commit_from_file(&vector_est_path))
        .map_err(|e| EngineError::LoadFailed(format!("vector_estimator: {e}")))?;
    let vocoder_ort = Session::builder()
        .and_then(|mut b| b.commit_from_file(&vocoder_path))
        .map_err(|e| EngineError::LoadFailed(format!("vocoder: {e}")))?;

    let unicode_indexer_path = model_dir.join("unicode_indexer.json");
    let text_processor = crate::helper::UnicodeProcessor::new(&unicode_indexer_path)
        .map_err(|e| EngineError::LoadFailed(format!("unicode_indexer: {e}")))?;

    Ok(SupertonicSessions {
        cfgs,
        text_processor,
        dp_ort,
        text_enc_ort,
        vector_est_ort,
        vocoder_ort,
    })
}