## Context

The app currently bundles ~950 MB of ONNX model assets (Parakeet STT: ~500 MB, Supertonic TTS: ~450 MB) inside the APK. These are downloaded at build time from HuggingFace Hub by Gradle tasks (`downloadParakeetAssets`, `downloadSupertonicAssets`) into `build/generated/assets/` and wired as asset source roots. At runtime, `ParakeetAssetExtractor` and `SupertonicAssetExtractor` copy them from APK assets to `filesDir/` on service start, keyed by a version marker.

Runtime permissions (BLUETOOTH_CONNECT, BLUETOOTH_SCAN, RECORD_AUDIO, POST_NOTIFICATIONS) are requested inside the RSM device setup page (`ConnectionScreen`), which is reached only by long-pressing the Work mode tile on the dashboard. New users have no onboarding flow.

This design removes model assets from the APK, adds an initial setup page that gates the main app, and moves permission requests out of the RSM setup flow into the onboarding step.

## Goals / Non-Goals

**Goals:**
- Remove ~950 MB of model assets from the APK so updates are small (~5 MB)
- Add a mandatory initial setup page shown on first launch that requests all runtime permissions and downloads+verifies models
- Re-check permissions and model integrity on every subsequent launch; re-route to setup if anything is missing
- Remove permission-request UI from `ConnectionScreen` (RSM setup), which becomes purely about device pairing and serial connection
- Generate `model-hashes.json` at build time from the Gradle download tasks for on-device integrity verification
- Support resumable downloads for large model files (~500 MB each)

**Non-Goals:**
- Progressive download of models (e.g., download STT first, use app, download TTS later) — both model sets must be ready before entering the dashboard
- Streaming model loading from network — models are fully downloaded to disk before use
- Removing the Gradle download tasks — they still run in CI to produce the hash manifest and for dev builds
- Play Asset Delivery or APK splits — the approach is zero model bytes in APK, not deferred delivery
- Background download when app is killed — if the app is killed mid-download, next launch restarts from scratch or from last completed chunk

## Decisions

### Decision 1: Hash manifest generation
A new Gradle task `generateModelHashes` runs after both `downloadParakeetAssets` and `downloadSupertonicAssets`. It reads the downloaded files (which already have SHA-256 verification in their download tasks), or equivalently reads the hardcoded `sha256` values from the `ParakeetAsset`/`SupertonicAsset` data classes plus the file lists, and writes a single `model-hashes.json` to `src/main/assets/`.

Format:
```json
{
  "parakeet-tdt-0.6b-v3-int8": {
    "version": "int8-2026-06-23",
    "repo": "smcleod/parakeet-tdt-0.6b-v3-int8",
    "files": {
      "encoder-model.int8.onnx": "sha256:6139d2fa...",
      "decoder_joint-model.int8.onnx": "sha256:eea7483e...",
      "nemo128.onnx": "sha256:a9fde148...",
      "vocab.txt": "sha256:d5854467...",
      "config.json": "sha256:666903c7..."
    }
  },
  "supertonic-3": {
    "version": "supertonic-3-2026-06-24",
    "repo": "Supertone/supertonic-3",
    "files": {
      "duration_predictor.onnx": "sha256:c3eb9141...",
      "text_encoder.onnx": "sha256:c7befd5e...",
      ...
    }
  }
}
```

Rationale: Using existing hardcoded SHA-256 values plus downloaded file lists means no redundant computation. The manifest is the single source of truth for what the device should have. The Gradle task is trivially deterministic.

### Decision 2: Model download on device via OkHttp
Use OkHttp (already an implicit dependency via Compose/AndroidX) with HuggingFace direct HTTP URLs:
```
https://huggingface.co/{repo}/resolve/main/{filename}
```

Resumable downloads via `Range` header. Progress reported via OkHttp `ResponseBody` wrapper that emits bytes-read callbacks.

Alternatives considered:
- `DownloadManager`: built-in, free, but progress visibility is limited to notification-bar level. Cannot show in-app progress bars.
- Ktor client: full control, not currently in the dependency tree. OkHttp is already transitive.
- Raw `HttpURLConnection`: works but no built-in interceptors for retry/logging.

