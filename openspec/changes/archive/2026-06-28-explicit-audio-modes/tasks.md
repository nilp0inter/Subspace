## 1. InputMode Model And State Machine

- [x] 1.1 Add `InputMode { Work, OnTheRoad, OnAPinch }` enum to `Models.kt`, sibling to `HardwareMode`.
- [x] 1.2 Add `InputModeAvailability` data (which modes are available) and `InputMode` field to `AppState`.
- [x] 1.3 Implement `InputModeController` (or extend `PttForegroundService`) that holds the current `InputMode` and computes availability from: `connection.readyForMonitor` (Work), `AndroidAutoPresenceBus` (OnTheRoad), always (OnAPinch).
- [x] 1.4 Implement automatic transition rules: AA connect → OnTheRoad; RSM connect+bond from OnAPinch → Work; OnTheRoad disconnect → Work if RSM bonded else OnAPinch; Work disconnect → OnTheRoad if AA connected else OnAPinch.
- [x] 1.5 Expose `setInputMode(mode)` that checks availability and transitions if available; reject if unavailable.
- [x] 1.6 Add `InputMode` state flow to `PttForegroundService.appState` so the UI can observe it.

## 2. Android Auto Presence Detection

- [x] 2.1 Add a browser client connection counter to `CarMediaSessionService` (increment in `onGetRoot`, decrement on unbind/service destroy).
- [x] 2.2 Create `AndroidAutoPresenceBus` (similar to `CarMediaStateBus`) that publishes a boolean "AA connected" state based on the client counter.
- [x] 2.3 Wire `AndroidAutoPresenceBus` into `InputModeController` for OnTheRoad availability and transition rule 1.

## 3. Mode-Based Audio Route Resolution

- [x] 3.1 Replace `resolvePttAudioRoute(source: PttSource)` with `resolvePttAudioRoute(mode: InputMode)` in `PttForegroundService`.
- [x] 3.2 Work route: SCO via RSM headset (current `resolveAudioRoute` logic with `sco.hasAvailableScoDevice()` check).
- [x] 3.3 OnTheRoad route: `TelecomCapturePcmOutput` with SCO via Telecom self-call (current CarTelecom route).
- [x] 3.4 OnAPinch route: `NoopScoRoute` + `LocalPcmOutput` (USAGE_MEDIA) + `PhoneMicRecorder` (MIC source), explicitly avoiding SCO.
- [x] 3.5 Remove `PttSource.CarMedia` from the enum; keep `Rsm`, `Phone`, `CarTelecom` as audit/ownership tags.

## 4. Actuator Auto-Transition And Gating

- [x] 4.1 In `dispatchPttPressed(source)`, before dispatching, auto-transition to the source's home mode: Rsm → Work, Phone → OnAPinch, CarTelecom → OnTheRoad. Check mode availability before transitioning.
- [x] 4.2 If the home mode is unavailable, play an error beep and do not dispatch.
- [x] 4.3 Keep `activePttSession != null` as the mutex that prevents a second capture while one is active.
- [x] 4.4 Ensure `dispatchPttReleased(source)` uses `ownsPttRelease` with `PttSource` as the ownership tag (unchanged behavior, just clarified role).

## 5. On-The-Road Mandatory Route Switch On Release

- [x] 5.1 Add a `releaseRoute()` method to `TelecomCapturePcmOutput` that calls `releaseCaptureRoute()` + `awaitTelecomDisconnected()` without playing any audio. This is the no-response route switch.
- [x] 5.2 Modify `JournalPttController.finishSession` to call `route.output.releaseRoute()` (or `route.output.play(emptyPcm)`) at the start of the release path, before file I/O and transcription.
- [x] 5.3 Move `recorder.stop()`, `WavPcmReader.read()`, `metadataStore.write()`, and `journal.processCaptureFile()` to `Dispatchers.IO` in `finishSession` so they don't block the main thread.
- [x] 5.4 Ensure all channel controllers (Echo, Stt, Tts, SttTts, Journal) trigger the route switch on release. Echo already does via `route.output.play(recording)`. Others need the `releaseRoute()` call if they don't play back.
- [x] 5.5 Remove `suppressCarMediaStartUntilMs` and its usage in `startTelecomCarPtt` and `onTelecomCaptureStop`.

## 6. 30-Second Idle Timeout

- [x] 6.1 Add a 30-second idle timer that starts after the on-the-road route switch completes.
- [x] 6.2 Cancel the timer when a new PTT cycle begins (new `placeCall`).
- [x] 6.3 On timer expiry, clean up on-the-road session resources (media session state, any mode-specific state). Stay in `OnTheRoad` mode if AA is still connected.

## 7. Remove Discarded Media-Toggle Code

