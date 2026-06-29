## 1. STT off-main initialization

- [x] 1.1 Extract a `sttModelDir: java.io.File?` service field on `PttForegroundService` to hold the Parakeet model directory captured during the off-main init (mirrors the existing `ttsSynthesizer` nullable field pattern)
- [x] 1.2 Refactor `initializeStt` so the blocking work (`ParakeetAssetExtractor.extract`, `ParakeetJniTranscriber` construction including `ensureLoaded` + `nativeInit` + `nativeStartLoad`) runs inside a `serviceScope.launch(Dispatchers.IO)` coroutine; store the resulting transcriber in `sttTranscriber` and the model dir in `sttModelDir` from the coroutine
- [x] 1.3 Move the STT controller construction, `sttModelStatusJob` poller launch, and the STT status collector launch to run after the off-main init succeeds, dispatched back to `serviceScope` (Main.immediate) via `serviceScope.launch { … }` from inside the IO coroutine, preserving the existing collector and poller behavior
- [x] 1.4 Preserve the existing `try { … } catch (err: Throwable) { Log.w(TAG, "STT transcriber unavailable: ${err.message}") }` failure path: on failure, `sttTranscriber` stays null, the controller/poller/collector launches are skipped, and the UI continues to show `Loading`
- [x] 1.5 Verify `initializeStt` performs zero JNI calls, zero `System.loadLibrary`, and zero asset file I/O synchronously on the main thread; `onCreate` returns immediately after launching the IO coroutine

## 2. TTS off-main initialization

- [x] 2.1 Extract a `supertonicModelDir: java.io.File?` service field on `PttForegroundService` to hold the Supertonic model directory captured during the off-main init
- [x] 2.2 Refactor `initializeTts` so the blocking work (`SupertonicAssetExtractor.extract`, `SupertonicJniSynthesizer` construction including `ensureLoaded` + `nativeInit` + `nativeStartLoad`) runs inside a `serviceScope.launch(Dispatchers.IO)` coroutine; store the resulting synthesizer in `ttsSynthesizer` and the model dir in `supertonicModelDir` from the coroutine
- [x] 2.3 Move the `SystemAnnouncer` construction and `precompute` launch, the TTS controller construction, the STT↔TTS controller construction, the `ttsModelStatusJob` poller launch, and the TTS/STT↔TTS status collector launches to run after the off-main init succeeds, dispatched back to `serviceScope` via `serviceScope.launch { … }` from inside the IO coroutine
- [x] 2.4 Preserve the existing `try { … } catch (err: Throwable) { Log.w(TAG, "TTS synthesizer unavailable: ${err.message}") }` failure path: on failure, `ttsSynthesizer` stays null, the announcer/controller/poller/collector launches are skipped, and the UI continues to show `Loading`
- [x] 2.5 Verify `initializeTts` performs zero JNI calls, zero `System.loadLibrary`, and zero asset file I/O synchronously on the main thread; `onCreate` returns immediately after launching the IO coroutine
- [x] 2.6 Verify the STT↔TTS controller's early-return on null `sttTranscriber` (existing `return` at PttForegroundService.kt:318) still prevents STT↔TTS construction when STT init has not yet completed, even if TTS init has

## 3. Eliminate per-request main-thread asset I/O

- [x] 3.1 Replace the `voiceStyleFile` helper (PttForegroundService.kt:815-818) to read from the `supertonicModelDir` field instead of calling `SupertonicAssetExtractor.extract(this, SUPERTONIC_ASSET_VERSION)`; return `java.io.File(supertonicModelDir, "$style.json")`
- [x] 3.2 Guard `requestTtsSynthesis` (PttForegroundService.kt:800-813) to early-return if `supertonicModelDir` is null (init not yet complete), in addition to the existing `ttsController` null check
- [x] 3.3 Guard the `announcer?.precompute` launch (PttForegroundService.kt:302-304) to use the `supertonicModelDir` already stored from the off-main init, not a fresh `voiceStyleFile(_appState.value.monitor.ttsVoiceStyle).absolutePath` call
- [x] 3.4 Verify no call site of `voiceStyleFile` performs asset file I/O on the main thread

## 4. onCreate ordering and foreground notification

- [x] 4.1 Verify `onCreate` (PttForegroundService.kt:173-229) launches the STT and TTS init coroutines and returns without blocking; `initializeJournal` and `updateActiveControllers` continue to run synchronously (they are cheap and do not perform JNI)
- [x] 4.2 Verify the foreground notification is posted by `onStartCommand` via `ensureForeground` (PttForegroundService.kt:350-354), not by `onCreate`, so moving init off-main does not delay the notification
- [x] 4.3 Add a `Log.d` with `SystemClock.elapsedRealtimeNanos()` around the launch of the two init coroutines in `onCreate` to confirm warm-run `onCreate` completes in < 100ms

## 5. Verification

- [x] 5.1 Run the existing unit tests (`TtsControllerTest`, `SttTtsControllerTest`, `TtsParameterPropagationTest`, `FourWayMutualExclusionTest`, `SttEchoMutualExclusionTest`, `RouteSwitchOnReleaseTest`, `EchoControllerTest`, `CaptureServiceTest`, `TtsAudioTest`, `SttAudioTest`, `AudioRouteResolverTest`) and confirm they pass unchanged — they use fakes and do not exercise the service-level init path
- [x] 5.2 Run `nix develop -c gradle test` and confirm the full unit test suite passes
- [x] 5.3 On `B02PTT-FF01`, perform the manual acceptance checks from AGENTS.md: install debug build (`nix develop -c gradle installDebug`), launch the app (`nix develop -c adb shell am start -n dev.nilp0inter.subspace/.MainActivity`), tap Grant permissions, enable Bluetooth, and confirm the foreground notification posts within ~1s on warm run
- [x] 5.4 On first install (or after clearing app data via `adb shell pm clear dev.nilp0inter.subspace`), confirm the foreground notification posts immediately and the TTS model readiness field shows `Loading` for the duration of the asset copy, with the UI remaining responsive (scrolling, navigating channels) throughout
- [x] 5.5 Confirm the existing manual acceptance checks pass: PTT press/release shows `pressed`/`released`, Group enters Control mode, PTT in Control returns to Active, Volume Up/Down show `clicked` then `idle` after 300ms, echo routes through the headset, the foreground-service notification persists when backgrounded, and `Disconnect serial` removes the notification
- [x] 5.6 Confirm the STT and TTS model readiness fields transition from `Loading` to `Ready` within a few seconds on warm run, and that STT/STT↔TTS/TTS test modes remain mutually exclusive and function correctly