Decision: OkHttp with a custom `ProgressResponseBody` wrapper.

### Decision 3: Setup state lives in `MainActivity` (no ViewModel)
The setup screen is shown before the service is bound, so service state is unavailable. The setup state is simple enough (permissions step done bool, each model set's download progress) to live as Compose state inside `MainActivity`. A dedicated `SetupViewModel` would add ceremony for two booleans and two progress floats. If complexity grows, extract later.

Specifically: `var setupState by remember { mutableStateOf(SetupState()) }` in `MainActivity.onCreate()`, where `SetupState` is a data class tracking permissions, parakeet status, supertonic status.

### Decision 4: Model directory layout unchanged
Models on device live at:
- `{filesDir}/parakeet-tdt-0.6b-v3-int8/` (files flat)
- `{filesDir}/supertonic-3/` (files flat)

This exactly matches the current `ParakeetAssetExtractor.MODEL_DIR_NAME` and `SupertonicAssetExtractor.MODEL_DIR_NAME` layout, so the downstream code (`ParakeetJniTranscriber`, `SupertonicJniSynthesizer`) needs zero changes — they already accept directory paths.

### Decision 5: Asset extractors replaced by runtime downloader
`ParakeetAssetExtractor` and `SupertonicAssetExtractor` are replaced by `ModelDownloader` which:
1. Loads `model-hashes.json` from APK assets
2. For each model set, checks `{filesDir}/{dirName}/.subspace_assets_version` against the manifest version
3. If missing or mismatched: downloads missing files, computes SHA-256, writes `.subspace_assets_version` on success
4. If version matches: verifies each file's SHA-256 against the manifest; on any mismatch, re-downloads that model set

The old extractors can be deleted. The `initializeStt()` and `initializeTts()` methods in `PttForegroundService` are adapted to call `ModelDownloader.ensure()` instead of extractors.

### Decision 6: No bundled model fallback
There is no fallback to bundled models. If the device has no network on first launch, the setup page blocks at the model download step with a "Connect to the internet to download speech models" message. Rationale: keeping models as a fallback negates the APK size benefit and adds complexity to CI and build tooling. If offline-first deployment is needed later, it's additive.

### Decision 7: `ConnectionScreen` permission removal
The following are removed from `ConnectionScreen`:
- `permissionText()` function call in `StatusPanel`
- `PermissionState.Missing` branch in `GuidancePanel`
- "Grant permissions" button in `ActionPanel`
- The `state.permissions` checks throughout

The `ConnectionState.permissions` field stays in the data model because `PttForegroundService.refreshReadiness()` still needs it for service-side logic (e.g., gate scanning behind permission grant). But the ConnectionScreen composable no longer renders it.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| First launch requires network — user without internet is blocked | Show clear "Connect to internet" message. No offline fallback (accepted non-goal). |
| Download fails mid-way (network loss, app kill) | Resumable downloads via `Range` header. Only completed chunks survive app kill. On re-launch, setup page resumes from the last fully-downloaded file. |
| HuggingFace rate limits on device downloads | Device downloads are per-user, not per-IP. HF Hub allows anonymous downloads with rate limits that are high enough for individual users. If observed, add `HF_TOKEN` support. |
| Storage space insufficient for ~950 MB models | Check `filesDir` free space before starting download. Show clear error with space requirement. |
| Long download time on slow networks | Show estimated time remaining, allow user to leave and come back (state persists). Download progress survives rotation via `rememberSaveable`. |
| Permissions revoked by user via system settings between launches | Startup re-check catches this. Setup page re-opens. User cannot enter dashboard until permissions re-granted. |
| Dev iteration speed — every clean install needs 1 GB download | Gradle build still downloads models locally; developer can `adb push` to `filesDir` to bypass runtime download. Future: add build task for `adb install` + `adb push` combo. |
| Model version mismatch between manifest and files on disk after partial update | Per-file hash verification catches any mismatch. Entire model set is re-downloaded on version change or single-file corruption. |
| `model-hashes.json` becomes stale (hashes don't match HF source) | CI downloads fresh files every build, so manifest is always current. Hash changes in the manifest trigger re-download on device. |
