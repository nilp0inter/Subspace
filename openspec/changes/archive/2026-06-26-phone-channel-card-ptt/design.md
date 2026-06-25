## Context

The service currently treats the RSM path as both the input source and the audio route. RSM button events arrive through SPP as `RawButtonEvent` values, and `PttForegroundService` dispatches those events to the active channel after checking readiness. Capture-capable controllers assume a `ScoRoute` and `AndroidPcmOutput`, so ready beeps, error beeps, recording, and playback all require an active Bluetooth SCO route.

The main dashboard already presents functional channel cards that select a single active channel on tap. Those cards are the natural phone-side PTT surface because the pressed card identifies the intended channel before capture starts.

## Goals / Non-Goals

**Goals:**

- Add channel-card long-press as a PTT source for every functional channel.
- Keep PTT source independent from audio route selection.
- Use the actual usable RSM audio route whenever available, including for phone-originated long-press PTT.
- Use the phone loudspeaker local audio route only when the RSM audio route is unavailable.
- Preserve existing PTT timing semantics: readiness gate, not-ready error beep, ready beep before recording, release/cancel handling, max duration, and active-channel dispatch.
- Keep tap-to-activate and config-button behavior intact on channel cards.

**Non-Goals:**

- No manual audio route selector.
- No user-facing manual route selector; phone fallback uses loudspeaker local playback.
- No phone-local audio when the actual RSM audio route is usable.
- No special channel-specific fallback behavior; all functional channels follow the same PTT source and route rules.
- No changes to the RSM protocol or physical button parsing.

## Decisions

### Model PTT source and audio route as separate concerns

PTT source SHALL identify how a session starts: RSM button events or phone channel-card long-press. Audio route SHALL be resolved independently at session start. This avoids making phone long-press imply phone audio, which would violate the desired behavior when RSM audio is available.

Alternative considered: map channel-card long-press to synthetic `RawButtonEvent.PttPressed` / `PttReleased`. This reuses dispatch but hides the source and makes it harder to select the long-pressed channel before capture and to reason about route selection.

### Resolve RSM route from actual audio usability

The route predicate SHALL be actual RSM audio route availability, not generic Bluetooth availability. Generic Bluetooth, bonding, or SPP state can be true while the audio route is unusable, which would risk silent beeps or failed recordings.

Alternative considered: use Bluetooth/device connection state. This is simpler but conflates serial presence with audio capability and would fail in partial-connectivity states.

### Introduce a phone-local audio route/output path

The existing SCO output intentionally checks for `TYPE_BLUETOOTH_SCO`. Phone fallback needs a distinct route/output path that does not acquire SCO and does not require a Bluetooth communication device. It should record from the phone microphone and play beeps/playback through phone loudspeaker local audio routing.

Alternative considered: relax `AndroidPcmOutput` to allow missing SCO. That would blur RSM and phone behavior and make regressions in SCO routing harder to detect.

### Keep channel dispatch centralized

Phone long-press should call a service-level PTT entry point with the channel id and source. The service should set the pressed channel active, resolve the audio route, apply readiness behavior, and then dispatch to the same channel behavior used by RSM PTT.

Alternative considered: implement capture directly in the Compose card. That would duplicate service logic, bypass foreground/service lifecycle behavior, and make background continuity harder to preserve.

### Use long-press as press-and-hold PTT

The long-press threshold distinguishes normal tap activation from PTT. Once the long-press is recognized, the card becomes the active channel and starts PTT. Pointer release or cancellation ends PTT.

Alternative considered: start capture on pointer down immediately. That would make ordinary channel selection accidentally transmit/record.

## Risks / Trade-offs

- Gesture cancellation paths can leave a session active if not handled explicitly → Handle pointer release and cancellation as PTT release.
- Generic channel-card code may not cover future functional channels → Route functional cards through a shared component or shared long-press action contract.
- Local audio routing behavior varies by Android mode/device state → Use local playback attributes that avoid earpiece voice-call routing when Bluetooth audio is unavailable.
- Existing controllers are SCO-coupled → Refactor minimally around an audio route/output abstraction while preserving SCO-specific tests and behavior.
- Long-press plus scrollable dashboard can conflict → Ensure the gesture implementation coexists with vertical scrolling and only starts PTT after long-press recognition.
