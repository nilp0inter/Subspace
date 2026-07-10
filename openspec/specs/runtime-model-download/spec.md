# Runtime Model Download

## Purpose

Define runtime model distribution, integrity verification, versioning, and download progress for the setup flow.

## Requirements

### Requirement: model-hashes.json is bundled in APK

The system SHALL bundle a `model-hashes.json` file in APK assets that maps each model set to its version, HuggingFace repository, and per-file SHA-256 hashes.

#### Scenario: Manifest present in APK
- **WHEN** the APK is built
- **THEN** `model-hashes.json` is present in the APK assets directory

#### Scenario: Manifest format
- **WHEN** the manifest is read
- **THEN** it contains entries for `parakeet-tdt-0.6b-v3-int8` and `supertonic-3`, each with a `version` string, `repo` identifier, and `files` map from filename to `sha256:<hash>` string

### Requirement: Model files are not bundled in APK

The system SHALL NOT bundle ONNX model files, voice style JSONs, vocab files, or config JSONs inside the APK.

#### Scenario: No model assets in APK
- **WHEN** the APK is inspected
- **THEN** no files matching `*.onnx`, `vocab.txt`, or voice style `*.json` are present in the APK assets

### Requirement: Models are downloaded at runtime from HuggingFace

The system SHALL download model files from HuggingFace Hub at runtime using the repository identifiers in the hash manifest.

#### Scenario: Download STT models
- **WHEN** the model download step runs for the parakeet model set
- **THEN** files are downloaded from `https://huggingface.co/smcleod/parakeet-tdt-0.6b-v3-int8/resolve/main/{filename}` using the `huggingface.co` host

#### Scenario: Download TTS models
- **WHEN** the model download step runs for the supertonic model set
- **THEN** files are downloaded from `https://huggingface.co/Supertone/supertonic-3/resolve/main/{filePath}` where `{filePath}` corresponds to the HuggingFace subdirectory path (e.g., `onnx/duration_predictor.onnx`, `voice_styles/M1.json`)

#### Scenario: Files saved to correct directory
- **WHEN** a model file is downloaded
- **THEN** it is saved to `{filesDir}/{modelSetName}/{localFilename}` where `modelSetName` is `parakeet-tdt-0.6b-v3-int8` or `supertonic-3` and `localFilename` matches the flat layout expected by the native bridge

### Requirement: SHA-256 verification after download

The system SHALL compute the SHA-256 hash of each downloaded file and verify it against the bundled manifest before considering the model set complete.

#### Scenario: Hash matches
- **WHEN** a downloaded file's SHA-256 matches the manifest
- **THEN** the file is considered valid and the next file is downloaded

#### Scenario: Hash mismatch
- **WHEN** a downloaded file's SHA-256 does not match the manifest
- **THEN** the file is re-downloaded (up to a configurable retry limit, default 3)

### Requirement: Download progress reporting

The system SHALL report download progress (bytes downloaded vs total bytes per file, and which file is currently downloading) so the setup screen can display progress bars.

#### Scenario: Per-file progress
- **WHEN** a model file is downloading
- **THEN** the downloader emits progress callbacks with bytes read and total bytes for that file

#### Scenario: Multi-file progress
- **WHEN** multiple files in a model set are downloaded sequentially
- **THEN** the progress indicates which file is currently downloading and the overall percentage

### Requirement: Resumable downloads

The system SHALL support resuming interrupted downloads using HTTP `Range` headers.

#### Scenario: Resume partial download
- **WHEN** a download was interrupted and a partial file exists on disk
- **THEN** the downloader sends a `Range` header with the existing file's byte size and appends the remaining content

#### Scenario: Server supports range requests
- **WHEN** the HuggingFace server responds with `206 Partial Content`
- **THEN** the downloader appends the response body to the existing partial file

#### Scenario: Server does not support range requests
- **WHEN** the server responds with `200 OK` (ignoring the Range header)
- **THEN** the downloader overwrites the partial file with the full response

### Requirement: Re-download on version change

The system SHALL re-download a model set when the bundled manifest's version string differs from the version marker on disk.

#### Scenario: Version mismatch triggers re-download
- **WHEN** the app launches and the `.subspace_assets_version` file in a model directory contains a different version than the bundled manifest
- **THEN** the entire model set is re-downloaded

#### Scenario: Version match verifies hashes
- **WHEN** the `.subspace_assets_version` matches the bundled manifest version
- **THEN** each model file's SHA-256 is verified against the manifest; any mismatch triggers re-download of that model set

### Requirement: generateModelHashes Gradle task

The build system SHALL have a `generateModelHashes` Gradle task that produces `model-hashes.json` in `src/main/assets/`.

#### Scenario: Task produces manifest
- **WHEN** `generateModelHashes` runs after the download tasks
- **THEN** it reads the SHA-256 constants from `ParakeetAsset`/`SupertonicAsset` definitions plus the file lists and writes a valid `model-hashes.json` to `src/main/assets/`

#### Scenario: Task is deterministic
- **WHEN** `generateModelHashes` runs twice with the same inputs
- **THEN** it produces byte-identical output
