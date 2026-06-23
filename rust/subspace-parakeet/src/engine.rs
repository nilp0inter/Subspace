//! Parakeet engine wrapper.
//!
//! - Keeps a process-local singleton [`ParakeetEngine`] loaded with int8
//!   model parameters.
//! - Synchronizes load state for idle/loading/ready/failed transitions.
//! - Serializes inference because `transcribe-rs` mutates model state.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use transcribe_rs::onnx::parakeet::{ParakeetModel, ParakeetParams};
use transcribe_rs::onnx::Quantization;

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

/// Outcome of a transcription request.
#[derive(Debug)]
pub enum TranscriptionOutcome {
    Success(String),
    ModelNotReady,
    Failure(EngineError),
}

#[derive(Debug, thiserror::Error)]
#[allow(dead_code)]
pub enum EngineError {
    #[error("ONNX Runtime initialization failed: {0}")]
    OrtInit(String),
    #[error("model load failed: {0}")]
    LoadFailed(String),
    #[error("inference failed: {0}")]
    InferenceFailed(String),
    #[error("engine mutex poisoned")]
    Poisoned,
}

/// Process-local Parakeet engine.
///
/// Owned by the [`OnceLock`] in the crate root. Holds the loaded
/// [`ParakeetModel`], the most recent load status, and any load error message.
pub struct ParakeetEngine {
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    status: LoadStatus,
    model: Option<ParakeetModel>,
    load_error: Option<String>,
}

impl ParakeetEngine {
    /// Start loading the Parakeet int8 model from `model_dir` on a background
    /// thread. The caller passes the `OnceLock` that will hold this engine.
    pub fn start_loading(
        model_dir: &Path,
        holder: &OnceLock<ParakeetEngine>,
    ) -> Result<(), EngineError> {
        let inner = Arc::new(Mutex::new(Inner {
            status: LoadStatus::Loading,
            model: None,
            load_error: None,
        }));
        let engine = ParakeetEngine {
            inner: inner.clone(),
        };
        if holder.set(engine).is_err() {
            log::debug("start_load called while engine already set; ignoring");
            return Ok(());
        }

        let dir: PathBuf = model_dir.to_path_buf();
        std::thread::spawn(move || {
            let result = ParakeetModel::load(&dir, &Quantization::Int8);
            let mut guard = match inner.lock() {
                Ok(g) => g,
                Err(_) => {
                    log::error("engine inner mutex poisoned during load");
                    return;
                }
            };
            match result {
                Ok(model) => {
                    guard.status = LoadStatus::Ready;
                    guard.model = Some(model);
                    guard.load_error = None;
                    log::info("Parakeet model ready");
                }
                Err(err) => {
                    let message = err.to_string();
                    log::error(&format!("Parakeet model load failed: {}", message));
                    guard.status = LoadStatus::Failed;
                    guard.model = None;
                    guard.load_error = Some(message);
                }
            }
        });

        Ok(())
    }

    /// Initialize ONNX Runtime by dynamically loading the library at
    /// `lib_path`. Must be called before [`ParakeetEngine::start_loading`].
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

    /// Transcribe normalized 16 kHz mono `f32` samples. Blocks if the model is
    /// still loading; returns [`TranscriptionOutcome::ModelNotReady`] if the
    /// load has failed.
    pub fn transcribe(&self, samples: &[f32]) -> TranscriptionOutcome {
        // Wait for loading to finish before attempting inference.
        let status = self.wait_for_load();
        if status != LoadStatus::Ready {
            return TranscriptionOutcome::ModelNotReady;
        }

        let mut guard = match self.inner.lock() {
            Ok(g) => g,
            Err(_) => return TranscriptionOutcome::Failure(EngineError::Poisoned),
        };
        let model = match guard.model.as_mut() {
            Some(m) => m,
            None => return TranscriptionOutcome::ModelNotReady,
        };

        match model.transcribe_with(samples, &ParakeetParams::default()) {
            Ok(result) => {
                log::debug(&format!("transcription success: {} chars", result.text.len()));
                TranscriptionOutcome::Success(result.text)
            }
            Err(err) => {
                let message = err.to_string();
                log::error(&format!("transcription failed: {}", message));
                TranscriptionOutcome::Failure(EngineError::InferenceFailed(message))
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