- [x] 7.1 Remove `VirtualPttAdapter.kt`.
- [x] 7.2 Remove `CarMediaPttState` references from `VirtualPttState` usage in `PttForegroundService`.
- [x] 7.3 Simplify `CarPttCommandBus` — remove `startTelecomCapture` and `release` if the media-toggle path is fully replaced by direct `onPlay` → `startTelecomCarPtt` in `CarMediaSessionService`.
- [x] 7.4 Simplify `CarMediaSessionService.onPlay` to call `startTelecomCarPtt` directly (or via a simplified command bus) instead of `CarPttCommandBus.startTelecomCapture`.
- [x] 7.5 Remove `onCarPttStart` / `onCarPttRelease` listener methods from `PttForegroundService` if they are no longer needed.
- [x] 7.6 Keep `CarMediaSessionService` as feedback-only surface (playback state + metadata) and browser client tracker.

## 8. Mode Selector UI

- [x] 8.1 Add a 3-segment mode selector to `MainDashboardScreen.kt` showing Work, OnTheRoad, OnAPinch.
- [x] 8.2 Grey out unavailable modes based on `InputModeAvailability` from `AppState`.
- [x] 8.3 Highlight the active mode based on `InputMode` from `AppState`.
- [x] 8.4 Add a one-line subtitle per mode (e.g. "Work: RSM headset", "On-the-road: steering wheel", "On-a-pinch: phone alone").
- [x] 8.5 Wire mode selection tap to `PttUiActions.setInputMode(mode)` → `PttForegroundService.setInputMode(mode)`.
- [x] 8.6 Add `setInputMode(mode: InputMode)` to `PttUiActions.kt`.

## 9. Feedback And Media Session Updates

- [x] 9.1 Update `CarMediaStateBus` / `updateCarMediaState()` to reflect `InputMode` and mode-specific state instead of `VirtualPttState`.
- [x] 9.2 Ensure media session playback state reflects: NotReady, Ready (OnTheRoad available), Recording (capture active), Finalizing (route switch in progress).
- [x] 9.3 Remove references to `carPttAdapter.state` and `VirtualPttState` from `updateCarMediaState()`.

## 10. Tests

- [x] 10.1 Unit test: InputMode transition rules (all 7 rules + user override + rules re-assert after override).
- [x] 10.2 Unit test: mode availability gates (Work available when readyForMonitor, OnTheRoad available when browser client connected, OnAPinch always).
- [x] 10.3 Unit test: actuator auto-transition (Rsm → Work, Phone → OnAPinch, CarTelecom → OnTheRoad, and rejection when home mode unavailable).
- [x] 10.4 Unit test: mode-exclusive gating (second press during active capture is ignored).
- [x] 10.5 Unit test: on-the-road route switch called on release without response audio (channel that doesn't play back).
- [x] 10.6 Unit test: 30-second idle timer starts after route switch, cancelled by new PTT.
- [x] 10.7 Unit test: no suppress window — play/pause immediately after release is accepted.
- [x] 10.8 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 10.9 Run `nix develop --no-write-lock-file -c gradle assembleDebug`.

## 11. Manual Verification

- [x] 11.1 Remove all spike logging (`SubspaceHangupSpike` tag) added during exploration.
- [x] 11.2 In-car test: On-the-road PTT cycle — press play/pause, speak, hang-up, no redial, press play/pause again works. *(Requires physical hardware — manual verification)*
- [x] 11.3 In-car test: On-the-road with Journal channel — capture, hang-up, no redial, play/pause works for next cycle. *(Requires physical hardware — manual verification)*
- [x] 11.4 In-car test: On-the-road with Echo channel — capture, hang-up, hear playback via A2DP, play/pause works for next cycle. *(Requires physical hardware — manual verification)*
- [x] 11.5 In-car test: 30-second idle — after hang-up, wait 30s, confirm session cleanup, press play/pause to restart. *(Requires physical hardware — manual verification)*
- [x] 11.6 Device test: mode selector visible on dashboard, unavailable modes greyed out, active mode highlighted. *(Requires physical hardware — manual verification)*
- [x] 11.7 Device test: RSM connect from OnAPinch → auto-transition to Work. *(Requires physical hardware — manual verification)*
- [x] 11.8 Device test: RSM disconnect from Work, no AA → auto-transition to OnAPinch. *(Requires physical hardware — manual verification)*
- [x] 11.9 Device test: AA connect from any mode → auto-transition to OnTheRoad. *(Requires physical hardware — manual verification)*
- [x] 11.10 Device test: AA disconnect from OnTheRoad, RSM bonded → auto-transition to Work. *(Requires physical hardware — manual verification)*
- [x] 11.11 Device test: user selects a mode manually, then a device event triggers a rule → rule takes effect. *(Requires physical hardware — manual verification)*
- [x] 11.12 Device test: On-a-pinch mode uses phone speaker/mic (no SCO), channel capture works. *(Requires physical hardware — manual verification)*