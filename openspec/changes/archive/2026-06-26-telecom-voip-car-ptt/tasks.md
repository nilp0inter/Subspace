## 1. Remove Unsafe Media-PTT Path

- [x] 1.1 Disable or remove the Android Auto media-session latched PTT behavior that uses media play/pause as a capture stop path.
- [x] 1.2 Keep or replace only the parts needed for a safe car start/discovery entry point, if still useful.
- [x] 1.3 Ensure media commands received during an active Telecom car capture cannot be required for stopping capture.

## 2. Telecom Foundation

- [x] 2.1 Add the required Android permissions, manifest service declaration, and metadata for a self-managed `ConnectionService`.
- [x] 2.2 Implement `PhoneAccount` registration with `CAPABILITY_SELF_MANAGED` and app-specific labeling/icon metadata.
- [x] 2.3 Add first-run readiness/setup handling for devices that require enabling the Subspace phone account.
- [x] 2.4 Implement a `ConnectionService` that creates one Subspace `Connection` per car PTT capture request.
- [x] 2.5 Place a self-managed VoIP call from the car PTT start action using the registered `PhoneAccountHandle`.

## 3. Capture Lifecycle

- [x] 3.1 Add a Telecom-originated car PTT source to the PTT source model if the existing car source is insufficiently specific.
- [x] 3.2 Implement a capture coordinator that waits for acceptable Telecom call audio state before dispatching PTT pressed.
- [x] 3.3 Prefer Bluetooth/HFP call audio for car capture and fail with a bounded timeout if no acceptable route becomes active.
- [x] 3.4 Stop and finalize capture from `Connection.onDisconnect()`.
- [x] 3.5 Stop and release capture from abort, reject, destroy, call-audio loss, Android Auto/Bluetooth disconnect, service shutdown, and max-duration hooks.
- [x] 3.6 Ensure Telecom connection teardown happens after capture release and does not recursively restart capture.

## 4. Channel Routing And Feedback

- [x] 4.1 Route Telecom-originated car PTT through the existing active-channel readiness checks before placing or continuing a capture connection.
- [x] 4.2 Ensure not-ready Telecom car PTT plays the existing two-tone error beep when possible and leaves capture released.
- [x] 4.3 Preserve source ownership so Telecom disconnect cannot close an RSM or phone-owned PTT session except during fail-safe shutdown.
- [x] 4.4 Reuse existing ready/start feedback after Telecom call audio route becomes active and recording actually starts.

## 5. Response Playback

- [x] 5.1 Add a post-capture media playback path that runs only after the Telecom connection is disconnected.
- [x] 5.2 Request media audio focus for response playback and abandon it after playback completes or fails.
- [x] 5.3 Verify response playback uses normal media output rather than HFP call audio.

## 6. Tests

- [x] 6.1 Add unit tests for Telecom car PTT lifecycle: start request, wait-for-route, route timeout, disconnect release, and abort release.
- [x] 6.2 Add unit tests for active-channel ready and not-ready Telecom car routing.
- [x] 6.3 Add unit tests for source ownership: Telecom release does not close RSM or phone sessions.
- [x] 6.4 Add service-level tests or fakes for conflicting call/audio-loss fail-safe paths where practical without Android Auto hardware.

## 7. Verification

- [x] 7.1 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 7.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug`.
- [x] 7.3 Install on the connected phone with `nix develop --no-write-lock-file -c gradle installDebug`.
- [ ] 7.4 Manually verify on the target car that the start action opens a Subspace call-mode capture session.
- [ ] 7.5 Manually verify that the steering-wheel/head-unit end-call button stops capture without touching the display.
- [ ] 7.6 Manually verify that disconnecting Android Auto/Bluetooth during capture stops recording and leaves no stuck microphone state.
- [ ] 7.7 Manually verify that post-capture response playback occurs over media audio and normal car media resumes afterward.
