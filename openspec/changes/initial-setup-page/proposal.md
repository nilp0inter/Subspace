## Why

The APK bundles ~950 MB of ONNX model assets (Parakeet STT, Supertonic TTS) downloaded at build time from HuggingFace. Every app update forces a full ~1 GB Play Store download even when models haven't changed. Meanwhile, runtime permissions (Bluetooth, microphone, notifications) are buried inside the RSM device setup page — a user who just installed the app has no clear onboarding path. This change adds a mandatory initial setup page that requests permissions and downloads+verifies models before the user enters the main dashboard, and re-checks both on every launch.

## What Changes

- **New `InitialSetupScreen` composable**: gated before the dashboard on first launch and whenever permissions are revoked or model hashes fail
  - Step 1: Request all runtime permissions at once (BT_CONNECT, BT_SCAN, RECORD_AUDIO, POST_NOTIFICATIONS)
  - Step 2: Download Parakeet and Supertonic model files from HuggingFace with progress bars
  - Auto-verify SHA-256 against bundled hash manifest on completion
  - Show check marks for already-satisfied steps; disable "Enter" until all steps done
- **Model source changes**: Remove model assets from APK build; bundle only `model-hashes.json` (~15 KB)
- **New Gradle task `generateModelHashes`**: emits `model-hashes.json` into `src/main/assets/` from the downloaded model files after `downloadParakeetAssets`/`downloadSupertonicAssets`
- **Remove permissions from `ConnectionScreen` (RSM setup)**: the permissions row, guidance text, and "Grant permissions" button are deleted. `ConnectionScreen` becomes purely about device discovery/pairing/SPP/HFP
- **`MainActivity.onCreate()` routing**: check permissions + model hashes → route to `Setup` or `Dashboard`
- **`PttForegroundService.refreshReadiness()`**: still checks permissions for service logic but the UI prompt moves to setup page
- **`AppState` gains a `setupState` field** tracking setup completion
- **`RequiredPermissions.kt`**: unchanged API; the callers change
- **BREAKING**: `ConnectionState.permissions` / `PermissionState` are removed from the ConnectionScreen UI (still present in service model)

## Capabilities

### New Capabilities
- `initial-setup`: Initial setup screen that gates the main app — permissions request flow, model download with progress, hash verification, startup re-check logic
- `runtime-model-download`: Device-side model downloading from HuggingFace Hub with SHA-256 integrity verification, resumable downloads, progress reporting, and storage management

### Modified Capabilities

None. Existing capability specs (capture-service, sco-audio, stt, tts, etc.) specify behavior when models are loaded — they don't prescribe how models arrive on disk.

## Impact

| Area | Change |
|------|--------|
| `app/build.gradle.kts` | Add `generateModelHashes` task; remove generated model dirs from `assets.srcDirs`; remove `preBuild` dependency on download tasks (or keep for CI hashing) |
| `app/src/main/assets/` | Remove model ONNX/JSON files; add `model-hashes.json` |
| `MainActivity.kt` | Add setup route + routing logic; permission launcher moves from inline to setup screen |
| `ConnectionScreen.kt` | Remove permissions UI elements |
| `Models.kt` (AppState) | Add `setupState: SetupState` |
| `SupertonicAssetExtractor.kt` | Replace with download+verify logic (or keep as fallback) |
| `ParakeetAssetExtractor.kt` | Replace with download+verify logic (or keep as fallback) |
| `PttForegroundService.kt` | Model init changes from asset extraction to using downloaded files; `initializeStt`/`initializeTts` adapted |
| CI (`ci.yml`) | No CI changes needed — `generateModelHashes` runs during every Gradle build |
| New: `SetupViewModel.kt` | State management for setup screen |
| New: `ModelDownloader.kt` | HTTP downloader with resumable downloads, SHA-256 verification |
| New: `app/src/main/assets/model-hashes.json` | CI-generated hash manifest |
