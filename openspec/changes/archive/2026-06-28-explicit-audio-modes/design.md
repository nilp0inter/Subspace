## Context

Subspace currently routes PTT captures based on `PttSource { Rsm, Phone, CarMedia, CarTelecom }`. The system has three real usage contexts — Work (RSM headset), On-the-road (car steering wheel via Android Auto), and On-a-pinch (phone alone) — but these are not modeled, named, or selectable. The on-the-road path has a confirmed bug: after PTT release, the Telecom self-call's SCO link is not released (because `JournalPttController.finishSession` never calls `route.output.play()`, unlike `EchoController` which does). This causes the car to see a dangling SCO link and redial `100000000`. In-car testing confirmed that triggering the `TelecomCapturePcmOutput.play()` route switch on release (which drops SCO and switches to A2DP) prevents the redial. The media-toggle approach (`VirtualPttAdapter`, `CarMedia` source) was discarded after in-car testing proved that direct SCO via media session does not give access to the car's microphone.

## Goals / Non-Goals

**Goals:**
- Make input mode explicit, observable, and user-selectable.
- Define mode transition rules based on device connectivity events and user selection.
- Gate actuators mode-exclusively: only the active mode's actuator can start a capture.
- Auto-transition to a mode when its preferred actuator is pressed, regardless of current mode.
- Resolve audio routes based on mode, not source.
- Fix the on-the-road SCO release bug by making every on-the-road PTT release trigger the route switch.
- Play on-the-road responses via A2DP (media audio) after SCO drops, keeping media controls available for the next PTT.
- Add a 30-second idle timeout that drops the on-the-road call/SCO session when no capture or audio output occurs.
- Remove the `suppressCarMediaStartUntilMs` blackout window (no longer needed).
- Remove the discarded media-toggle code path (`VirtualPttAdapter`, `CarMedia` source, `CarPttCommandBus` PTT start path).

**Non-Goals:**
- Do not implement a custom Android Auto Compose UI template.
- Do not implement channel configuration or channel browsing from the car.
- Do not change the RSM hardware protocol or SPP connection logic.
- Do not implement inbound message playback, backlog, replay, or priority-channel capture (those remain future work per `STATUS.md`).
- Do not change the journal channel's transcription or encoding pipeline — only its release path and threading.
- Do not add new channels or channel types.

## Decisions

### InputMode is a first-class state, not derived from PttSource

