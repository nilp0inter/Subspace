## Context

The central PTT manager now accepts a channel target before its ready beep and owns an On-the-road Telecom route. The new ordering correctly proves channel acceptance before users speak, but its terminal model still allows two asynchronous terminal operations to claim the same session. A car hang synchronously emits `onTelecomCaptureStop()` and `onTelecomConnectionEnded()`: the first schedules normal completion and the second schedules forced cancellation. The cancellation can win, so Journal finalizes its WAV without terminal metadata or derived processing.

The same ownership gap leaves `TelecomCapturePcmOutput.releaseRoute()` uncalled when cancellation occurs during setup or a short car press. Separately, starting `AudioRecord` before the ready beep allows pre-beep data to accumulate in its buffer, while recorder-silencing evidence is only supplied by tests. The current Work-route gate is also bypassed by the error-feedback path, and a timed-out car HFP prime can leave voice recognition enabled.

Constraints: preserve the existing channel boundary; no Android hidden APIs; no new dependencies; retain Work's 30-second warm route and local route's no-op cleanup; retain the established ready-beep-before-channel-visible-audio promise.

## Goals / Non-Goals

**Goals:**

- Make every PTT session have one terminal owner and one exact-once route cleanup path.
- Treat a captured car call hang as normal terminal release, preserving terminal channel delivery and Journal processing.
- Clean pending and short-press Telecom sessions before their active session record is cleared.
- Exclude all pre-ready-beep PCM from channel live frames and terminal recordings.
- Reject Android-reported silenced recorders before the ready beep and channel start.
- Route non-Work problem feedback only after the appropriate Work-release gate and stop unsuccessful car HFP priming.

**Non-Goals:**

- No redesign of channels, Journal file formats, Android Auto controls, Bluetooth pairing, or the persisted channel configuration.
- No semantic speech/silence inference beyond Android's explicit recorder-silencing evidence.
- No fallback that commits a capture after route, recorder, or channel commitment failure.
- No recovery conversion of existing interrupted entries into completed captures; recovery remains an abandoned-entry safeguard.

## Decisions

### Decision 1: Atomically claim each terminal transition inside `PttAudioSessionManager`

The manager will add an internal terminal state/claim that is set synchronously before launching any suspendable normal-release or cancellation work. `release`, `cancelActive`, setup failure, and delayed callbacks must first claim the matching session; later terminal requests become no-ops. The claimed session remains active until its own capture and route cleanup have completed.

For terminal operations before capture handoff, the manager will cancel or observe setup, release the resolved output route if one exists, notify an accepted target only once, then clear the session. For `OnTheRoad`, route release must use `PcmOutput.releaseRoute()` rather than `TelecomCallScoRoute.release()`.

Alternative: retain independent completion and cancellation coroutines with extra identity checks. Rejected: identity does not serialize two concurrent callbacks for the same current session.

### Decision 2: Classify a recorded Telecom disconnect as normal release

The Telecom lifecycle emits a normal capture-stop signal before connection-ended for a session that reached recording. The foreground-service/coordinator boundary will preserve that classification: it dispatches normal release once, and the following connection-ended callback must not force-cancel that release. Connection-ended before capture handoff remains cancellation and follows setup cleanup.

Alternative: have Journal treat `onInputCancelled` as successful finalization. Rejected: cancellation has no reliable terminal recording contract and would conflate failed/partial sessions with accepted captures.

### Decision 3: Keep terminal Journal persistence only behind terminal recording delivery

An accepted Journal target receives `onInputReleased(recording)` only after capture stop. That method remains the sole path that writes `endedAt`, final capture metadata, derived-task state, OGG/transcription output, and Markdown. Cancellation finalizes only a partial writer and leaves recovery to mark an interrupted entry.

Alternative: finalize Journal from a route/Telecom callback. Rejected: route callbacks must remain channel-agnostic and cannot establish capture completion ordering.

### Decision 4: Drain pre-commit capture continuously until ready beep completion

`AndroidCaptureSource.open()` must still start recording before the ready beep to prove recorder startup and inspect active recording configuration. While the beep plays, `CaptureService` will own a pre-commit discard reader for the opened source. It will stop and join that reader before creating `CaptureSessionImpl`; only the post-beep reader can emit live frames or append terminal PCM.

The pre-commit reader owns the source exclusively until its join completes. It discards all chunks and does not update channel frames, terminal PCM, or VU state.

Alternative: defer `AudioRecord.startRecording()` until after the beep. Rejected: a start failure would again occur after the user received the ready signal.

### Decision 5: Populate recorder readiness from the opened production `AudioRecord`

The Android capture source will obtain the opened recorder's active recording configuration after `startRecording()` and use its `isClientSilenced` value when Android provides it. `CaptureService` will fail before ready beep when that value is `true`; absent configuration remains diagnostic/unknown rather than a fabricated healthy state.

Alternative: infer health from empty PCM. Rejected: silence can be valid user audio and cannot distinguish a silenced client.

### Decision 6: Apply route gates and cleanup to all non-capture PTT outcomes

The error-feedback helper will await the resolved route's gate before acquiring/playing feedback. A failed gate must not play feedback through a stale route; it must perform the route's cleanup contract exactly once when a route was resolved. If car HFP priming starts successfully but readiness times out, the starter must stop voice recognition on that exact device before returning failure.

Alternative: retain eager Work release in the dispatcher. Rejected: it duplicates route ownership outside the route-gate model and does not compose with typed gate outcomes.

## Risks / Trade-offs

- Terminal claiming can keep a session reserved longer while `stop()` or route cleanup suspends. → This is intentional: concurrent PTT must remain rejected until the owner finishes.
- Pre-beep draining consumes an additional coroutine/read loop. → The loop is bounded by beep duration and prevents data leakage across the commitment boundary.
- Android recording configuration may be absent or delayed on vendor builds. → Only explicit `true` is a hard rejection; unavailable state is recorded as unknown.
- A Telecom connection can end before capture start. → That remains cancellation, with manager-owned output-route cleanup before the reservation is cleared.
- Existing interrupted entries remain incomplete. → Startup recovery marks them abandoned/failed; this change prevents new normal car hangs from taking that path.

## Migration Plan

1. Add deterministic manager, Telecom, capture, and Journal regression tests before production edits.
2. Implement terminal claiming and setup/Telecom cleanup, then wire lifecycle callback classification.
3. Implement pre-commit drain and production silencing evidence.
4. Gate error feedback and complete car-HFP timeout cleanup.
5. Run focused unit tests, build/install the debug APK, and validate car hang finalization plus Work-to-car and Work-to-phone transitions on `B02PTT-FF01`.

Rollback: revert the change as one unit. No migration or persisted-schema conversion is required.

## Open Questions

None. Android silencing evidence is fail-closed only when explicitly reported; that is the chosen compatibility boundary.
