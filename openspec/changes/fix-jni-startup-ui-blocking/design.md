## Context

`PttForegroundService.onCreate` (PttForegroundService.kt:173-229) currently runs `initializeStt` and `initializeTts` synchronously on the main thread (`serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, line 103). Each `initialize*` function performs, in order, on the main thread:

1. `AssetExtractor.extract(this, VERSION)` — reads a version marker file; on first run or after a version bump, copies the full model asset set from APK `assets/` to `filesDir`. Supertonic's asset set is ~415MB.
2. `JniBridge.ensureLoaded()` — `System.loadLibrary(NAME)`, which performs `dlopen` of the cdylib plus the library's `JNI_OnLoad`.
3. `JniBridge.nativeInit(nativeLibDir)` → JNI → `Engine::init_onnxruntime` → `ort::init_from(lib_path).commit()`. The `ort` crate is `load-dynamic` (Cargo.toml:14,15), so `init_from` calls `load_dylib_from_path` (a `dlopen` of `libonnxruntime.so`) and `commit()` calls `G_ENV_OPTIONS.try_insert_with`, which invokes the ONNX Runtime `CreateEnv` FFI. Because `subspace-parakeet` and `subspace-supertonic` are separate `cdylib`s, each owns its own copy of `ort`'s `OnceLock` statics (`G_ORT_LIB`, `G_ORT_API`, `G_ENV_OPTIONS`), so both `dlopen` and `CreateEnv` actually execute twice in the same process.
4. `JniBridge.nativeStartLoad(modelDir)` → `Engine::start_loading` → `std::thread::spawn`. This step already returns immediately.

After both `initialize*` functions return, `onCreate` launches the model-status pollers and the controller status collectors on `serviceScope` (lines 255-265, 321-344). These pollers run on `Main.immediate` and call `synth.modelStatus` / `transcriber.modelStatus`, which are JNI calls into `nativeLoadStatus`. (A separate mutex-contention issue with these pollers is out of scope here; see proposal "Out of scope".)

The existing `ptt-stt-test` and `ptt-tts-test` specs already state that model loading SHALL start "without blocking initial UI rendering." The implementation violates this; the fix is to make the existing requirement testable and to move the four blocking steps off the main thread.

The foreground-service notification is posted by `onStartCommand` via `ensureForeground` (line 351-353), not by `onCreate`. So `onCreate` is free to return immediately after launching the init coroutines; the notification posts on the first `onStartCommand` which the system calls after `onCreate` returns.

## Goals / Non-Goals

**Goals:**
- `PttForegroundService.onCreate` performs zero JNI calls, zero `dlopen`s, zero `CreateEnv` FFI calls, and zero asset file I/O synchronously on the main thread.
- The existing model-load pollers, status collectors, and controller construction run only after the off-main init for their respective engine completes. The pollers and collectors continue to run on `serviceScope` as before.
- The connected monitor/test surface continues to show `Loading` for STT and TTS model readiness until the off-main init finishes, then transitions to `Ready` or `Failed`. No UI surface changes.
- `voiceStyleFile` (PttForegroundService.kt:815-818) stops re-invoking `SupertonicAssetExtractor.extract` on the main thread on every TTS synthesis request. The model directory captured during the off-main init is reused.
- Warm-run and first-run both pass the foreground-service ANR threshold.

**Non-Goals:**
- Deduplicating the two `OrtEnv` instances across `subspace-parakeet` and `subspace-supertonic`. Each crate's `ort` statics are isolated by `cdylib` boundaries; deduplication would require a shared shim crate. The second `dlopen` and `CreateEnv` are real main-thread costs today, but moving them off-main is sufficient to fix the startup symptom. Deduplication is deferred.
- Fixing the Rust `ENGINE` `Mutex<OnceLock<Engine>>` held across `wait_for_load` + synthesis (the poller/inference mutex contention described in exploration). Separate change.
- Moving `TtsAudio.toScoPlayback` resampling or `SttAudio.toParakeetInput` normalization off the main thread. Separate change.
- Adding a progress UI for the first-run ~415MB Supertonic asset extraction. The UI already shows `Loading`; improving this to a progress bar is a UX decision that is out of scope here. The first-run extraction now happens off-main, so the ANR risk is eliminated; the duration is still long but the UI is responsive.
- Changing the `ParakeetJniTranscriber` or `SupertonicJniSynthesizer` constructor `init` blocks. They continue to call `ensureLoaded` + `nativeInit` + `nativeStartLoad`; they just do so on `Dispatchers.IO`.

## Decisions

### Decision 1: Off-main init via `Dispatchers.IO`, not a dedicated dispatcher

**Choice:** Launch two coroutines on `Dispatchers.IO` from `onCreate`, one for STT init and one for TTS init. Each runs the full `initialize*` body (asset extract, `ensureLoaded`, `nativeInit`, `nativeStartLoad`, controller construction, poller/collector launch) sequentially on the IO thread. The pollers and collectors continue to be launched on `serviceScope` (Main.immediate) as before, but only after the IO-coroutine init succeeds.

**Alternatives considered:**

- **A single coroutine doing STT then TTS sequentially**: rejected. The two engines are independent and can initialize in parallel. Two coroutines on `Dispatchers.IO` let them overlap; the slow one (Supertonic, larger asset set) does not block the fast one (Parakeet).
- **A dedicated `CoroutineDispatcher` backed by a single-thread executor**: rejected. `Dispatchers.IO` is already backed by a pool that can run both inits concurrently. A dedicated dispatcher adds lifecycle complexity (shutdown) for no benefit, since the init is one-shot.
- **`WorkManager` OneTimeWorkRequest**: rejected. The init must complete before the controllers are useful, and `WorkManager` adds persistence and scheduling semantics that don't fit a one-shot foreground-service init. `WorkManager` would be appropriate for the deferred first-run asset extraction (see Decision 3), but here we keep the init inline in the service.

**Rationale:** `Dispatchers.IO` is the standard Android choice for one-shot blocking I/O and is already used by `AndroidPcmOutput.playStaticPcm` (AndroidAudio.kt:89). The two inits are independent and benefit from parallelism. The existing pollers and collectors are launched on `serviceScope` to preserve their main-thread state-collection behavior; only the init itself moves.

### Decision 2: Controller and poller construction moves *inside* the init coroutine

**Choice:** The current `initializeStt`/`initializeTts` functions are split. The blocking init (asset extract, `ensureLoaded`, `nativeInit`, `nativeStartLoad`, synthesizer/transcriber construction) runs on `Dispatchers.IO`. After the synthesizer/transcriber is constructed and stored in the service field, the controller construction, poller launch, and collector launch are dispatched back to `serviceScope` via `withContext(Dispatchers.Main)` or `serviceScope.launch { … }` from inside the IO coroutine.

This means the controllers, pollers, and collectors are created only after the off-main init succeeds, and they are created on the main thread (preserving the existing `Main.immediate` semantics for state collection).

**Alternatives considered:**

- **Construct controllers eagerly on main with null synthesizer, fill synthesizer later**: rejected. The controllers take the synthesizer/transcriber as a constructor parameter (`TtsController(scope, sco, output, synthesizer)`, `SttController(scope, sco, captureService, source, output, transcriptionService)`). Making the synthesizer a `var` that is filled later would require the controllers to handle a null synthesizer at every call site, which is a larger change than necessary.
- **Keep controllers on IO thread**: rejected. The controllers' `setEnabled` and PTT callbacks are called from the main thread (UI actions, button events). Moving controller construction to IO but keeping their `scope = serviceScope` (Main.immediate) means their coroutines still run on main, which is the existing behavior. Constructing them on IO and using them from main is fine, but the poller/collector launches need to happen on `serviceScope` to preserve state-collection threading. So the split is: IO for init, `serviceScope.launch` for controllers/pollers/collectors.

**Rationale:** The minimal-diff approach. The init coroutine produces the synthesizer/transcriber, stores it in the service field, then hands off to `serviceScope` for everything that touches `serviceScope`-launched coroutines. The field write is a single `@Volatile`-style assignment; reads from the pollers happen after the launch, so visibility is guaranteed by the coroutine handoff.

### Decision 3: First-run asset extraction stays in the init coroutine (no `WorkManager`)

**Choice:** The first-run ~415MB Supertonic asset copy continues to happen inside the init coroutine on `Dispatchers.IO`. It is not deferred to a separate `WorkManager` job. The UI shows `Loading` for the TTS model readiness field until the copy + load completes.

**Alternatives considered:**

- **Lazy extraction on first TTS screen open with progress UI**: rejected for this change. It's a better UX but is a larger change (new `StateFlow<Float>` progress state, UI surface changes, the Debug Channel config screen needs to observe and render the progress). The proposal's scope is "fix the main-thread blocking," and moving the copy to `Dispatchers.IO` achieves that. The progress-UI improvement is a follow-up.
- **Eager `WorkManager` extraction on first launch with progress notification**: rejected for the same reason. `WorkManager` is appropriate for the progress-UI follow-up but adds persistence and scheduling complexity that the current change doesn't need.

**Trade-off:** On first run, the user opens the app, the foreground notification posts, and the TTS model readiness field shows `Loading` for the duration of the ~415MB copy (estimated 5-30s depending on device flash speed). The UI is responsive throughout. This is strictly better than the current behavior (ANR or multi-second freeze). The progress-UI follow-up is captured as an open question.

### Decision 4: `voiceStyleFile` captures the model directory from the off-main init

**Choice:** The `SupertonicAssetExtractor.extract` call in `voiceStyleFile` (PttForegroundService.kt:815-818) is removed. Instead, the model directory `File` returned by `extract` during the off-main TTS init is stored in a service field (`supertonicModelDir: File?`) and `voiceStyleFile` reads from it. If the field is null (init not yet complete), `requestTtsSynthesis` and the precompute setup early-return (they already early-return if `ttsController` is null, which is the case until init completes).

**Alternatives considered:**

- **Keep `voiceStyleFile` calling `extract` but on `Dispatchers.IO`**: rejected. `extract` does a marker file read every call; even on IO it's unnecessary work on every synthesis request. The marker can only change when the asset version bumps, which happens at app install, not at runtime. Caching the directory from the init-time call is correct and free.
- **Make `extract` lock-free on the warm path**: rejected. It already short-circuits on marker match (SupertonicAssetExtractor.kt:48-50). The problem is not the cost of `extract` on the warm path (a single `readText`), it's that it runs on the main thread on every synthesis request. Caching the directory eliminates the main-thread file I/O entirely.

### Decision 5: No new tests required for the threading behavior, but existing tests are extended

**Choice:** The existing `TtsControllerTest`, `SttTtsControllerTest`, `TtsParameterPropagationTest`, and `FourWayMutualExclusionTest` use `FakeTtsSynthesizer` / `FakeSttTranscriber` and do not exercise the service-level init path. They are unchanged. The change is verified by:

- A new instrumented test (or a note in tasks.md for manual verification per the AGENTS.md physical-device flow) that confirms `onCreate` returns within a threshold (e.g., < 100ms) on warm run, and that the foreground notification posts before the STT/TTS models report `Ready`.
- The existing manual acceptance checks in AGENTS.md ("Tap Grant permissions; enable Bluetooth; ...") continue to pass. The only behavioral difference is that on first launch, the TTS model readiness field shows `Loading` for longer (the duration of the off-main asset copy).

**Alternatives considered:**

- **Unit test that asserts `onCreate` does not call `System.loadLibrary` on the main thread**: rejected. This is testing implementation details, not behavior. The behavior is "onCreate returns fast and the notification posts."
- **No tests, rely on manual acceptance**: rejected. The warm-run threshold is easy to assert with a `SystemClock.elapsedRealtimeNanos` measurement around the init coroutines' launch point in `onCreate`.

## Risks / Trade-offs

- **[Risk] Init coroutine failure leaves controllers null** → The existing `try { … } catch (err: Throwable) { Log.w(...) }` pattern in `initializeStt`/`initializeTts` already handles init failure by leaving `sttTranscriber`/`ttsSynthesizer` null. The init coroutine preserves this: on failure, the field stays null and the controller/poller/collector launches are skipped. The UI continues to show `Loading` (since no poller updates it to `Ready` or `Failed`). This is the same failure behavior as today, except today the failure is synchronous and tomorrow it's async. The poller-based `Failed` status update only fires if `nativeStartLoad` was called; if `nativeInit` fails, the poller is never launched and the status stays `Loading`. This is a pre-existing gap and is out of scope.
- **[Risk] Controller availability is delayed on warm run** → By the duration of the off-main init (~hundreds of ms). If the user opens the Debug Channel and triggers a TTS synthesis before the controller is constructed, `requestTtsSynthesis` early-returns because `ttsController` is null. The UI already shows `Loading` for the model readiness, so the user has a signal that the model isn't ready. Acceptable.
- **[Risk] First-run Supertonic extraction takes 5-30s on IO** → The foreground notification is posted, the UI is responsive, but the TTS model readiness shows `Loading` for a long time. This is strictly better than the current ANR. A progress-UI follow-up is captured as an open question.
- **[Risk] `System.loadLibrary` off-main** → `System.loadLibrary` is safe to call off the main thread. The JVM's loaded-library registry is internally synchronized. This is a common pattern in Android apps with native code.
- **[Trade-off] Two `OrtEnv`s still created** → The second `dlopen` and `CreateEnv` still happen, just off-main. Deduplication is deferred (see Non-Goals). The cost is now invisible to the user.
- **[Trade-off] No progress UI for first-run extraction** → Acceptable for this change. The `Loading` status is the existing UI state and is preserved.

## Migration Plan

- **Deploy**: Single PR. No data migration, no API changes, no schema changes. The asset extraction marker logic is unchanged; the marker file written by a previous install is still recognized, so warm-run behavior is preserved.
- **Rollback**: Revert the PR. The `initialize*` functions return to synchronous main-thread execution. No data cleanup needed.
- **Verification**: Run the manual acceptance checks in AGENTS.md on `B02PTT-FF01`. Confirm the foreground notification posts within ~1s of app launch on warm run, and that the STT/TTS model readiness fields transition from `Loading` to `Ready` within a few seconds. On first install (or after clearing app data), confirm the foreground notification posts immediately and the TTS model readiness shows `Loading` for the duration of the asset copy, with the UI remaining responsive (scrolling, navigating channels) throughout.

## Open Questions

- **First-run progress UI**: Should the connected monitor/test surface show a progress bar (or percentage) for the Supertonic asset extraction instead of the generic `Loading` state? This would require `SupertonicAssetExtractor.extract` to emit progress (e.g., bytes copied / total bytes) via a callback or `StateFlow`, and the UI to render it. Deferred to a follow-up change; captured here so it's not lost.
- **`OrtEnv` deduplication**: Should a shared `subspace-ort-shim` crate be introduced to deduplicate the `dlopen` and `CreateEnv` across `subspace-parakeet` and `subspace-supertonic`? This saves ~150-700ms on warm run, but adds build complexity. Deferred; this change makes the cost invisible to the user, so the dedup is optimization, not fix.