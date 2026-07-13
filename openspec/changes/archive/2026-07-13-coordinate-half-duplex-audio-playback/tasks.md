## 1. Protected Boundaries and Domain Contracts

- [x] 1.1 Inventory the existing `InputModeController`, PTT ingress, `PttAudioSessionManager`, route-gate, `CaptureService`, ready-beep, terminal ownership, and Work/Telecom/local capture sequences; record the exact external admission and terminal-completion seams that playback may use
- [x] 1.2 Enforce the critical constraint that implementation SHALL NOT redesign, rewrite, reorder, replace, or move ownership inside the input subsystem; reject any approach that changes existing mode transition, recorder preflight, ready-beep, committed-target, terminal, or route-release mechanics
- [x] 1.3 Add language-neutral host audio operation identities, half-duplex states, capture/playback admission outcomes, controllable playback outcomes, explicit skip outcome, and queue-drain state without Android, route, device, coroutine-scope, or channel-provider types
- [x] 1.4 Add a host-only playback route contract with acquired endpoint output, typed busy/unavailable/failure outcomes, and exactly-once release ownership separate from `ResolvedAudioRoute`

## 2. Durable Queue Pause and Shared Reselection

- [x] 2.1 Extend the atomic durable agent store schema with per-channel `ENABLED` / `PAUSED_BY_USER` playback-drain state and a backward-compatible migration/default
- [x] 2.2 Add atomic operations to commit active-message explicit skip, preserve later messages pending, pause that channel, and clear pause only through deliberate same-channel reselection
- [x] 2.3 Distinguish deliberate shared channel selection/reselection from passive catalogue or projection refresh without creating surface-specific selection paths
- [x] 2.4 Propagate the shared reselection signal from phone, RSM, and car controls so reselecting an already-active channel can resume its paused queue
- [x] 2.5 Project paused playback separately from pending count while preserving device-agnostic phone and Android Auto channel state

## 3. Process-Wide Half-Duplex Coordinator

- [x] 3.1 Implement the host-owned capture/playback/route-transition state machine with atomic operation identity, one terminal owner, and exactly-once release
- [x] 3.2 Add external capture reservation admission before every actuator auto-transition and release an uncommitted reservation when the unchanged existing dispatch path rejects or fails to reserve input
- [x] 3.3 Transfer capture ownership to the existing input session once reserved and release host capture admission only from the existing terminal-completion publication
- [x] 3.4 Admit playback only while no capture reservation, route setup, recording, finalization, playback, or route cleanup owns host audio
- [x] 3.5 Coalesce response, selection, mode, endpoint-readiness, and terminal-completion wakeups as scheduling hints while keeping lease acquisition authoritative
- [x] 3.6 Serialize announcements and other host-produced audio with the same coordinator without moving their policies into channel runtimes or the input subsystem

## 4. Playback-Time Mode Route Strategies

- [x] 4.1 Resolve every host channel-content playback route from the current authoritative InputMode only after the host accepts playback
- [x] 4.2 Acquire a fresh target-RSM HFP/SCO lease for Work playback; never treat retained warm transport as an owned playback route
- [x] 4.3 Acquire and validate car playback using the existing car endpoint rules; fail closed if unavailable and do not use phone or RSM fallback
- [x] 4.4 Acquire and validate phone playback using the existing phone endpoint rules; fail closed if unavailable and do not use car or RSM fallback
- [x] 4.5 Return typed busy, unavailable, and failure outcomes for route acquisition while preserving the pending durable response
- [x] 4.6 Keep an admitted playback stream bound to its acquired endpoint until natural completion, explicit skip, or failure; later mode changes affect only later admissions

## 5. Controllable Streaming Playback

- [x] 5.1 Implement a controllable host stream playback lifecycle with bounded reusable PCM buffers, natural completion, explicit skip, interruption, and failure outcomes
- [x] 5.2 Stream durable delayed channel responses through the host-owned route after synthesis rather than using one-shot direct output
- [x] 5.3 Mix a ducked two-tone PTT-rejection cue in the already-admitted playback stream without creating a second route or interrupting content
- [x] 5.4 Debounce repeated rejected PTT presses per active playback operation so a press produces at most one active or queued cue
- [x] 5.5 Classify natural completion, explicit skip, interruption, and write/route failure distinctly for downstream durable queue handling

## 6. Durable Delayed Playback Integration

