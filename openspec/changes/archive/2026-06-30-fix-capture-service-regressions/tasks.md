## 1. JournalWavWriter thread safety (D6 layer 1)

- [x] 1.1 Add `private val lock = Any()` and `@Volatile private var closed: Boolean = false` to `JournalWavWriter`
- [x] 1.2 Wrap `writeChunk` body in `synchronized(lock) { if (closed) return; ... }`
- [x] 1.3 Wrap `finalize` body in `synchronized(lock) { if (closed) return; closed = true; ... }`
- [x] 1.4 Add a unit test `JournalWavWriterTest` that calls `finalize` from one thread while another thread is inside `writeChunk`, asserts no `IOException` and a well-formed WAV header
- [x] 1.5 Add a unit test that calls `writeChunk` after `finalize` and asserts it is a no-op (file size unchanged)
- [x] 1.6 Run `nix develop -c gradle test` and confirm journal tests stay green

## 2. CaptureSession.sampleRate (D4)

- [x] 2.1 Add `val sampleRate: Int` to the `CaptureSession` interface in `CaptureService.kt`
- [x] 2.2 Implement `override val sampleRate: Int = opened.sampleRate` on `ActiveSession`
- [x] 2.3 Update `CaptureServiceTest` to assert `session.sampleRate` matches the fake source's negotiated rate for both 16 kHz and 8 kHz fake sources
- [x] 2.4 Update `CaptureServiceFakes.SingleShotCaptureSource` / `SingleShotOpenedSource` if needed so the sample rate flows through
- [x] 2.5 Run `nix develop -c gradle test` and confirm capture-service tests stay green

## 3. Service SCO release on failure branches + synchronous active clear (D2, D5)

- [x] 3.1 In `CaptureService.startSession`, add `sco.release()` before returning `CaptureStartResult.Cancelled` on both `shouldProceed() == false` branches (after SCO acquire, after beep)
- [x] 3.2 In `CaptureService.startSession`, add `sco.release()` before returning `CaptureStartResult.RecordingFailed` when `source.open()` returns null
- [x] 3.3 Remove the `onFinalized` callback parameter from `ActiveSession` and the launch in `startSession`
- [x] 3.4 In `ActiveSession.finalize`, clear the service's `active` reference synchronously: add a `private val finalizeLock` (already present) and set `if (active === this) active = null` inside the existing `synchronized(finalizeLock)` block. The service needs a reference to do this — pass the service or an `onFinalize: (ActiveSession) -> Unit` lambda that runs synchronously (no `scope.launch`) instead of the current async callback
- [x] 3.5 Update `CaptureServiceTest.stopClearsActiveSoNextStartIsAccepted` to remove the `advanceTimeBy`/`runCurrent` pump and assert the second start is accepted immediately after `stop()` returns
- [x] 3.6 Add a `CaptureServiceTest` scenario asserting `sco.release()` is called once on `Cancelled` (after SCO acquire, PTT released)
- [x] 3.7 Add a `CaptureServiceTest` scenario asserting `sco.release()` is called once on `RecordingFailed` (source open returns null after beep)
- [x] 3.8 Add a `CaptureServiceTest` scenario asserting rapid re-press after `cancelSession` is accepted (no `SessionActive`)
- [x] 3.9 Run `nix develop -c gradle test` and confirm capture-service tests stay green

## 4. ScopedPcmOutput + resolveAudioRoute wiring (D1)

- [x] 4.1 Add `ScopedPcmOutput` class to `AudioPorts.kt` (delegation-only, takes `delegate: PcmOutput` and `releaseRoute: suspend () -> Unit`)
- [x] 4.2 Update `resolveAudioRoute` to wrap `scoOutput` with `ScopedPcmOutput(scoOutput) { scoRoute.release() }` on the SCO branch
- [x] 4.3 Update `resolveAudioRoute` to wrap `localOutput` with `ScopedPcmOutput(localOutput) { }` on the local fallback branch
- [x] 4.4 Add a unit test `ScopedPcmOutputTest` (or extend `AudioRouteResolverTest`) asserting `releaseRoute()` on the SCO branch calls `scoRoute.release()` and on the local branch is a no-op
- [x] 4.5 Run `nix develop -c gradle test` and confirm route resolver tests stay green

