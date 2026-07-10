## Context

The central PTT manager now accepts a channel target before its ready beep and owns an On-the-road Telecom route. The new ordering correctly proves channel acceptance before users speak, but its terminal model still allows two asynchronous terminal operations to claim the same session. A car hang synchronously emits `onTelecomCaptureStop()` and `onTelecomConnectionEnded()`: the first schedules normal completion and the second schedules forced cancellation. The cancellation can win, so Journal finalizes its WAV without terminal metadata or derived processing.

The same ownership gap leaves `TelecomCapturePcmOutput.releaseRoute()` uncalled when cancellation occurs during setup or a short car press. Separately, starting `AudioRecord` before the ready beep allows pre-beep data to accumulate in its buffer, while recorder-silencing evidence is only supplied by tests. The current Work-route gate is also bypassed by the error-feedback path, and a timed-out car HFP prime can leave voice recognition enabled.

Physical car validation exposed five additional ownership boundaries in the same flow. A production `AudioRecord` can open successfully yet deliver only digital zero; reopening it inside the same dead Telecom/HFP ownership interval is insufficient unless the primed car route is fully handed off before the call. Telecom can advertise Bluetooth support before the exact car route is active and stable, and can reselect a late-connected RSM after an earlier exact-car request succeeded. Finally, Android Auto media state and phone-initiated response playback must follow terminal ownership and normal-media focus rather than callback timing or an unowned communication route.

Constraints: preserve the existing channel boundary; no Android hidden APIs; no new dependencies; retain Work's 30-second warm route and local route's no-op cleanup; retain the established ready-beep-before-channel-visible-audio promise.

## Goals / Non-Goals

**Goals:**

- Make every PTT session have one terminal owner and one exact-once route cleanup path.
- Treat a captured car call hang as normal terminal release, preserving terminal channel delivery and Journal processing.
- Clean pending and short-press Telecom sessions before their active session record is cleared.
- Exclude all pre-ready-beep PCM from channel live frames and terminal recordings.
- Reject Android-reported silenced recorders before the ready beep and channel start.
- Route non-Work problem feedback only after the appropriate Work-release gate and fully hand car HFP priming to Telecom before call ownership.
- Prove production PCM liveness before the ready beep, with one same-route recorder reopen when the first recorder remains digital zero.
- Select one unambiguous car by exact Bluetooth identity, carry that identity through Telecom connection creation, reacquire only that exact route when Android overrides it, and stabilize the identity match before capture starts.
- Publish Android Auto Recording/Finalizing/Ready from owned session phases and route On-a-pinch responses through stable focused media playback.

**Non-Goals:**

- No redesign of channels, Journal file formats, Bluetooth pairing, or the persisted channel configuration.
- Android Auto changes are limited to correct PTT state publication and standard Play/Pause/Stop command semantics.
- No semantic speech detection or user-silence rejection; sustained digital zero is used only as a bounded recorder-path liveness failure before commitment.
- No fallback that commits a capture after route, recorder, focus, or channel commitment failure.
- No recovery conversion of existing interrupted entries into completed captures; recovery remains an abandoned-entry safeguard.

## Decisions

### Decision 1: Atomically claim each terminal transition inside `PttAudioSessionManager`

The manager will add an internal terminal state/claim that is set synchronously before launching any suspendable normal-release or cancellation work. `release`, `cancelActive`, setup failure, and delayed callbacks must first claim the matching session; later terminal requests become no-ops. The claimed session remains active until its own capture and route cleanup have completed.

For terminal operations before capture handoff, the manager cancels or observes setup, releases the resolved Telecom output when applicable, notifies an accepted target only once, then clears the session. For an active car response, the manager also owns the single Telecom release before response media playback and marks the route released so final cleanup is a no-op. `OnTheRoad` cleanup uses `PcmOutput.releaseRoute()` rather than `TelecomCallScoRoute.release()`.

