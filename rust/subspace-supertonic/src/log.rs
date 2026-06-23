//! Lightweight Android-side logging adapter.
//!
//! Routes `log` crate events to Android `Log` via JNI when running on-device.
//! When unit tests run on host, falls back to `eprintln`.

#[derive(Copy, Clone, Debug)]
enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

pub(crate) fn debug(message: &str) {
    emit(Level::Debug, message);
}

pub(crate) fn info(message: &str) {
    emit(Level::Info, message);
}

pub(crate) fn warn(message: &str) {
    emit(Level::Warn, message);
}

pub(crate) fn error(message: &str) {
    emit(Level::Error, message);
}

fn emit(level: Level, message: &str) {
    let truncated = if message.len() > 4000 {
        // Android log truncates at ~4000 bytes; chunk to keep tail visible.
        &message[..4000]
    } else {
        message
    };
    #[cfg(target_os = "android")]
    {
        android_log(level, truncated);
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[{:?}] {}", level, message);
    }
}

#[cfg(target_os = "android")]
fn android_log(level: Level, message: &str) {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    const PRIO_DEBUG: c_int = 3;
    const PRIO_INFO: c_int = 4;
    const PRIO_WARN: c_int = 5;
    const PRIO_ERROR: c_int = 6;

    let tag = CString::new("SubspaceSupertonic").unwrap_or_default();
    let text = CString::new(message).unwrap_or_default();
    let prio = match level {
        Level::Debug => PRIO_DEBUG,
        Level::Info => PRIO_INFO,
        Level::Warn => PRIO_WARN,
        Level::Error => PRIO_ERROR,
    };
    unsafe {
        __android_log_write(prio, tag.as_ptr(), text.as_ptr());
    }
}