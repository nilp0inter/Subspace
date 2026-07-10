## 1. Terminal-ownership regression coverage

- [x] 1.1 Add `PttAudioSessionManager` tests proving an active car session retains normal terminal release when connection-ended arrives before its completion coroutine runs.
- [x] 1.2 Add tests proving forced cancellation during a resolved On-the-road route gate, recorder preflight, and ready beep releases the Telecom output exactly once.
- [x] 1.3 Add tests proving a short On-the-road press during preflight and ready beep releases `TelecomCapturePcmOutput` exactly once without channel-visible PCM.
- [x] 1.4 Add Telecom lifecycle/coordinator coverage for active-call hang versus pre-capture disconnect terminal classification.
- [x] 1.5 Add Journal integration coverage proving a normal car hang writes final metadata and invokes configured derived processing, while a pre-capture cancellation remains recoverable.

## 2. Atomic session terminal ownership

- [x] 2.1 Add an internal terminal-claim state to `PttAudioSessionManager` and claim normal release or cancellation synchronously before launching suspendable work.
- [x] 2.2 Route normal release, forced cancellation, setup failure, and stale callbacks through the claim so they cannot redeliver terminal events or double-release a route.
- [x] 2.3 Keep claimed sessions active until their own capture stop/cancellation and route cleanup complete; reject competing PTT during that interval.
- [x] 2.4 Release a resolved Telecom output during On-the-road pre-handoff cancellation when `TelecomCallScoRoute.release()` is a no-op.
- [x] 2.5 Preserve Work CaptureService/SCO warm retention and local no-op cleanup without adding output-route teardown to those setup paths.

## 3. Telecom hang and Journal finalization

- [x] 3.1 Preserve `onTelecomCaptureStop` as normal release when a recorded Telecom lifecycle disconnects, aborts, rejects, or loses its call.
- [x] 3.2 Make `onTelecomConnectionEnded` cancel only pending/pre-capture car sessions and never override a claimed normal car release.
- [x] 3.3 Ensure the committed target receives one `onInputReleased(recording)` before Telecom cleanup after a normal car hang.
- [x] 3.4 Verify Journal normal terminal delivery finalizes WAV, writes terminal metadata, invokes OGG/transcription processing, and regenerates daily Markdown.

## 4. Capture commitment integrity

- [x] 4.1 Add a pre-commit discard reader that exclusively drains opened capture data while the ready beep plays.
- [x] 4.2 Stop and join the discard reader before creating the channel-visible capture session, so live frames and terminal PCM contain post-beep samples only.
- [x] 4.3 Preserve cancellation and source-close semantics when PTT is released or ready-beep playback fails during pre-commit draining.
- [x] 4.4 Populate production `CaptureStartupEvidence.clientSilenced` from the opened `AudioRecord` active recording configuration.
- [x] 4.5 Reject explicitly silenced production capture before ready beep/channel handoff while preserving unknown configuration as non-silenced diagnostic state.

## 5. Route-gate and HFP failure completion

- [x] 5.1 Make non-Work unavailable-channel/problem-feedback paths await and handle the resolved route gate before playing feedback.
- [x] 5.2 Ensure failed feedback route gates perform route cleanup without playing through a stale Work/RSM communication route.
- [x] 5.3 Stop the exact car device's voice recognition when HFP priming starts but its observed readiness wait times out.
- [x] 5.4 Add focused tests for Work-warm → unavailable phone feedback, route-gate timeout, and late car-HFP prime cleanup.

## 6. Verification

- [x] 6.1 Run focused CaptureService, PttAudioSessionManager, Telecom lifecycle/coordinator, Journal, and route-feedback unit tests through the Nix devshell.
- [x] 6.2 Build and install the debug APK through the Nix devshell on `B02PTT-FF01`.
- [x] 6.3 Manually verify car Journal capture: speak after ready beep, hang the call, then confirm finished metadata, OGG/transcription output, Markdown entry, and route release.
- [x] 6.4 Manually verify short car press, pre-capture car disconnect, Work-warm → car, Work-warm → unavailable phone feedback, and Work → Work reuse.

## 7. On-a-pinch media response routing

- [x] 7.1 Route On-a-pinch recorded responses through the stable normal-media and transient-focus lifecycle while retaining the existing raw local ready and problem feedback output.
- [x] 7.2 Add focused regressions for local/media delegation, route-readiness timeout, focus denial, playback ordering, and focus cleanup.
- [x] 7.3 Build and install the corrected debug APK on `B02PTT-FF01`.
- [x] 7.4 Manually verify phone-initiated Debug playback through the connected car media route.

## 8. Physical-route reliability follow-ups

- [x] 8.1 Split production pre-commit nonblocking reads from committed blocking reads and require bounded nonzero PCM proof before readiness.
- [x] 8.2 Reopen one zero-only production recorder on the already acquired route, fail closed after two attempts, and preserve cancellation cleanup.
- [x] 8.3 Convert car HFP priming into an exact-device connect/stop/disconnect handoff completed before Telecom placement.
- [x] 8.4 Carry the primed car identity across Telecom connection creation, actively reacquire only that device when Android selects another endpoint, and require the exact route to remain continuously acceptable before capture readiness.
- [x] 8.5 Derive Android Auto Recording/Finalizing/Ready from manager-owned phases, start the 30-second idle timer only from terminal completion, cancel it on new car PTT, and make Pause/Stop/recording Play-Pause release PTT.
- [x] 8.6 Add focused recorder-recovery, HFP-handoff, route-acceptability, manager terminal-callback ordering, terminal-media-state, and consecutive-call regressions, then verify the corrected flows on device.
- [x] 8.7 Await Journal's configured derived-processing job before terminal channel completion and add deterministic ordering coverage.
- [x] 8.8 Make the audio session manager own the single Telecom release before car response media playback and suppress duplicate final cleanup.
- [x] 8.9 Recheck PTT cancellation after the final recorder liveness window before reporting exhausted-attempt failure, with focused regression coverage.
- [x] 8.10 Select the car as the sole connected non-RSM HFP candidate by exact device identity and fail closed when target ownership is unknown or ambiguous.
- [x] 8.11 Reserve the primed car identity before `placeCall`, claim it exactly once during outgoing connection creation, and reject missing, duplicate, or overlapping handoffs.
- [x] 8.12 Reacquire only the reserved car through one serialized exact-device retry loop, cancel it on success/support loss/teardown, add identity and retry regressions, and verify the fresh-RSM-power-cycle failure sequence on device.
