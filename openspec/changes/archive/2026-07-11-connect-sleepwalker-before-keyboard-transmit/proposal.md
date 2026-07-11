## Why

When a Keyboard channel is selected, a disconnected Sleepwalker bridge makes the channel immediately reject PTT and emit the problem beep, even though the BLE connection may be recoverable on demand. PTT should first make one bounded connection attempt so transient disconnection does not prevent an otherwise valid capture-and-type cycle.

## What Changes

- Treat a configured Keyboard channel with a disconnected Sleepwalker bridge as recoverable when PTT is requested, while continuing to report its passive readiness as not ready until the bridge is connected.
- Start or join a single bounded Sleepwalker BLE connection attempt before committing the Keyboard channel input session.
- Continue through the existing route preflight, ready beep, capture, transcription, and HID delivery only after the bridge reaches `Connected`.
- Emit the existing problem beep and do not start capture when the connection attempt fails or times out.
- Preserve single-session ownership and handle PTT release, cancellation, repeated presses, and an already-running BLE connection attempt without duplicate captures or connection attempts.
- Keep non-Keyboard channel readiness and problem-feedback behavior unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `keyboard-channel`: Add on-demand, bounded Sleepwalker connection recovery as part of Keyboard PTT preparation.
- `channel-routing`: Allow a disconnected but otherwise configured Keyboard channel to enter recoverable PTT preparation instead of selecting the immediate not-ready error path.
- `audio-input-session-lifecycle`: Keep the PTT session pending and uncommitted during Keyboard connection recovery, then provide ready or problem feedback according to the connection result.

## Impact

- Affects Sleepwalker BLE connection orchestration, Keyboard runtime input preparation, PTT dispatch/readiness gating, and centralized audio-input session setup.
- Requires unit coverage for successful recovery, connection failure/timeout, joining an in-progress connection, cancellation/release while connecting, and unchanged behavior for non-Keyboard not-ready channels.
- Does not change the BLE protocol, HID operation ordering, audio-route selection, channel configuration schema, or user-facing setup controls.