Alternative: retain independent completion and cancellation coroutines with extra identity checks. Rejected: identity does not serialize two concurrent callbacks for the same current session.

### Decision 2: Classify a recorded Telecom disconnect as normal release

The Telecom lifecycle emits a normal capture-stop signal before connection-ended for a session that reached recording. The foreground-service/coordinator boundary will preserve that classification: it dispatches normal release once, and the following connection-ended callback must not force-cancel that release. Connection-ended before capture handoff remains cancellation and follows setup cleanup.

Alternative: have Journal treat `onInputCancelled` as successful finalization. Rejected: cancellation has no reliable terminal recording contract and would conflate failed/partial sessions with accepted captures.

### Decision 3: Keep terminal Journal persistence only behind terminal recording delivery

An accepted Journal target receives `onInputReleased(recording)` only after capture stop. That method remains the sole path that writes `endedAt`, final capture metadata, derived-task state, OGG/transcription output, and Markdown. It joins the `JournalController.processCaptureFile()` job before returning, so manager terminal completion, Android Auto Ready, idle retention, and route cleanup cannot precede derived processing. Cancellation finalizes only a partial writer and leaves recovery to mark an interrupted entry.

Alternative: finalize Journal from a route/Telecom callback. Rejected: route callbacks must remain channel-agnostic and cannot establish capture completion ordering.

### Decision 4: Drain pre-commit capture continuously until ready beep completion

`AndroidCaptureSource.open()` must still start recording before the ready beep to prove recorder startup and inspect active recording configuration. While the beep plays, `CaptureService` will own a pre-commit discard reader for the opened source. It will stop and join that reader before creating `CaptureSessionImpl`; only the post-beep reader can emit live frames or append terminal PCM.

The pre-commit reader owns the source exclusively until its join completes. It discards all chunks and does not update channel frames, terminal PCM, or VU state.

Pre-commit draining uses nonblocking reads so cancellation can stop and join the discard reader without closing the recorder underneath it. After handoff, the committed session becomes the sole reader and uses the source's normal blocking read; this prevents temporarily unavailable PCM from becoming a rapid empty-read loop.

Alternative: defer `AudioRecord.startRecording()` until after the beep. Rejected: a start failure would again occur after the user received the ready signal.

### Decision 5: Combine Android silencing evidence with bounded PCM liveness proof

The Android capture source obtains the opened recorder's active recording configuration after `startRecording()` and uses its `isClientSilenced` value when Android provides it. Explicit client silencing fails before the ready beep; absent configuration remains diagnostic/unknown rather than fabricated healthy state.

Production sources additionally opt into a 500 ms pre-commit liveness proof requiring at least one nonzero PCM sample. If the first recorder remains digital zero, the service closes it, retains the acquired route, waits 100 ms, and opens one replacement recorder. The replacement must independently prove liveness before the ready beep. Two zero-only attempts return recording failure without channel handoff. This is recorder-path validation, not semantic speech or silence inference.

Alternative: treat a single empty or quiet chunk as failure. Rejected: transient read unavailability and legitimate quiet audio are not sufficient evidence; only the bounded production liveness window is fail-closed.

### Decision 6: Apply route gates and cleanup to all non-capture PTT outcomes

The error-feedback helper awaits the resolved route's gate before acquiring or playing feedback. A failed gate must not play through a stale route and must perform the resolved cleanup contract exactly once. Car HFP priming is a bounded ownership pulse: start voice recognition on the exact car, observe connected audio, stop that same device, then observe disconnected audio before placing the Telecom call. Timeout or cancellation stops the exact started device and leaves no priming ownership for Telecom cleanup.

Alternative: retain eager Work release in the dispatcher. Rejected: it duplicates route ownership outside the route-gate model and does not compose with typed gate outcomes.

### Decision 7: Bind Telecom acquisition and readiness to the exact primed car

