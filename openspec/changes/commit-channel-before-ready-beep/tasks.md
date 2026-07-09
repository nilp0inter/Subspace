## 1. Contract Tests

- [x] 1.1 Add tests for ready beep ordering after route, capture preflight, and channel acceptance
- [x] 1.2 Add tests proving problem beep fires for pre-commit route failure and ready beep does not
- [x] 1.3 Add tests proving problem beep fires for channel refusal and ready beep does not
- [x] 1.4 Add tests proving On-the-road Telecom route ready without channel commitment fails closed
- [x] 1.5 Add tests proving successful On-the-road PTT plays ready beep through committed car route before channel-visible audio
- [x] 1.6 Add tests proving terminal release uses the originally committed channel target after active-channel/debug-mode changes
- [x] 1.7 Add tests proving post-ready-beep live PCM reaches the committed channel/VU proxy

## 2. Channel Commitment Model

- [x] 2.1 Add internal channel input acceptance result types for accepted, refused, and unavailable states
- [x] 2.2 Add session-local committed channel target abstraction without route objects or Android facts
- [x] 2.3 Replace fire-and-forget `ChannelRouter.onInputStarted` startup with prepare/commit semantics
- [x] 2.4 Ensure committed targets handle release, cancellation, failure, and playback-completion for the same session
- [x] 2.5 Preserve public channel-facing input contract: live frames, sample rate, terminal PCM, cancellation, and failure only

## 3. Channel Controller Migration

- [x] 3.1 Migrate Journal to refuse before ready beep when controller, readiness, or base directory is unavailable
- [x] 3.2 Prepare Journal entry paths/writer before commitment and use them for the committed session
- [x] 3.3 Migrate Debug to snapshot selected mode/controller/model target before ready beep
- [x] 3.4 Migrate Keyboard to refuse before ready beep when controller or transcription dependencies are unavailable
- [x] 3.5 Remove silent nullable-controller drops and silent `onInputStarted` early returns from committed paths

## 4. Ready and Problem Beep Sequencing

- [x] 4.1 Update `PttAudioSessionManager` setup flow to require channel commitment before ready beep
- [x] 4.2 Ensure all pre-commit failures play problem beep when a safe feedback route is available
- [x] 4.3 Ensure ready beep is played exactly once only for committed sessions
- [x] 4.4 Ensure PTT release before ready beep cancels setup without channel-visible user audio
- [x] 4.5 Keep route cleanup exact-once across ready-beep failure, problem-beep path, cancellation, and setup failure

## 5. Telecom Car Ready Beep Routing

- [x] 5.1 Add Telecom-aware car ready/problem beep output path inside the audio input subsystem
- [x] 5.2 Use Telecom call route or call endpoint state as the hard car beep route authority
- [x] 5.3 Treat `AudioManager.communicationDevice` as diagnostic or preferred-device hint for car beep, not sole hard proof
- [x] 5.4 Play On-the-road ready beep through communication/call audio and fail closed if playback cannot complete
- [x] 5.5 Preserve Work/RSM output behavior that requires selected RSM SCO communication device

## 6. Capture Preflight and Post-Beep Delivery

- [x] 6.1 Rework capture setup so capture can be preflighted before ready beep without delivering pre-beep frames to channels
- [x] 6.2 Discard or suppress any pre-beep frames captured for readiness proof
- [x] 6.3 Start channel-visible live frame delivery only after ready beep completes
- [x] 6.4 Preserve terminal PCM semantics for post-beep captured audio
- [x] 6.5 Keep capture setup failures before ready beep as problem-beep/no-ready-beep outcomes

## 7. Verification

- [x] 7.1 Run targeted audio input session manager tests
- [x] 7.2 Run targeted channel router/controller tests
- [x] 7.3 Run targeted capture service sequencing tests
- [x] 7.4 Run targeted Telecom car PTT tests
- [x] 7.5 Run focused Gradle test command through the Nix devshell for modified tests
- [ ] 7.6 Perform manual car validation: On-the-road ready beep is audible through the call and Journal creates an entry
- [ ] 7.7 Perform manual regression checks for Work/RSM and On-a-pinch ready/problem beep behavior
