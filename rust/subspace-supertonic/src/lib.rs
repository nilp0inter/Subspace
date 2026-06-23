//! Subspace Supertonic 3 TTS bridge.
//!
//! Native library that:
//! - exposes JNI entry points for initialization, startup loading, load-status
//!   polling, load-error reporting, and synthesis;
//! - keeps a process-local singleton [`SupertonicEngine`] loaded with the four
//!   Supertonic ONNX sessions, `tts.json`, and the Unicode indexer;
//! - exposes the asset extraction module for host-side unit testing.
//!
//! Guarantees:
//! - never performs network I/O;
//! - never persists text or synthesized audio;
//! - serializes access to the shared engine because inference mutates model
//!   state.

#![allow(clippy::missing_safety_doc)]

pub mod asset;
mod engine;
mod helper;
mod log;

use std::path::PathBuf;
use std::sync::OnceLock;

use jni::objects::{JClass, JString};
use jni::sys::{jfloat, jint, jstring};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::engine::{LoadStatus, SupertonicEngine, SynthesisOutcome};

/// Process-local singleton engine. Loaded once, shared across all calls.
static ENGINE: Lazy<Mutex<OnceLock<SupertonicEngine>>> = Lazy::new(|| Mutex::new(OnceLock::new()));

/// Synthesis outcome tag returned to Kotlin.
///
/// Mirrors `SupertonicJniBridge.SynthesisOutcome` Kotlin sealed class so the
/// Kotlin side can pattern-match without parsing strings.
const OUTCOME_SUCCESS: jint = 0;
const OUTCOME_MODEL_NOT_READY: jint = 1;
const OUTCOME_SYNTHESIS_FAILED: jint = 2;
const OUTCOME_EMPTY_TEXT: jint = 3;

/// Initialize ONNX Runtime by dynamically loading `libonnxruntime.so` from the
/// app files directory. Must be called before `nativeStartLoad`.
///
/// `native_lib_dir` must point to the directory containing the ONNX Runtime
/// shared library extracted by the app. Returns 0 on success, a negative
/// error code otherwise.
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_SupertonicJniBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    native_lib_dir: JString,
) -> jint {
    let lib_dir = match extract_path(&mut env, &native_lib_dir) {
        Ok(p) => p,
        Err(code) => return jni_error_code(code),
    };

    let lib_path = lib_dir.join("libonnxruntime.so");
    log::debug(&format!(
        "initializing ONNX Runtime from {}",
        lib_path.display()
    ));
    match SupertonicEngine::init_onnxruntime(&lib_path) {
        Ok(()) => 0,
        Err(err) => {
            log::error(&format!("failed to initialize ONNX Runtime: {}", err));
            -1
        }
    }
}

/// Start loading the Supertonic model from `model_dir`. Returns immediately
/// (0 on accepted, negative on error). The actual load happens in a background
/// thread. Use `nativeLoadStatus` to poll readiness.
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_SupertonicJniBridge_nativeStartLoad(
    mut env: JNIEnv,
    _class: JClass,
    model_dir: JString,
) -> jint {
    let dir = match extract_path(&mut env, &model_dir) {
        Ok(p) => p,
        Err(code) => return jni_error_code(code),
    };

    let lock = ENGINE.lock().expect("engine mutex poisoned");
    if lock.get().is_some() {
        log::debug("nativeStartLoad called while engine already initialized; no-op");
        return 0;
    }

    log::info(&format!("starting Supertonic model load from {}", dir.display()));
    let holder_ref: &OnceLock<SupertonicEngine> = &*lock;
    match SupertonicEngine::start_loading(&dir, holder_ref) {
        Ok(()) => 0,
        Err(err) => {
            log::error(&format!("failed to start Supertonic load: {}", err));
            -2
        }
    }
}

/// Current load status.
///
/// Returns one of:
/// - 0: idle (not loaded, not loading, no failure)
/// - 1: loading
/// - 2: ready
/// - 3: failed; call `nativeLoadError` for the message
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_SupertonicJniBridge_nativeLoadStatus(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let lock = ENGINE.lock().expect("engine mutex poisoned");
    match lock.get() {
        Some(engine) => match engine.status() {
            LoadStatus::Idle => 0,
            LoadStatus::Loading => 1,
            LoadStatus::Ready => 2,
            LoadStatus::Failed => 3,
        },
        None => 0,
    }
}

/// Returns the most recent model-load failure message, or `null` when no
/// failure has been recorded.
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_SupertonicJniBridge_nativeLoadError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let lock = ENGINE.lock().expect("engine mutex poisoned");
    let message = match lock.get() {
        Some(engine) => match engine.status() {
            LoadStatus::Failed => engine.load_error().unwrap_or_default(),
            _ => String::new(),
        },
        None => String::new(),
    };
    drop(lock);
    if message.is_empty() {
        std::ptr::null_mut()
    } else {
        match env.new_string(message) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}

