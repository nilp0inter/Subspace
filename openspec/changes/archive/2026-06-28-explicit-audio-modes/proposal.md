## Why

Subspace currently has no first-class concept of input mode. The system is implicitly source-centric: `PttSource { Rsm, Phone, CarMedia, CarTelecom }` drives route selection and behavior, but the three real usage contexts — Work (RSM headset), On-the-road (car steering wheel via Android Auto), and On-a-pinch (phone alone) — are not named, selectable, or observable. This causes concrete problems: the on-the-road path is flaky because SCO is not released after capture (causing the car to redial), the dashboard does not expose which mode is active, and audio route resolution is ad-hoc per source rather than per mode.

## What Changes

- Add a first-class `InputMode { Work, OnTheRoad, OnAPinch }` state machine with explicit transition rules based on device connectivity events and user selection.
- Add a visible mode selector on the main dashboard showing all three modes, with unavailable modes greyed out. The user can transit between any available mode at any time.
- Mode availability gates: Work available when `connection.readyForMonitor` is true (RSM bonded + SPP connected + SCO ready); On-the-road available when Android Auto has an active media browser client connection; On-a-pinch always available.
- Automatic mode transitions on device events: Android Auto connects → On-the-road (from any mode); RSM connects+bonds → Work (from On-a-pinch); On-the-road disconnect → Work if RSM bonded, else On-a-pinch; Work disconnect → On-the-road if AA connected, else On-a-pinch.
- Actuator auto-transition: pressing any mode's preferred actuator transitions to that mode regardless of current mode (RSM PTT → Work, phone channel long-press → On-a-pinch, AA play/pause → On-the-road), then dispatches the PTT.
- Mode-exclusive actuator gating: only the active mode's actuator can start a capture. Other actuators are ignored while a different mode is active.
- Replace source-based audio route resolution with mode-based route resolution. `resolvePttAudioRoute(source)` → `resolvePttAudioRoute(mode)`. Work → SCO via RSM headset; On-the-road → Telecom self-call for SCO acquisition; On-a-pinch → default audio route (phone speaker or BT A2DP headset, not SCO).
- Fix the on-the-road SCO release bug: every PTT release on the on-the-road path must trigger the `TelecomCapturePcmOutput.play()` route switch (release SCO, end call, switch to A2DP for any response audio) even when there is no response to play. This prevents the car from redialing due to dangling SCO.
- Responses on the on-the-road path play via A2DP (media audio) after SCO drops, not via call audio. This keeps the car's media controls available for the next PTT cycle.
- 30-second idle timeout on the on-the-road call session: if no capture and no audio output for 30 seconds, drop the call and SCO entirely.
- Remove the `suppressCarMediaStartUntilMs` 10-second blackout window — it is no longer needed because SCO is always released on PTT release via the route switch.
- Demote `PttSource` to an audit/ownership tag only. It no longer drives route selection or behavior — `InputMode` owns that. `PttSource` remains for `ownsPttRelease` session ownership tracking.
- Remove the unused `VirtualPttAdapter` and `CarMedia` source code paths that were designed for the media-toggle approach (discarded after in-car testing proved direct SCO via media session does not work).

## Capabilities

### New Capabilities
- `input-mode`: Defines the InputMode state machine, availability gates, automatic transition rules, actuator auto-transition, user selection, and mode-exclusive actuator gating.
- `on-the-road-ptt-session`: Defines the on-the-road PTT cycle: call-per-cycle with mandatory SCO route switch on release, A2DP response playback, 30-second idle timeout, and hang-up as the release signal.

### Modified Capabilities
- `channel-routing`: PTT dispatch route resolution changes from source-based to mode-based. Actuator gating becomes mode-exclusive. Actuator presses auto-transition to the actuator's home mode before dispatch.
- `telecom-voip-car-ptt`: On-the-road PTT release must trigger the TelecomCapturePcmOutput route switch even without response audio. The suppressCarMediaStartUntilMs blackout is removed. The 30-second idle timeout is added. VirtualPttAdapter and CarMedia source paths are removed.

## Impact

- `Models.kt`: New `InputMode` enum, `InputModeAvailability` state, `InputModeTransition` rules.
- `PttForegroundService.kt`: New `InputModeController` or equivalent logic for mode state machine, availability, transitions, user selection, and actuator gating. `resolvePttAudioRoute` switches from source to mode. `dispatchPttPressed` checks mode before dispatching. Auto-transition on actuator press.
- `JournalPttController.kt`: `finishSession` must call the route switch (release SCO via `TelecomCapturePcmOutput.play()` or equivalent) even when there is no response to play. File I/O moves to a background dispatcher.
- `CarMediaSessionService.kt`: Remove or simplify — media session is feedback-only, not a PTT input path. Track browser client connections for On-the-road availability detection.
- `VirtualPttAdapter.kt`: Remove (dead code, media-toggle approach discarded).
- `CarPttCommandBus.kt`: Remove or simplify (the media-toggle command path is discarded).
- `MainDashboardScreen.kt`: Add visible 3-segment mode selector with availability-based greying.
- `MainActivity.kt` / `PttUiActions.kt`: Wire mode selection to the service.
- `SubspaceConnection.kt` / `TelecomCarPttCoordinator.kt` / `TelecomCarPttLifecycle.kt`: Add 30-second idle timeout, keep call-per-cycle model, ensure SCO route switch on every release.
- Unit tests for: mode transition rules, availability gates, actuator auto-transition, mode-exclusive gating, on-the-road route switch on release without response, idle timeout.
- Manual verification: on-the-road PTT cycle (press, speak, hang-up, no redial, press again works), mode selector UI, mode transitions on device connect/disconnect.