## 5. Controller migration (D1, D3)

- [x] 5.1 `EchoController`: replace every `route.sco.release()` in `finishEchoSessionIfNeeded` and `cancelSession` with `route.output.releaseRoute()`; remove `sco.release()` from `cancelAndRelease` and use `output.releaseRoute()` (keeping `sco` parameter as a safety net per design open question)
- [x] 5.2 `EchoController`: on the `Cancelled` and `RecordingFailed` branches of `startEchoSession`, remove the `route.sco.release()` / `cancelSession` SCO release call (the service releases now); keep the status transition and cooldown
- [x] 5.3 `EchoController`: update `EchoControllerTest` to assert `output.releaseRouteCount` instead of `sco.releaseCount`; update the `FakeOutput` to count `releaseRoute` calls; update `FakeScoRoute` to assert it is NOT released by the controller on `Cancelled` / `RecordingFailed`
- [x] 5.4 `SttController`: wrap the `transcribeJob` body in `try/finally` with `route.output.releaseRoute()` in `finally`; remove the per-branch `route.sco.release()` / `route.output.releaseRoute()` calls inside the job
- [x] 5.5 `SttController`: on `Cancelled` / `RecordingFailed` branches, remove the controller-side SCO release; in `cancelAndRelease`, replace `sco.release()` with `output.releaseRoute()`
- [x] 5.6 `SttController`: update `SttControllerTest` to assert `output.releaseRouteCount` instead of `sco.releaseCount`; add a scenario where a new PTT press cancels an in-flight transcription and assert the SCO refcount is balanced (one acquire → one release on the old session, one acquire → one release on the new)
- [x] 5.7 `SttTtsController`: wrap the `transcribeJob` body in `try/finally` with `route.output.releaseRoute()` in `finally`; remove per-branch release calls inside the job
- [x] 5.8 `SttTtsController`: on `Cancelled` / `RecordingFailed` branches, remove the controller-side SCO release; in `cancelAndRelease`, replace `sco.release()` with `output.releaseRoute()`
- [x] 5.9 `SttTtsController`: update `SttTtsControllerTest` to assert `output.releaseRouteCount`; add a transcription-cancelled-by-new-press refcount-balance scenario
- [x] 5.10 `JournalPttController`: in `finishSession`, replace `framesJob?.cancel()` with `framesJob?.cancelAndJoin()`; construct `JournalWavWriter` with `session.sampleRate` instead of the hardcoded `SAMPLE_RATE = 16_000`; remove the `SAMPLE_RATE` companion constant
- [x] 5.11 `JournalPttController`: in `finishSession` and `cancelAndRelease`, replace `route.sco.release()` with `route.output.releaseRoute()`; on the non-`Started` branches of `startSession`, remove the controller-side `route.sco.release()` (the service releases now)
- [x] 5.12 `JournalPttController`: drop the `if (telecomOutput != null) telecomOutput.releaseRoute() else route.output.play(...)` branching in `finishSession` — both branches now go through `route.output.releaseRoute()` (telecom's `releaseRoute` already does the right thing; the `play(RecordedPcm(shortArrayOf(), ...))` no-op play for the non-telecom path stays as a separate call before the release, preserving current behavior)
- [x] 5.13 `JournalPttController`: update any journal PTT tests to assert against `output.releaseRoute()` and `session.sampleRate`; add a scenario with an 8 kHz fake source asserting the WAV header `sampleRate` is 8000
- [x] 5.14 Run `nix develop -c gradle test` and confirm all controller tests stay green

## 6. Integration verification

- [x] 6.1 Run `nix develop -c gradle test` full suite
- [x] 6.2 Run `nix develop -c gradle assembleDebug` and confirm the build succeeds
- [x] 6.3 Run `nix flake check --no-write-lock-file`
- [x] 6.4 Stage any new flake-required files and confirm `nix flake check` still passes
- [x] 6.5 Manual device-test per `AGENTS.md`: echo, STT, STT↔TTS, journal capture, rapid PTT re-press, interrupted transcription, 8 kHz fallback (if reproducible)