`CarTelecomStarter` selects the car from connected HFP devices by identity: exclude the exact target RSM and require exactly one connected non-RSM candidate. Unknown RSM identity with multiple candidates, or multiple candidates after exclusion, fails closed rather than choosing the first endpoint or comparing display names. After the selected car completes the connect/stop/disconnect priming pulse, `TelecomCarPttCoordinator.prepareConnection()` reserves that exact `BluetoothDevice` before `placeCall`. The outgoing `ConnectionService` claims the reservation once and constructs `SubspaceConnection` with it; missing, duplicate, or overlapping claims abort connection creation.

`SubspaceConnection` accepts readiness only when `CallAudioState.route` is Bluetooth and `activeBluetoothDevice == expectedBluetoothDevice`. Device names are diagnostic only; duplicate, changed, or unreadable names cannot establish ownership. While the expected car remains in `supportedBluetoothDevices` but is not active, the connection calls the public `requestBluetoothAudio(expectedDevice)` API immediately and retries every 250 ms through one serialized pending loop. The loop stops when the exact car becomes active, disappears from supported devices, or the connection disconnects, aborts, is destroyed, or times out. No substitute Bluetooth device is ever requested.

The exact identity predicate must remain continuously true for 500 ms before readiness is published. Any wrong route or device cancels the pending publication and requires a new full window. This composes route correction with stabilization: retries acquire the intended endpoint, while stabilization proves Android kept it long enough to begin capture.

Alternative: accept any active non-RSM name, including an unreadable name. Rejected after physical validation: a late-connected RSM can become active after Telecom startup, display names are not identity, and an unreadable wrong endpoint would be accepted. Alternative: send one exact-device request. Rejected: Android acknowledged the transition and then selected the RSM again during later call-state processing. Alternative: pass the device through `ConnectionRequest.extras`. Rejected on the target build because Android did not preserve the custom `BluetoothDevice` parcelable into connection creation; the process-local one-shot coordinator reservation retains explicit ownership without hidden APIs.

### Decision 8: Derive Android Auto state from manager-owned phases

The session manager exposes `PttHeld` while no terminal claim exists and `TerminalWork` from claim until capture stop, channel completion, response playback, and route cleanup finish. Android Auto maps those phases to Recording/playing and Finalizing/buffering, then maps the terminal-completion callback to Ready/paused when On-the-road remains available. That same callback starts the 30-second idle-retention timer only after terminal work clears; a new car PTT cancels it. Telecom connection-ended callbacks cannot publish Ready or start idle retention ahead of the terminal owner.

Standard Play starts PTT. Pause and Stop release it. Play/Pause releases only while Recording and otherwise starts PTT. This prevents a second press from being interpreted as another start while the media session still owns an active PTT.

Alternative: derive Finalizing from a fixed 30-second timer. Rejected: a timer can outlive or under-run the actual terminal work and can republish stale buffering after the session is complete.

### Decision 9: Converge local responses on the normal-media playback gate

On-a-pinch retains endpoint-local ready and problem beeps, but recorded channel responses use the same `MediaResponsePlayer` as post-Telecom responses. Playback requires 500 ms of continuously stable `MODE_NORMAL` with no SCO communication device, then transient `USAGE_MEDIA` focus. The final `AudioTrack` remains unpinned so Android media policy can select the phone, A2DP, or Android Auto route. Readiness timeout or focus denial produces no output, and acquired focus is abandoned on success or failure.

Alternative: write the response directly to an unpinned media track. Rejected: Android Auto can accept PCM into its remote submix without rendering it audibly when the application bypasses media-focus arbitration.

## Physical Validation Learnings