- [x] 6.1 Replace agent direct audio wiring with the host-delayed playback capability port; keep agent/runtime contracts device-agnostic
- [x] 6.2 Revalidate channel selection and acquire host admission after synthesis and immediately before starting playback
- [x] 6.3 Leave responses pending and unheard whenever capture owns audio; retry only from host scheduling hints after terminal cleanup
- [x] 6.4 Retain responses for inactive channels in durable FIFO order without acquiring any route or moving them to another channel
- [x] 6.5 Preserve PAUSED_BY_USER queues across re-render, channel navigation, mode changes, and restart recovery
- [x] 6.6 Resume only an explicitly reselected same channel through the shared selection path, preserving FIFO and avoiding a new PTT session

## 7. PTT and SOS Control Integration

- [x] 7.1 Gate RSM, phone, car command, and telecom PTT ingress through host capture admission before existing input-mode auto-transition or session reservation
- [x] 7.2 On PTT rejection during audible playback, preserve stream content and add only the in-stream ducked rejection cue
- [x] 7.3 Consume the matching rejected PTT release so it cannot terminate, mutate, or release a later capture session
- [x] 7.4 Intercept SOS while host playback owns audio, explicitly skip that playback, pause its channel queue, and defer SOS dispatch
- [x] 7.5 Preserve the existing SOS reset behavior when no host playback is active
- [x] 7.6 Resolve SOS, natural completion, explicit skip, and service-shutdown races through the coordinator’s single terminal owner

## 8. Channel-Content Cutover and Composition

- [x] 8.1 Preserve ready beeps and input-session problem feedback on the unchanged session-owned input route
- [x] 8.2 Identify every built-in channel-produced content playback still returned through the input terminal result and migrate it through semantic playback-time routing at the channel/capability boundary without changing `PttAudioSessionManager.runTerminal`
- [x] 8.3 Keep temporary legacy terminal playback only where required during the cutover, then remove migrated direct paths without aliases, shims, or dual playback
- [x] 8.4 Compose the coordinator, route strategies, streaming output, queue scheduler, shared selection, PTT gate, SOS control, announcements, and lifecycle teardown in `PttForegroundService`
- [x] 8.5 Remove the direct delayed-agent `pcmOutput`/`MediaResponsePlayer` selection and any status-driven playback loop that bypasses atomic host admission
- [x] 8.6 Smoke-test one complete agent turn in each mode and confirm capture and playback never overlap, current mode chooses the output, and route cleanup permits the next operation

## 9. Verification and Regression Protection

- [x] 9.1 Add deterministic coordinator tests covering capture-before-playback and playback-before-capture races, reserved/acquiring/releasing states, exact terminal ownership, and wakeup coalescing
- [x] 9.2 Add durable store and scheduler tests for FIFO accumulation, explicit skip, persistent pause, restart recovery, passive-refresh non-resume, and same-channel reselection resume from all three surfaces
- [x] 9.3 Add route-strategy tests for Work/RSM, On-the-road/car, and On-a-pinch/phone selection, fresh playback leases, active-route stability across mode changes, route failure, and no cross-mode fallback
- [x] 9.4 Add controllable playback tests for duck-and-overlay mixing, PCM saturation, debounce, continued speech, SOS stop, route interruption, and cancellation cleanup
- [x] 9.5 Add PTT/SOS integration tests proving rejected PTT occurs before auto-transition, matching release is inert, active SOS does not reset conversation, and idle SOS retains existing reset behavior
- [x] 9.6 Run the full existing input lifecycle, capture-service, SCO, Telecom, local-route, actuator, ready-beep, and terminal-cleanup regression suites and confirm their mechanics and ordering are unchanged
- [x] 9.7 Run all JVM tests and debug/release builds, verify SDK/channel boundaries contain no Android audio or route types, and inspect for leaked route/admission ownership
- [x] 9.8 Verify on the physical RSM/phone/car setup that responses accumulate during recording, play FIFO after cleanup, follow the mode selected at admission, reject PTT with ducked overlay feedback, stop/pause on RSM SOS, resume only on same-channel reselection, survive restart, fail closed on endpoint loss, and tear down foreground audio cleanly

## 10. Field Verification Aids

- [x] 10.1 Add a five-second delayed Debug Echo mode that preserves raw captured audio, releases capture promptly, and becomes eligible through host-owned deferred playback
- [x] 10.2 Render each channel's exact non-zero pending-response count on the phone dashboard card

## Known Field Defects

- On-the-road/Android Auto response playback can still fail to acquire or validate the car output on the physical car setup; preserve the pending response and collect route diagnostics before changing endpoint acceptance.
- On-a-pinch/phone response route acquisition or playback intermittently crashes the app; capture the pre-crash logcat and reproduce the acquisition/playback lifecycle before implementing a fix.
- When the RSM is disconnected and On-a-pinch mode is used, an active phone recording stops prematurely after a seemingly variable duration; reproduce without an RSM connection and capture the complete PTT/session terminal timeline before implementing a fix.
