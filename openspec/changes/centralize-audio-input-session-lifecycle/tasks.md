## 1. Contract Tests

- [x] 1.1 Add audio input session tests for single active session across RSM, phone, and car sources
- [x] 1.2 Add tests proving forced cancel releases the active session's resolved route exactly once
- [x] 1.3 Add tests proving wrong-source release does not clear the active session
- [x] 1.4 Add tests proving stale release from an old session cannot clear a newer route
- [x] 1.5 Add tests proving channels receive input events/audio data and no route objects
- [x] 1.6 Add tests for cancellation during setup after route acquisition and before capture handoff
- [x] 1.7 Add tests for car Telecom pending-route reservation blocking phone/RSM PTT

## 2. Audio Input Session Manager

- [x] 2.1 Add channel-facing input event and audio session types
- [x] 2.2 Implement an active audio input session identity with source, channel, mode, and internal route
- [x] 2.3 Implement session start using existing input-mode route resolution and CaptureService
- [x] 2.4 Implement normal source release with terminal PCM delivery and exact-once route release
- [x] 2.5 Implement force cancellation with exact-once route release and channel cancellation/failure event
- [x] 2.6 Implement stale identity and wrong-source guards for release and cancellation
- [x] 2.7 Keep CaptureService as the low-level AudioRecord owner and remove duplicated setup orchestration from callers

## 3. Channel Boundary Migration

- [x] 3.1 Replace route-facing ChannelRouter press/release APIs with channel input event APIs
- [x] 3.2 Migrate Echo controller to consume channel input events and terminal PCM
- [x] 3.3 Migrate STT controller to consume channel input events and terminal PCM
- [x] 3.4 Migrate STT↔TTS controller to consume channel input events and terminal PCM
- [x] 3.5 Migrate Journal controller to consume live frame stream and terminal release events
- [x] 3.6 Migrate Keyboard controller to consume channel input events and terminal PCM
- [x] 3.7 Keep TTS playback behavior working without adding capture-specific route ownership to channels

## 4. Dispatcher and Mode Integration

- [x] 4.1 Wire PttDispatcher press flow to request audio input sessions instead of resolving routes for channels
- [x] 4.2 Wire PttDispatcher release flow to release the active audio input session by source
- [x] 4.3 Replace forceReleaseActivePtt channel-only cleanup with session-scoped audio input cancellation
- [x] 4.4 Preserve actuator auto-transition semantics before strategy/route selection
- [x] 4.5 Preserve not-ready error beep behavior through subsystem-owned route acquisition and release
- [x] 4.6 Ensure mode switch and active channel changes do not release routes outside the active audio session owner

## 5. Telecom and Route Cleanup

- [x] 5.1 Represent car Telecom waiting-for-route as a pending audio input session
- [x] 5.2 Transition pending Telecom session to active capture only after acceptable route readiness
- [x] 5.3 Cancel pending Telecom session on timeout, abort, disconnect, or setup failure
- [x] 5.4 Trigger mandatory On-the-road route switch from session release, not channel cleanup
- [x] 5.5 Preserve Work SCO warm retention and On-a-pinch local no-op release semantics
- [x] 5.6 Prevent stale Work SCO release from clearing a newer active session route

## 6. Cleanup and Verification

- [x] 6.1 Remove channel controller fields and helper paths that store default route/output solely for cleanup
- [x] 6.2 Remove obsolete route-facing controller overloads after all call sites use the audio input subsystem
- [x] 6.3 Remove unused controller registry or update it to reflect the active channel input boundary
- [x] 6.4 Run targeted audio, service, and telecom unit tests covering changed contracts
- [x] 6.5 Run a focused Gradle test command for the modified test classes through the Nix devshell
- [x] 6.6 Document any remaining manual device checks needed for RSM, phone, and Android Auto paths
