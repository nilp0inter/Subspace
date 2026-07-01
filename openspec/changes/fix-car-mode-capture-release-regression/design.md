## Context

Car mode routes PTT through `TelecomCapturePcmOutput`, which drops the Telecom/SCO capture route before any media playback. In `InputMode.OnTheRoad`, `PttForegroundService.resolvePttAudioRoute()` wires that release to `sco.releaseImmediately()` and waits for Telecom disconnect before returning control to media mode.

The current Journal release path violates the intended order. `JournalPttController.finishSession()` calls `route.output.play(RecordedPcm(shortArrayOf(), session.sampleRate))` before `session.stop()`. On the on-the-road route, `TelecomCapturePcmOutput.play()` releases the capture route immediately, so the Telecom/SCO route can be dropped while the centralized capture session is still active.

Other centralized-capture consumers already use the safer shape: `EchoController`, `SttController`, and `SttTtsController` stop the `CaptureSession` first, then release or switch the route after the post-capture consumer completes. The fix should align Journal with that shape without undoing the `CaptureService` refactor.

## Goals / Non-Goals

**Goals:**

- Guarantee that on-the-road route switch/release happens only after active capture has terminally stopped.
- Keep `CaptureService` as the single owner of active audio capture and live PCM frames.
- Preserve Journal-specific safeguards added after the refactor: negotiated sample rate, `cancelAndJoin` before WAV finalization, thread-safe writer finalization, and metadata updates from finalized capture data.
- Make no-response route release explicit via `route.output.releaseRoute()` instead of empty playback.
- Add regression tests that fail on the current early-release ordering.

**Non-Goals:**

- Do not reintroduce `FileWavRecorder`, per-channel `AudioRecord` ownership, or duplicated acquire/beep/record code.
- Do not change Android Auto UI, input-mode selection, Telecom call placement, or media browser behavior.
- Do not change persisted Journal metadata schema or output file layout.
- Do not change the 60-second centralized capture cap in this change.
- Do not add compatibility branches for older unreleased controller behavior.

## Decisions

### 1. Treat route switch as a post-capture terminal action

The route switch belongs after terminal capture stop, not before. For Journal normal release, the ordering should be:

1. Detach the active session from controller state.
2. Cancel and join the frames collector.
3. Stop the `CaptureSession` so the capture source closes and `CaptureService.isCapturing` becomes false.
4. Finalize the WAV writer and write final capture metadata.
5. Trigger no-playback route switch with `route.output.releaseRoute()`.
6. Start Journal derived artifact processing.

The key invariant is that no `TelecomCapturePcmOutput.play()` or `releaseRoute()` call may happen before `session.stop()` has completed.

Alternative considered: keep the current `play(empty)` route switch but move it after `session.stop()`. Rejected because empty playback couples release to media playback semantics and makes tests observe `play` rather than the intended no-playback route release primitive.

### 2. Use `releaseRoute()` as the no-response primitive

`TelecomCapturePcmOutput.releaseRoute()` already performs the route switch without calling `MediaResponsePlayer.play()`. Journal has no response audio, so it should call `route.output.releaseRoute()` exactly once after capture finalization.

Alternative considered: add a new `switchRouteWithoutPlayback()` API. Rejected because `PcmOutput.releaseRoute()` already expresses route-owned release and is used by other controllers.

### 3. Preserve route-owned release semantics

Controllers should continue to release via `route.output.releaseRoute()`, not by reaching into `ScoRoute`. The selected `PcmOutput` determines whether release means warm SCO retention, immediate Telecom route release, or local no-op.

Alternative considered: special-case `TelecomCapturePcmOutput` in Journal. Rejected because the previous special-case made route behavior type-dependent in a channel controller and undermines `ResolvedAudioRoute` as the route abstraction.

### 4. Test observable order, not only counts

The regression exists because tests assert `releaseRoute()` eventually happens, but do not assert that release happens after capture stop. Add fakes that record events such as capture-source close, output `play`, and output `releaseRoute`. The Journal no-playback test should fail against the current code because `play(empty)` records a route switch before the capture source closes.

Alternative considered: assert only `playCount == 0` and `releaseRouteCount == 1`. Rejected as insufficient because it would not prove capture stopped before route release.

## Risks / Trade-offs

- Hardware behavior cannot be fully reproduced by unit tests. -> Unit tests lock controller ordering; final acceptance still requires the documented Android Auto car flow on the target head unit.
- Moving route release earlier than derived Journal processing could expose derived task failures after the car has returned to media mode. -> This is acceptable because derived processing is asynchronous and metadata-driven; route release only depends on finalized capture persistence, not on encoding/transcription completion.
- Over-correcting by waiting for all Journal derived work before route release would keep the car in call mode too long. -> Only wait for capture stop, WAV finalization, and terminal capture metadata; do not wait for encoding/transcription.
- Cancel paths can race collector shutdown and route release. -> Preserve or add `cancelAndJoin` before writer finalization on teardown paths covered by existing Journal requirements.

## Migration Plan

1. Add failing ordering tests for Journal no-playback on a Telecom-like output.
2. Reorder Journal normal release to stop/finalize capture before route release.
3. Remove `play(empty)` from Journal no-response release.
4. Verify existing Echo, STT, STT-TTS, capture-service, and Journal tests still pass.
5. Manually verify on the target car: press play/pause to start, hang up to release, confirm no redial and immediate media-control recovery.

Rollback is to revert the controller and test changes. No data migration is required.

## Open Questions

None for implementation. Hardware acceptance still depends on the physical Android Auto test flow.
