//! Subspace Parakeet v3 STT bridge.
//!
//! Native library that:
//! - exposes JNI entry points for initialization, startup loading, and
//!   transcription of normalized 16 kHz mono `f32` samples;
//! - keeps a process-local singleton [`ParakeetEngine`] loaded with the int8
//!   model parameters;
//! - exposes the asset extraction module for host-side unit testing.
//!
//! Guarantees:
//! - never performs network I/O;
//! - never persists captured audio or transcripts;
//! - serializes access to the shared engine because inference mutates model
//!   state.

#![allow(clippy::missing_safety_doc)]

pub mod asset;
mod engine;
mod log;

use std::sync::OnceLock;

use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::engine::{ParakeetEngine, TranscriptionOutcome};

/// Process-local singleton engine. Loaded once, shared across all calls.
static ENGINE: Lazy<Mutex<OnceLock<ParakeetEngine>>> = Lazy::new(|| Mutex::new(OnceLock::new()));

/// Transcription outcome tag returned to Kotlin.
///
/// Mirrors `ParakeetJniBridge.TranscriptionOutcome` Kotlin sealed class so the
/// Kotlin side can pattern-match without parsing strings.
const OUTCOME_SUCCESS: jint = 0;
const OUTCOME_MODEL_NOT_READY: jint = 1;
const OUTCOME_INFERENCE_FAILED: jint = 2;
const OUTCOME_EMPTY_INPUT: jint = 3;

/// Initialize ONNX Runtime by dynamically loading `libonnxruntime.so` from the
/// app files directory. Must be called before `nativeLoadModel`.
///
/// `native_lib_dir` must point to the directory containing the ONNX Runtime
/// shared library extracted by the app. Returns 0 on success, a negative
/// error code otherwise.
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_ParakeetJniBridge_nativeInit(
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
    match engine::ParakeetEngine::init_onnxruntime(&lib_path) {
        Ok(()) => 0,
        Err(err) => {
            log::error(&format!("failed to initialize ONNX Runtime: {}", err));
            -1
        }
    }
}

/// Start loading the Parakeet int8 model from `model_dir`. Returns immediately
/// (0 on accepted, negative on error). The actual load happens in a background
/// thread. Use `nativeLoadStatus` to poll readiness.
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_ParakeetJniBridge_nativeStartLoad(
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

    log::info(&format!("starting Parakeet model load from {}", dir.display()));
    let holder_ref: &OnceLock<ParakeetEngine> = &*lock;
    match ParakeetEngine::start_loading(&dir, holder_ref) {
        Ok(()) => 0,
        Err(err) => {
            log::error(&format!("failed to start Parakeet load: {}", err));
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
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_ParakeetJniBridge_nativeLoadStatus(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let lock = ENGINE.lock().expect("engine mutex poisoned");
    match lock.get() {
        Some(engine) => match engine.status() {
            engine::LoadStatus::Idle => 0,
            engine::LoadStatus::Loading => 1,
            engine::LoadStatus::Ready => 2,
            engine::LoadStatus::Failed => 3,
        },
        None => 0,
    }
}

/// Returns the most recent model-load failure message, or `null` when no
/// failure has been recorded.
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_ParakeetJniBridge_nativeLoadError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let lock = ENGINE.lock().expect("engine mutex poisoned");
    let message = match lock.get() {
        Some(engine) => match engine.status() {
            engine::LoadStatus::Failed => engine.load_error().unwrap_or_default(),
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

/// Transcribe normalized 16 kHz mono `f32` samples.
///
/// Returns a `jstring` JSON object with the shape:
///
/// ```json
/// {"outcome":0,"text":"..."}
/// {"outcome":1}              // model not ready
/// {"outcome":2,"error":"..."}
/// {"outcome":3}              // empty input
/// ```
///
/// The function blocks until either the model is ready or loading fails; it
/// does not block the UI thread because callers must invoke it off the main
/// thread (see `SttController` Kotlin code).
#[no_mangle]
pub unsafe extern "C" fn Java_dev_nilp0inter_subspace_audio_ParakeetJniBridge_nativeTranscribe(
    mut env: JNIEnv,
    _class: JClass,
    samples_array: JFloatArray,
) -> jstring {
    let samples = match extract_f32_samples(&mut env, &samples_array) {
        Ok(s) => s,
        Err(code) => return encode_status(&mut env, OUTCOME_INFERENCE_FAILED, Some(code)),
    };

    if samples.is_empty() {
        return encode_status(&mut env, OUTCOME_EMPTY_INPUT, None);
    }

    let outcome = {
        let lock = ENGINE.lock().expect("engine mutex poisoned");
        match lock.get() {
            Some(engine) => engine.transcribe(&samples),
            None => TranscriptionOutcome::ModelNotReady,
        }
    };

    match outcome {
        TranscriptionOutcome::Success(text) => {
            encode_status(&mut env, OUTCOME_SUCCESS, Some(&text))
        }
        TranscriptionOutcome::ModelNotReady => {
            encode_status(&mut env, OUTCOME_MODEL_NOT_READY, None)
        }
        TranscriptionOutcome::Failure(err) => {
            encode_status(&mut env, OUTCOME_INFERENCE_FAILED, Some(err.to_string().as_str()))
        }
    }
}

fn extract_path(env: &mut JNIEnv, path: &JString) -> Result<std::path::PathBuf, &'static str> {
    let string: String = env
        .get_string(path)
        .map_err(|_| "invalid Java string")?
        .into();
    Ok(std::path::PathBuf::from(string))
}

fn extract_f32_samples(
    env: &mut JNIEnv,
    array: &JFloatArray,
) -> Result<Vec<f32>, &'static str> {
    let length = env
        .get_array_length(array)
        .map_err(|_| "invalid array")?;
    if length < 0 {
        return Err("negative array length");
    }
    let mut buf = vec![0.0f32; length as usize];
    env.get_float_array_region(array, 0, &mut buf)
        .map_err(|_| "failed to copy samples")?;
    Ok(buf)
}

fn encode_status(env: &mut JNIEnv, outcome: jint, payload: Option<&str>) -> jstring {
    let body = match payload {
        Some(text) if !text.is_empty() && outcome == OUTCOME_SUCCESS => {
            let escaped = escape_json_string(text);
            format!(r#"{{"outcome":{},"text":"{}"}}"#, outcome, escaped)
        }
        Some(text) if !text.is_empty() => {
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

fn jni_error_code(_message: &str) -> jint {
    -100
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