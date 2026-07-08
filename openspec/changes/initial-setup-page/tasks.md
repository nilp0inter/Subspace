## 1. Build System — Hash Manifest

- [x] 1.1 Add `generateModelHashes` Gradle task to `app/build.gradle.kts` that reads the `ParakeetAsset`/`SupertonicAsset` SHA-256 constants and file lists and writes `model-hashes.json` to `src/main/assets/`
- [x] 1.2 Wire task dependencies: `generateModelHashes` depends on `downloadParakeetAssets` and `downloadSupertonicAssets`; `preBuild` depends on `generateModelHashes`
- [x] 1.3 Remove `build/generated/assets/{parakeet,supertonic}` from `assets.srcDirs` in `build.gradle.kts` so model files are not bundled in APK
- [x] 1.4 Update `.gitignore` if needed — `model-hashes.json` in `src/main/assets/` SHOULD be tracked (it's small, generated deterministically, and needed for CI-to-device consistency)

## 2. Runtime Model Downloader

- [x] 2.1 Create `ModelDownloader.kt` in `audio/` — downloads model files from HuggingFace Hub via OkHttp with `Range` header resumability, progress callbacks, SHA-256 verification against bundled `model-hashes.json`
- [x] 2.2 Create `ModelVerifier.kt` in `audio/` — loads `model-hashes.json` from APK assets, walks `filesDir/{modelSet}` dirs, computes SHA-256 on each file, reports which model sets are valid/missing/corrupted
- [x] 2.3 Adapt `PttForegroundService.initializeStt()` and `initializeTts()` — replace `ParakeetAssetExtractor.extract()` / `SupertonicAssetExtractor.extract()` calls with `ModelDownloader.ensure(context, modelSet)` calls that return the model directory path
- [x] 2.4 Delete `ParakeetAssetExtractor.kt` and `SupertonicAssetExtractor.kt`

## 3. Setup Screen UI

- [x] 3.1 Add `SetupState` data class to `Models.kt` tracking: `permissionsDone: Boolean`, `modelsDone: Boolean`, `parakeetProgress: DownloadProgress`, `supertonicProgress: DownloadProgress`
- [x] 3.2 Add `SetupState` to `AppState` data class (default: neither step done)
- [x] 3.3 Add `MainRoute.Setup` to the `MainRoute` enum in `MainActivity.kt`
- [x] 3.4 Create `InitialSetupScreen.kt` composable in `ui/` — shows permission step, model download step, and "Enter Subspace" button gated on both
- [x] 3.5 Wire routing in `MainActivity.onCreate()` — on startup, check permissions and model hashes; if anything missing, route to `Setup` instead of `Dashboard`
- [x] 3.6 Wire "Enter Subspace" button to set `setupState` to completed and switch to `MainRoute.Dashboard`
- [x] 3.7 Add `INTERNET` permission to `AndroidManifest.xml` (normal permission, auto-granted)

## 4. ConnectionScreen — Remove Permissions

- [x] 4.1 Remove `permissionText()` call and `StatusRow("Permissions", …)` from `StatusPanel` in `ConnectionScreen.kt`
- [x] 4.2 Remove `PermissionState.Missing` branch from `GuidancePanel` in `ConnectionScreen.kt`
- [x] 4.3 Remove "Grant permissions" `Button` from `ActionPanel` in `ConnectionScreen.kt`

## 5. Verification

- [x] 5.1 Run `nix develop --no-write-lock-file -c gradle build` — APK builds successfully without model assets
- [x] 5.2 Verify APK contains `model-hashes.json` and does NOT contain `.onnx` files: `jar tf app/build/outputs/apk/debug/app-debug.apk | grep -E '\.onnx$|model-hashes\.json'`
- [x] 5.3 Run `nix develop --no-write-lock-file -c gradle test` — all existing tests pass
- [ ] 5.4 Manual device test: fresh install → setup page shown → grant permissions → models download → "Enter Subspace" → dashboard loads
- [ ] 5.5 Manual device test: revoke permissions via system settings → next launch → setup page re-shown
- [ ] 5.6 Manual device test: corrupt a model file on disk → next launch → setup page shows re-download
