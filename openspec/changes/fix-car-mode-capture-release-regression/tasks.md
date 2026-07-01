## 1. Regression Tests

- [x] 1.1 Add a Journal no-playback car-route ordering test that records capture-source close, output `play`, and output `releaseRoute`, and fails if route release occurs before `CaptureSession.stop()` closes the source.
- [x] 1.2 Assert the Journal no-playback path does not call `route.output.play()` with empty PCM and releases via `route.output.releaseRoute()` exactly once.
- [x] 1.3 Add or extend a `TelecomCapturePcmOutput` test proving `releaseRoute()` releases the capture route and awaits Telecom disconnect without invoking `MediaResponsePlayer.play()`.

## 2. Journal Release Ordering

- [x] 2.1 Reorder `JournalPttController.finishSession()` so normal release cancels and joins the frames collector, stops the `CaptureSession`, finalizes the WAV writer, and writes terminal capture metadata before any route switch.
- [x] 2.2 Replace Journal's `route.output.play(RecordedPcm(shortArrayOf(), ...))` no-response route switch with a direct `route.output.releaseRoute()` call after capture finalization.
- [x] 2.3 Ensure Journal derived processing starts only from finalized capture state and does not block car route recovery on encoding, transcription, or markdown regeneration.
- [x] 2.4 Review `JournalPttController.cancelAndRelease()` for the existing collector-join/finalize requirement and align teardown ordering if it can race route release.

## 3. Cross-Controller Verification

- [x] 3.1 Verify Echo, STT, and STT-TTS still stop capture before route release and still release routes from normal, failure, and cancellation paths.
- [x] 3.2 Run targeted unit tests for Journal, route switching, Telecom output, CaptureService, Echo, STT, and STT-TTS through the Nix devshell.
- [x] 3.3 Run the broader Gradle test target through the Nix devshell if targeted tests pass.

## 4. Manual Acceptance

- [ ] 4.1 On the target Android Auto car setup, start on-the-road Journal capture with steering-wheel play/pause, release with hang-up, and verify the car does not redial.
- [ ] 4.2 Verify media controls are available immediately after route switch and a second play/pause starts a new PTT cycle.
- [ ] 4.3 Verify no audible response plays for Journal no-response release, while Echo or response-producing channels still play via media audio after SCO drop.
