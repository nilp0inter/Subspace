## 1. Contract Tests

- [x] 1.1 Add route-gate tests proving timeout means failure, not success
- [x] 1.2 Add tests proving Work/RSM warm route reuses for Work PTT
- [x] 1.3 Add tests proving Work/RSM warm route blocks On-the-road until observed release
- [x] 1.4 Add tests proving Work/RSM warm route does not leak route objects to phone/local input
- [x] 1.5 Add tests proving car HFP prime failure is not ignored
- [x] 1.6 Add tests proving Telecom setup failure releases the pending route exactly once
- [x] 1.7 Add tests proving channel input starts only after route and capture readiness
- [x] 1.8 Add tests proving route-gate failure reaches channels only as cancellation or failure

## 2. Internal Route Gate Model

- [x] 2.1 Add internal route-gate result types for success, failure, cancellation, and timeout
- [x] 2.2 Add internal route-gate contracts without changing channel APIs
- [x] 2.3 Thread route-gate data through `ResolvedAudioRoute` or route strategy internals
- [x] 2.4 Keep `ChannelRouter` and channel controllers free of Android route objects
- [x] 2.5 Add logging for route-gate observed facts and failure reasons

## 3. Work/RSM Release Gate

- [x] 3.1 Convert immediate Work/RSM release into an observed success/failure path
- [x] 3.2 Require target RSM HFP audio disconnected before switching away from Work
- [x] 3.3 Require `AudioManager.communicationDevice` to no longer be selected RSM SCO before switching away from Work
- [x] 3.4 Preserve 30-second warm retention and reuse for consecutive Work PTT
- [x] 3.5 Fail closed when Work/RSM release cannot be observed before timeout

## 4. On-the-road Readiness Gate

- [x] 4.1 Treat Telecom call audio state as one readiness fact, not the whole readiness decision
- [x] 4.2 Use car HFP prime result as an authoritative setup fact when priming is attempted
- [x] 4.3 Prevent car capture start while stale Work/RSM communication route is still observed
- [x] 4.4 Keep pending On-the-road session ownership while route facts are being collected
- [x] 4.5 Fail or cancel pending On-the-road session when route validation times out or conflicts

## 5. Capture Startup Evidence

- [x] 5.1 Extend internal capture startup result evidence without exposing it to channels
- [x] 5.2 Detect recorder open/start failure before channel `Started` delivery
- [x] 5.3 Use `AudioRecordingConfiguration` silencing/input-device facts where available
- [x] 5.4 Treat missing best-effort recorder facts as diagnostics unless required by the selected route gate
- [x] 5.5 Ensure silent or empty PCM health checks do not leak route internals to channels

## 6. Session Cleanup and Failure Semantics

- [x] 6.1 Release active session route exactly once when setup fails after route resolution
- [x] 6.2 Ensure Telecom route cleanup runs when car capture setup fails before handoff
- [x] 6.3 Preserve wrong-source and stale-session guards for delayed route callbacks
- [x] 6.4 Ensure fail-closed route gates clear pending session reservations
- [x] 6.5 Ensure route-gate failures produce channel cancellation or failure only

## 7. Verification

- [x] 7.1 Run targeted audio input session manager tests
- [x] 7.2 Run targeted SCO audio route tests
- [x] 7.3 Run targeted Telecom car PTT lifecycle tests
- [x] 7.4 Run targeted capture service/source tests
- [x] 7.5 Run focused Gradle test command through the Nix devshell for modified tests
- [x] 7.6 Perform manual device check for RSM warm → car PTT capture on `B02PTT-FF01`
- [x] 7.7 Perform manual regression checks for RSM warm → RSM reuse and RSM warm → phone local capture
