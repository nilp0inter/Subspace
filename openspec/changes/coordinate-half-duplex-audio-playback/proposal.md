## Why

Subspace has a stable, device-agnostic audio-input subsystem, but delayed channel playback bypasses equivalent mode-aware output coordination and can contend with active capture or target the wrong endpoint. Channel responses need a host-owned, process-wide half-duplex scheduler that keeps messages pending while audio is busy and resolves Work/RSM, On-the-road/car, or On-a-pinch/phone output from the mode selected when playback is admitted—without changing the input subsystem mechanics that already provide reliable capture.

## What Changes

- Add one host-owned half-duplex audio coordinator that atomically serializes capture reservations, route acquisition and release, channel response playback, rejection feedback, and other host audio operations.
- Keep channel/provider/runtime contracts device-agnostic: channels submit semantic input and playback work and never receive `InputMode`, Android audio objects, routes, endpoints, recorders, or outputs.
- Resolve every channel-produced playback route only after output admission, from the currently selected mode: Work uses the target RSM-owned HFP/SCO endpoint, On-the-road uses the car output, and On-a-pinch uses the phone output. Route failure is fail-closed and never falls back across modes.
- Keep inbound responses durable, device-unbound, pending, and FIFO while recording, playback, route transition, another channel selection, or endpoint unavailability blocks admission. Re-evaluate current channel and mode when audio becomes free.
- Reject every RSM, car, or phone PTT press during active response playback before actuator auto-transition or capture reservation. Duck the active speech, overlay one debounced error beep through the already-owned playback route, restore speech, and consume the matching release without changing mode.
- Interpret RSM SOS contextually: during active response playback it stops and explicitly skips only that response, pauses automatic draining of later responses for that channel, and does not reset conversation state. Outside active playback, SOS retains its existing channel-level behavior.
- Resume a user-paused response queue only when the user reselects that same channel through any phone, RSM, or car control surface. New responses, route recovery, mode changes, or ordinary audio-idle notifications do not silently resume it.
- Preserve input-session feedback on the session-owned route, while channel-produced content uses playback-time mode routing.
- **CRITICAL NON-GOAL / IMPLEMENTATION CONSTRAINT:** Do not redesign, rewrite, reorder, or otherwise change the mechanics or invariants of the existing input subsystem. `InputModeController`, PTT actuator auto-transition for admissible PTT, `PttAudioSessionManager` terminal ownership and cleanup, `CaptureService` recorder acquisition/preflight/frame delivery, route gates, ready-beep timing, and existing Work/Telecom/local capture strategies remain authoritative. Integration is limited to an external audio-admission boundary around capture reservation/start and terminal-completion notification; implementations must adapt to the input subsystem, not make the input subsystem adapt to playback.

## Capabilities

### New Capabilities
- `half-duplex-audio-coordination`: Process-wide capture/playback admission, mode-aware playback route ownership, PTT rejection feedback during playback, contextual RSM SOS skipping, and paused-queue resumption policy.

### Modified Capabilities
- `input-mode`: Extend the selected mode from PTT input-route policy to authoritative playback-time output policy without changing existing input-route mechanics or actuator behavior for admissible PTT.
- `channel-host-capabilities`: Require host-owned playback admission, controllable active playback, route resolution, mixing of rejection feedback, cancellation, and cleanup while retaining fully semantic channel contracts.
- `channel-routing`: Keep asynchronous responses pending under capture/output contention, enforce FIFO and half-duplex admission, pause after SOS skip, and resume only on deliberate reselection of the same channel.
- `sco-audio`: Require Work response playback to acquire its own target-RSM-owned SCO lease after admission rather than relying on an originating or merely warm route.

## Impact

- Primary implementation areas: host audio composition, delayed playback scheduling, mode-aware playback-route resolution, active playback control/mixing, PTT admission interception, RSM SOS dispatch, and channel playback projections.
- Existing durable response storage gains queue-pause state and explicit skip handling while preserving pending/heard monotonicity and restart recovery.
- Existing input subsystem files and behavior are protected from internal mechanical changes; only narrow admission and completion integration points may be added around them.
- Existing synchronous channel-generated content may require migration from input-session output to the same playback-time coordinator; ready/error capture feedback remains session-owned.
- Tests must cover capture/playback exclusion, admission races, exact endpoint selection for all three modes, no cross-mode fallback, rejected PTT tone behavior, SOS skip/pause, same-channel reselection resume, durable recovery, and regression of every current input lifecycle invariant.
