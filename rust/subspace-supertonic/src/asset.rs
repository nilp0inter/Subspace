//! Asset extraction from APK assets to app files storage.
//!
//! ONNX Runtime requires filesystem paths, so Supertonic model assets shipped
//! in the APK `assets/` directory must be extracted to a writable directory
//! before the engine can load them. Extraction is idempotent: a
//! `.subspace_assets_version` marker file records the extracted version.
//! Already-up-to-date directories are skipped without re-copying.
//!
//! This module is intentionally JNI-free: callers (typically Kotlin) provide
//! asset bytes via the engine wrapper. The extraction logic itself is pure
//! file I/O so it can be unit-tested on host without Android.

use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use crate::log;

/// Marker file written into the destination directory once extraction of all
/// expected assets completes successfully. Contains the asset version string.
const MARKER_NAME: &str = ".subspace_assets_version";

/// Expected Supertonic model asset filenames, in the order they must be
/// extracted. The marker file is written only after every file in this list is
/// present and readable. Voice style JSONs (e.g. `M1.json`) are extracted
/// separately by callers because the set of preset voice styles may vary.
pub const SUPERTONIC_ASSETS: &[&str] = &[
    "duration_predictor.onnx",
    "text_encoder.onnx",
    "vector_estimator.onnx",
    "vocoder.onnx",
    "tts.json",
    "unicode_indexer.json",
];

/// Outcome of an extraction request.
#[derive(Debug, PartialEq, Eq)]
pub enum ExtractionOutcome {
    /// All assets were already extracted at the requested version. No file
    /// I/O was required beyond reading the marker.
    AlreadyPresent,
    /// Assets were extracted from the provided bytes and the marker was
    /// updated.
    Extracted,
}

#[derive(Debug, thiserror::Error)]
pub enum ExtractionError {
    #[error("asset `{0}` was not provided by the caller")]
    MissingAsset(String),
    #[error("destination directory `{0}` could not be created: {1}")]
    DestinationCreateFailed(String, String),
    #[error("failed to write asset `{path}`: {reason}")]
    WriteFailed { path: String, reason: String },
    #[error("failed to read marker `{path}`: {reason}")]
    MarkerReadFailed { path: String, reason: String },
    #[error("marker present but payload was not valid UTF-8: {0}")]
    MarkerInvalidUtf8(String),
}

/// Extract the provided asset bytes into `dest_dir`, marking completion with a
/// version marker.
///
/// - `dest_dir` is the target app files directory (must be writable).
/// - `version` is a caller-chosen tag (e.g. `"supertonic-3-2026-06-23"`). If the
///   existing marker matches, extraction is skipped.
/// - `assets` maps each filename in [`SUPERTONIC_ASSETS`] to its bytes.
pub fn extract_assets(
    dest_dir: &Path,
    version: &str,
    assets: &std::collections::HashMap<String, Vec<u8>>,
) -> Result<ExtractionOutcome, ExtractionError> {
    if !dest_dir.exists() {
        fs::create_dir_all(dest_dir).map_err(|err| ExtractionError::DestinationCreateFailed(
            dest_dir.display().to_string(),
            err.to_string(),
        ))?;
    }

    let marker_path = dest_dir.join(MARKER_NAME);
    if let Some(existing) = read_marker(&marker_path)? {
        if existing == version {
            log::debug("asset extraction skipped; marker matches");
            return Ok(ExtractionOutcome::AlreadyPresent);
        }
        log::info("asset version changed; re-extracting");
    }

    for filename in SUPERTONIC_ASSETS {
        let bytes = assets
            .get(*filename)
            .ok_or_else(|| ExtractionError::MissingAsset(filename.to_string()))?;
        let target = dest_dir.join(filename);
        write_atomic(&target, bytes).map_err(|reason| ExtractionError::WriteFailed {
            path: target.display().to_string(),
            reason,
        })?;
    }

    write_atomic(&marker_path, version.as_bytes()).map_err(|reason| ExtractionError::WriteFailed {
        path: marker_path.display().to_string(),
        reason,
    })?;

    log::info(&format!("asset extraction complete (version={})", version));
    Ok(ExtractionOutcome::Extracted)
}

fn read_marker(path: &Path) -> Result<Option<String>, ExtractionError> {
    if !path.exists() {
        return Ok(None);
    }
    let mut file = File::open(path).map_err(|reason| ExtractionError::MarkerReadFailed {
        path: path.display().to_string(),
        reason: reason.to_string(),
    })?;
    let mut buf = String::new();
    file.read_to_string(&mut buf).map_err(|reason| ExtractionError::MarkerReadFailed {
        path: path.display().to_string(),
        reason: reason.to_string(),
    })?;
    let trimmed = buf.trim().to_string();
    if trimmed.is_empty() {
        return Ok(None);
    }
    Ok(Some(trimmed))
}

fn write_atomic(target: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp_path: PathBuf = target.with_extension("tmp");
    {
        let mut file = File::create(&tmp_path).map_err(|err| err.to_string())?;
        file.write_all(bytes).map_err(|err| err.to_string())?;
        file.sync_all().map_err(|err| err.to_string())?;
    }
    fs::rename(&tmp_path, target).map_err(|err| err.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_assets() -> std::collections::HashMap<String, Vec<u8>> {
        SUPERTONIC_ASSETS
            .iter()
            .map(|name| (name.to_string(), format!("contents-of-{}", name).into_bytes()))
            .collect()
    }

    #[test]
    fn extracts_all_assets_and_writes_marker() {
        let tmp = tempfile_dir();
        let assets = make_assets();
        let outcome = extract_assets(&tmp, "v1", &assets).unwrap();
        assert_eq!(outcome, ExtractionOutcome::Extracted);
        for name in SUPERTONIC_ASSETS {
            assert!(tmp.join(name).exists(), "missing {}", name);
        }
        let marker = std::fs::read_to_string(tmp.join(MARKER_NAME)).unwrap();
        assert_eq!(marker.trim(), "v1");
    }

    #[test]
    fn skips_when_marker_matches() {
        let tmp = tempfile_dir();
        let assets = make_assets();
        extract_assets(&tmp, "v1", &assets).unwrap();
        std::fs::write(tmp.join("tts.json"), b"tampered").unwrap();
        let outcome = extract_assets(&tmp, "v1", &assets).unwrap();
        assert_eq!(outcome, ExtractionOutcome::AlreadyPresent);
        let contents = std::fs::read(tmp.join("tts.json")).unwrap();
        assert_eq!(contents, b"tampered");
    }

    #[test]
    fn re_extracts_when_version_changes() {
        let tmp = tempfile_dir();
        let assets = make_assets();
        extract_assets(&tmp, "v1", &assets).unwrap();
        std::fs::write(tmp.join("tts.json"), b"tampered").unwrap();
        let outcome = extract_assets(&tmp, "v2", &assets).unwrap();
        assert_eq!(outcome, ExtractionOutcome::Extracted);
        let contents = std::fs::read(tmp.join("tts.json")).unwrap();
        assert_eq!(contents, format!("contents-of-{}", "tts.json").as_bytes());
    }

    #[test]
    fn fails_when_asset_missing() {
        let tmp = tempfile_dir();
        let mut assets = make_assets();
        assets.remove("tts.json");
        let err = extract_assets(&tmp, "v1", &assets).unwrap_err();
        match err {
            ExtractionError::MissingAsset(name) => assert_eq!(name, "tts.json"),
            other => panic!("unexpected error: {:?}", other),
        }
    }

    fn tempfile_dir() -> PathBuf {
        tempfile::tempdir().unwrap().into_path()
    }
}