- Successful HFP preflight does not prove Telecom will retain that route. With car and a freshly reconnected RSM both present, Android can move HFP from RSM to car for preflight, move it back to the RSM during Telecom startup, and only then honor a repeated exact-car request.
- `requestBluetoothAudio(device)` is a request, not an ownership grant. Correctness requires observing `CallAudioState.activeBluetoothDevice`, retrying only while the exact car remains supported, and bounding all retries by the existing connection lifecycle and route-acquisition timeout.
- `BluetoothDevice` equality is the usable public identity boundary. Names can be duplicated, changed, or unreadable; they remain useful only in diagnostics.
- The asynchronous `placeCall` → `ConnectionService` boundary needs a one-shot in-process reservation. The attempted custom `ConnectionRequest.extras` handoff was not preserved by the target Android build and caused fail-closed connection creation before lifecycle attachment.
- Repeated wrong-route callbacks must not create repeated timers. One serialized retry loop prevents callback amplification, and every disconnect/abort/destroy/timeout path must cancel both retry and stabilization callbacks.
- The decisive device test starts from a fresh RSM power cycle with the RSM visibly owning HFP before car PTT. The passing trace showed preflight selecting the car, Telecom briefly reselecting `B02PTT-FF01`, the retry restoring `CAR MULTIMEDIA`, stable car SCO through capture, no RSM buzz, normal call release, `MODE_NORMAL`, and Android Auto Ready/paused afterward.

## Risks / Trade-offs

- Terminal claiming can keep a session reserved longer while `stop()` or route cleanup suspends. → This is intentional: concurrent PTT must remain rejected until the owner finishes.
- Pre-commit proof consumes an additional coroutine/read loop and can include two 500 ms observation windows plus a 100 ms retry delay before the beep. → All waits are bounded and prevent either pre-beep data leakage or a false ready signal from a zero-producing recorder.
- Android recording configuration may be absent or delayed on vendor builds. → Only explicit client silencing rejects on configuration evidence; unavailable configuration remains unknown, while the independent bounded PCM-liveness contract still applies.
- A Telecom connection can end before capture start. → That remains cancellation, with manager-owned output-route cleanup before the reservation is cleared.
- Existing interrupted entries remain incomplete. → Startup recovery marks them abandoned/failed; this change prevents new normal car hangs from taking that path.

- PCM liveness proof can reject a genuinely silent analog path. → It applies only before commitment, requires sustained digital zero across the bounded window, and retries one fresh recorder on the same route.
- HFP handoff adds connect and disconnect waits before Telecom placement. → Both waits are bounded; failure closes setup rather than entering an overlapping SCO ownership race.
- Route stabilization adds 500 ms before the ready beep. → The delay is intentional because an advertised or transient Bluetooth route is not usable evidence.
- Normal-media playback can be skipped when route stability or focus is unavailable. → Fail-closed silence is preferable to playback through the wrong endpoint.
- Exact-device selection intentionally fails closed when multiple non-RSM HFP candidates remain or target-RSM identity is unavailable with multiple candidates. → Selecting an arbitrary endpoint would recreate the cross-device capture defect.
- Repeated exact-car route requests can increase Bluetooth control traffic while Android reports the wrong active device. → There is one 250 ms retry loop, it runs only while the expected car remains supported, and connection timeout/teardown cancels it.

## Migration Plan

1. Add deterministic manager, Telecom, capture, Journal, media-state, and playback-gate regressions before production edits.
2. Implement terminal claiming and setup/Telecom cleanup, then wire lifecycle callback classification.
3. Implement split pre-commit/committed reads, silencing evidence, bounded PCM liveness proof, and same-route recorder retry.
4. Gate error feedback, select and fully hand off one exact car HFP device, reserve it across Telecom connection creation, reacquire only that route through a bounded serialized retry loop, and stabilize the exact identity match.
5. Derive Android Auto state from terminal phases and converge local responses on stable focused media playback.
6. Run focused unit tests, build/install the debug APK, and validate car capture, Journal finalization, Work transitions, consecutive PTT, Android Auto pause/re-press, and phone-initiated playback on `B02PTT-FF01`.

Rollback: revert the change as one unit. No migration or persisted-schema conversion is required.

## Open Questions

None. Explicit Android client silencing and bounded production PCM liveness are independent fail-closed signals; neither performs semantic speech detection.
