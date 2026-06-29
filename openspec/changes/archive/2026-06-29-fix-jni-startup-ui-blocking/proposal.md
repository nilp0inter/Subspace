## Why

The Parakeet (STT) and Supertonic (TTS) JNI bridges are initialized synchronously on the Android main thread inside `PttForegroundService.onCreate`. The chain performs, per engine: APK-asset marker check (and on first run a multi-hundred-MB file copy), `System.loadLibrary` (`dlopen` + `JNI_OnLoad`), `ort::init_from(lib_path).commit()` (a second `dlopen` of `libonnxruntime.so` plus an ONNX Runtime `CreateEnv` FFI call), and `nativeStartLoad`. Because `subspace-parakeet` and `subspace-supertonic` are separate `cdylib`s, each owns its own `ort` `OnceLock` statics, so both `dlopen` and `CreateEnv` actually execute twice in the same process. On warm run this is roughly 0.4-1.5s of main-thread blocking; on first run the ~415MB Supertonic asset copy alone exceeds the foreground-service ANR threshold by 1-6x. The existing `ptt-stt-test` and `ptt-tts-test` specs already require that model loading "not block initial UI rendering," but the requirement is untestable as written and the implementation violates it. This change makes the requirement explicit and testable, and fixes the implementation.

## What Changes

- Move `ParakeetAssetExtractor.extract`, `System.loadLibrary("subspace_parakeet")`, `ParakeetJniBridge.nativeInit`, and `ParakeetJniBridge.nativeStartLoad` off the main thread, into a single `Dispatchers.IO` coroutine launched from `PttForegroundService.onCreate`. The STT controller, poller, and status collector are constructed and started only after the off-main init succeeds.
- Move `SupertonicAssetExtractor.extract`, `System.loadLibrary("subspace_supertonic")`, `SupertonicJniBridge.nativeInit`, and `SupertonicJniBridge.nativeStartLoad` off the main thread the same way, into a single `Dispatchers.IO` coroutine. The TTS controller, STT↔TTS controller, announcer, poller, and status collectors are constructed and started only after the off-main init succeeds.
- `PttForegroundService.onCreate` returns after posting the foreground notification and starting the two init coroutines. No JNI call, `dlopen`, `CreateEnv`, or asset file I/O happens synchronously on the main thread.
- The connected monitor/test surface already shows `Loading` for both model readiness fields; that status is now reached via the same pollers but driven by the off-main init. No UI surface changes.
- The `voiceStyleFile` helper (called on every TTS synthesis request and on precompute setup) currently re-invokes `SupertonicAssetExtractor.extract` on the main thread. This is changed to use the already-extracted model directory captured during the off-main init, eliminating per-request main-thread file I/O.

## Capabilities

### New Capabilities
<!-- None. This change tightens existing requirements; it does not introduce a new capability. -->

### Modified Capabilities
- `ptt-stt-test`: The "Parakeet model loads on app startup" requirement is tightened to explicitly require that asset extraction, native library load, ONNX Runtime initialization, and load-start all happen off the main thread, with testable scenarios for the first-run and warm-run cases.
- `ptt-tts-test`: The "Supertonic model loads on app startup" requirement is tightened the same way, and a new requirement is added that per-synthesis asset-directory resolution does not perform file I/O on the main thread.

## Impact

- **Affected code**: `PttForegroundService.onCreate`, `PttForegroundService.initializeStt`, `PttForegroundService.initializeTts`, `PttForegroundService.voiceStyleFile`. The `ParakeetJniTranscriber` and `SupertonicJniSynthesizer` constructor `init` blocks are unchanged (they still call `ensureLoaded` + `nativeInit` + `nativeStartLoad`), but they now run on `Dispatchers.IO`.
- **No API changes**: The Kotlin `TtsSynthesizer` / `SttTranscriber` ports are unchanged. The Rust crates are unchanged. The `SupertonicAssetExtractor.extract` signature is unchanged; callers just stop invoking it on the main thread.
- **Behavioral change**: STT and TTS controllers, pollers, and the `SystemAnnouncer` become available slightly later on warm run (by the duration of the off-main init, ~hundreds of ms after `onCreate` returns instead of synchronously). On first run they become available after the deferred asset extraction completes. The UI already shows `Loading` for both model readiness fields; this state persists until the off-main init finishes, which is the existing intended behavior.
- **Foreground-service contract**: `onStartCommand` posts the foreground notification and is unchanged. `onCreate` no longer blocks on JNI, so the foreground notification posts faster on first run.
- **Out of scope**: Main-thread mutex contention between the model-status pollers and in-flight inference (the Rust `ENGINE` `Mutex<OnceLock<Engine>>` held across `wait_for_load` + synthesis), main-thread `TtsAudio.toScoPlayback` resampling, and main-thread `SttAudio.toParakeetInput` normalization. These are real but separate from the startup symptom and are deferred to a separate change.
- **Dependencies**: None added or removed.