/// Synthesize speech from text.
///
/// Accepts:
/// - `text`: the text to synthesize;
/// - `voice_style_path`: filesystem path to the voice style JSON (e.g. M1.json);
/// - `lang`: language code (e.g. "en");
/// - `total_steps`: number of denoising steps (quality);
/// - `speed`: speech speed factor (higher = faster).
///
/// Returns a `jstring` JSON object with the shape:
///
/// ```json
/// {"outcome":0,"samples":[...]}
/// {"outcome":1}              // model not ready
/// {"outcome":2,"error":"..."}
/// {"outcome":3}              // empty text
/// ```
///
/// The function blocks until either the model is ready or loading fails; it
/// does not block the UI thread because callers must invoke it off the main
/// thread (see `TtsController` Kotlin code).
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_SupertonicJniBridge_nativeSynthesize(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    voice_style_path: JString,
    lang: JString,
    total_steps: jint,
    speed: jfloat,
) -> jstring {
    let text_str = match extract_string(&mut env, &text) {
        Ok(s) => s,
        Err(code) => return encode_error(&mut env, OUTCOME_SYNTHESIS_FAILED, code),
    };
    let voice_path = match extract_path(&mut env, &voice_style_path) {
        Ok(p) => p,
        Err(code) => return encode_error(&mut env, OUTCOME_SYNTHESIS_FAILED, code),
    };
    let lang_str = match extract_string(&mut env, &lang) {
        Ok(s) => s,
        Err(code) => return encode_error(&mut env, OUTCOME_SYNTHESIS_FAILED, code),
    };

    if text_str.trim().is_empty() {
        return encode_status(&mut env, OUTCOME_EMPTY_TEXT, None, None);
    }

    let request = engine::SynthesisRequest {
        text: &text_str,
        voice_style_path: &voice_path,
        lang: &lang_str,
        total_steps: total_steps.max(1) as usize,
        speed,
        silence_duration: 0.3,
    };

    let outcome = {
        let lock = ENGINE.lock().expect("engine mutex poisoned");
        match lock.get() {
            Some(engine) => engine.synthesize(request),
            None => SynthesisOutcome::ModelNotReady,
        }
    };

    match outcome {
        SynthesisOutcome::Success(samples) => {
            encode_status(&mut env, OUTCOME_SUCCESS, Some(&samples), None)
        }
        SynthesisOutcome::ModelNotReady => {
            encode_status(&mut env, OUTCOME_MODEL_NOT_READY, None, None)
        }
        SynthesisOutcome::Failure(err) => {
            encode_status(&mut env, OUTCOME_SYNTHESIS_FAILED, None, Some(&err.to_string()))
        }
        SynthesisOutcome::EmptyText => encode_status(&mut env, OUTCOME_EMPTY_TEXT, None, None),
    }
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

fn extract_path(env: &mut JNIEnv, path: &JString) -> Result<PathBuf, &'static str> {
    let string: String = env
        .get_string(path)
        .map_err(|_| "invalid Java string")?
        .into();
    Ok(PathBuf::from(string))
}

fn extract_string(env: &mut JNIEnv, value: &JString) -> Result<String, &'static str> {
    Ok(env
        .get_string(value)
        .map_err(|_| "invalid Java string")?
        .into())
}

fn encode_status(
    env: &mut JNIEnv,
    outcome: jint,
    samples: Option<&[f32]>,
    error: Option<&str>,
) -> jstring {
    let body = match (outcome, samples, error) {
        (OUTCOME_SUCCESS, Some(samples), _) => {
            let mut sb = String::from(r#"{"outcome":0,"samples":["#);
            for (i, &s) in samples.iter().enumerate() {
                if i > 0 {
                    sb.push(',');
                }
                sb.push_str(&format_f32(s));
            }
            sb.push_str("]}");
            sb
        }
        (_, _, Some(text)) if !text.is_empty() => {
            let escaped = escape_json_string(text);
            format!(r#"{{"outcome":{},"error":"{}"}}"#, outcome, escaped)
        }
        _ => format!(r#"{{"outcome":{}}}"#, outcome),
    };
    match env.new_string(body) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn encode_error(env: &mut JNIEnv, outcome: jint, message: &str) -> jstring {
    encode_status(env, outcome, None, Some(message))
}

fn jni_error_code(_message: &str) -> jint {
    -100
}

/// Format an `f32` for JSON: finite values use Rust's default `Display`, which
/// does not produce `NaN`/`Infinity` for finite inputs. Non-finite values are
/// replaced with 0.0 to keep the JSON valid.
fn format_f32(value: f32) -> String {
    if value.is_finite() {
        format!("{}", value)
    } else {
        "0.0".to_string()
    }
}

fn escape_json_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '"' => out.push_str(r#"\""#),
            '\\' => out.push_str(r"\\"),
            '\n' => out.push_str(r"\n"),
            '\r' => out.push_str(r"\r"),
            '\t' => out.push_str(r"\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!(r"\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}