`InputMode { Work, OnTheRoad, OnAPinch }` is a top-level state in `AppState`, sibling to `HardwareMode` (which stays as the RSM's internal Active/Control state). `InputMode` owns audio route resolution, actuator gating, and feedback surface selection. `PttSource` is demoted to an audit/ownership tag for `ownsPttRelease` and is no longer used for route selection.

Alternatives considered:
- Derive mode from `PttSource` at dispatch time. Rejected because mode must be observable and user-selectable before any PTT press, and because the same source (e.g. `CarTelecom`) is only valid in one mode (`OnTheRoad`), so the derivation is trivial and adds no value over an explicit state.
- Store mode as a string preference. Rejected because the mode set is small and compile-time exhaustiveness is useful in Kotlin.

### Mode transition rules are event-driven with user override

The state machine implements these rules:
1. Android Auto connects → On-the-road (from any mode).
2. RSM connects+bonds → Work (from On-a-pinch only; if already On-the-road, user stays there).
3. On-the-road disconnect → Work if RSM bonded, else On-a-pinch.
4. Work disconnect → On-the-road if AA connected, else On-a-pinch.
5. User selects any available mode → that mode (honored at selection time; rules re-assert on the next device event).

Rule 2 is scoped to On-a-pinch → Work only (not On-the-road → Work) because rule 1 already covers AA connect and the user may be driving when RSM connects.

Alternatives considered:
- Rule 4 unconditionally → On-a-pinch (the user's first draft). Rejected because dropping to phone-only while driving is unsafe when AA is available.
- Rule 2 from any mode (including On-the-road). Rejected because an RSM connecting while driving should not force the user off car controls; the user or the AA-disconnect rule handles the transition.

### Mode availability reuses existing readiness gates

- Work available when `connection.readyForMonitor == true` (permissions + BT enabled + bonded + SPP connected + SCO available).
- On-the-road available when `CarMediaSessionService` has at least one active media browser client connection (Android Auto connects as a browser client when it projects).
- On-a-pinch always available.

`CarMediaSessionService` currently does not track browser client connect/disconnect. A client counter (incremented in `onGetRoot`, decremented when the browser service is unbound) feeds a new `AndroidAutoPresenceBus` that the mode controller consumes.

Alternatives considered:
- Track media session active state. Rejected because the session is always active while the service runs; it does not reflect AA projection.
- Use Android Auto's projection API. Rejected because it requires additional permissions and is less reliable than browser client tracking.

### Actuator auto-transition: pressing an actuator transits to its home mode

Regardless of current mode:
- RSM PTT pressed → transit to Work, then dispatch.
- Phone channel long-press → transit to On-a-pinch, then dispatch.
- AA play/pause received → transit to On-the-road, then dispatch.

This subsumes rule 1 (AA connect → On-the-road) for the case where AA initiates audio. The transition happens before `dispatchPttPressed`, and the mode's route resolution is used.

Alternatives considered:
- Ignore non-mode actuators. Rejected because the user's mental model is "I pressed the button, it should work" — auto-transition is more intuitive than a silent ignore.

### Mode-exclusive actuator gating

Only the active mode's actuator can start a capture. If a non-mode actuator fires, it is ignored (no auto-transition, no dispatch). Auto-transition only applies to the active mode's preferred actuator and the explicit actuator-press rules above.

Wait — this contradicts the auto-transition decision. Let me reconcile: the auto-transition rule says "RSM PTT pressed → transit to Work." If the current mode is On-the-road and RSM PTT is pressed, do we transition to Work? The user said "Independently of the mode, if the RSM PTT is pressed, we'll transit automatically to work mode." So yes, any actuator press transitions to its home mode and dispatches. The "mode-exclusive gating" means: after the transition, only the now-active mode's actuator is the one that fired. Other actuators during an active capture are still blocked by `activePttSession != null`.

So the gating is: any actuator can fire from any mode (auto-transition), but only one capture session at a time (`activePttSession` mutex).

Alternatives considered:
- All actuators welcome, last-press-wins, no mode transition. Rejected because it's the current flaky behavior.
- Strict mode-exclusive, no auto-transition. Rejected by the user's explicit request.

### On-the-road: call-per-cycle with mandatory route switch on release

Every on-the-road PTT cycle:
1. `play/pause` → `placeCall(self)` → SCO acquired via in-call audio.
2. Ready beep + capture via call audio (SCO).
3. `hang-up` → `onDisconnect` → release capture.
4. **Mandatory route switch**: call `TelecomCapturePcmOutput.play()` (or its `releaseCaptureRoute` + `awaitTelecomDisconnected` sequence) even if there is no response to play. This drops SCO, ends the call, and returns the car to normal mode.
5. If a response exists, it plays via `MediaResponsePlayer` (A2DP, `USAGE_MEDIA`).
6. Media controls available for the next `play/pause`.

The route switch is the mechanism that prevents the redial. In-car testing confirmed: without it, SCO dangles and the car redials; with it, SCO drops cleanly and the car returns to normal.

For channels that don't play back (like Journal), the route switch must still be triggered. A minimal empty `RecordedPcm` or a dedicated `releaseRoute()` method on `TelecomCapturePcmOutput` achieves this without playing audible audio.

Alternatives considered:
- Keep SCO between cycles for response audio. Rejected because SCO-active locks the car's media controls, preventing the next PTT start.
- Play responses via call audio (SCO). Rejected for the same reason — keeping the call alive locks media controls.
- Persistent call with transparent re-place. Rejected because in-car testing showed the car's hang-up button sends `KEYCODE_UNKNOWN` during the no-call SCO-active phase, making the second PTT cycle unreachable.

### 30-second idle timeout on the on-the-road session

After the route switch (SCO dropped, call ended), start a 30-second idle timer. If no new `play/pause` press arrives within 30 seconds, the on-the-road session is fully cleaned up. If a new press arrives, the timer is cancelled and a new cycle begins.

The idle timeout is not on the call (the call already ended via the route switch). It's on the on-the-road mode's "ready for next cycle" state — a convenience to clean up mode-specific resources. In practice, since each cycle places a new call and drops it, the idle timeout mainly affects the media session state and any mode-specific UI.

Alternatives considered:
- No idle timeout. Rejected because the user's ideal UI includes it.
- Idle timeout on the call. Not applicable since the call ends every cycle.

### Audio route resolution by mode

```
   Work:        resolveAudioRoute(sco, scoOutput, scoRecorder, localOutput, localRecorder)
                → SCO if available (RSM headset), else local fallback
                (unchanged from today's resolveAudioRoute)

   OnTheRoad:   TelecomCapturePcmOutput route
                → SCO via Telecom self-call for capture
                → A2DP via MediaResponsePlayer for output

   OnAPinch:    ResolvedAudioRoute(NoopScoRoute, localOutput, localRecorder)
                → phone speaker/mic or BT A2DP headset, NOT SCO
                → LocalPcmOutput with USAGE_MEDIA
                → PhoneMicRecorder with MIC source
```

On-a-pinch explicitly avoids SCO. Today's `resolveAudioRoute` prefers SCO if any SCO device is available, which is wrong for pinch mode (a BT A2DP headset may report SCO availability but can't actually do SCO capture). Pinch mode uses `NoopScoRoute` + `LocalPcmOutput` + `PhoneMicRecorder` directly.

Alternatives considered:
- Reuse `resolveAudioRoute` for all modes. Rejected because it prefers SCO, which is wrong for pinch mode.

### Remove discarded media-toggle code

`VirtualPttAdapter`, `CarMedia` `PttSource`, `CarPttCommandBus.startTelecomCapture` / `release`, and the `onCarPttStart` / `onCarPttRelease` listener methods are removed. The media session (`CarMediaSessionService`) stays as a feedback-only surface (playback state + metadata) and as the browser client tracker for availability. The `onPlay` callback in `CarMediaSessionService` now calls the on-the-road PTT start path directly (which is `startTelecomCarPtt` — the same as today, just without the `CarPttCommandBus` indirection).

Alternatives considered:
- Keep `VirtualPttAdapter` as a potential future path. Rejected because it's dead code that confuses the architecture.

## Risks / Trade-offs

- **Car head-unit variability**: The call-per-cycle model relies on the car accepting rapid self-call placement. In-car testing on one vehicle (B02PTT-FF01 + user's car) shows ~200ms call setup. Other cars may be slower or block self-calls. → Mitigation: The 30s idle timeout limits unnecessary call placement; the route switch ensures clean teardown. Car compatibility is documented as a known constraint.

- **A2DP response volume vs call volume**: Responses play via A2DP (media audio) which may have a different volume level than call audio. The user may not hear responses if media volume is low. → Mitigation: `MediaResponsePlayer` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and uses `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`. Volume level is a car setting, not an app setting. Document in manual verification.

- **Mode transition during active capture**: If a device event triggers a mode transition while a capture is in flight, the capture should complete on its current route before the transition takes effect. → Mitigation: Mode transitions check `activePttSession != null` and defer until the session completes.

- **Browser client tracking reliability**: Using `MediaBrowserService` client connections as a proxy for "Android Auto is projecting" may not be perfectly accurate (other apps could connect as browser clients). → Mitigation: Accept the false positive — if a browser client is connected, On-the-road is available, and the user can select it. The cost of a false positive is low (mode is available but unused).

- **On-a-pinch route policy change**: Switching pinch mode to `NoopScoRoute` + local-only changes behavior for users who currently use pinch mode with a BT headset that supports SCO. → Mitigation: This is a known trade-off documented in the proposal. Pinch mode is "phone alone" — if the user wants SCO, they should be in Work mode with the RSM.

## Migration Plan

This change is additive for the mode system (new state, new UI) and corrective for the on-the-road release path. Rollback:
- Remove `InputMode` state and mode selector UI; revert `dispatchPttPressed` to source-based routing.
- Revert `JournalPttController.finishSession` to not calling the route switch (restores the redial bug, but the app compiles and runs).
- Re-add `suppressCarMediaStartUntilMs` if the redial behavior returns.
- Re-add `VirtualPttAdapter` and `CarMedia` source if the media-toggle path is needed again (unlikely).

The mode system does not change persisted state (no stored mode preference — mode is derived from connectivity + user selection, and user selection is session-scoped). Channel state, journal data, and debug channel config are unaffected.

## Open Questions

- Should the mode selector show a brief explanation of each mode (e.g. "Work: RSM headset", "On-the-road: steering wheel", "On-a-pinch: phone alone"), or just the mode names? The names alone may not be clear to a new user. → Decision: show names with a one-line subtitle per mode in the selector.
- Should the 30-second idle timeout be configurable, or fixed? → Decision: fixed for now. Can be made configurable later if needed.
- Should the on-the-road mode automatically activate when AA connects (rule 1), or should it require a user confirmation? → Decision: rule 1 fires automatically per the user's explicit request.