## Why

The app currently depends on the RSM as both the primary PTT input and audio route, so a missing or unusable RSM path blocks field use. The phone needs to provide a secondary PTT input and a local audio fallback while preserving the same channel routing, beeps, capture timing, and readiness behavior.

## What Changes

- Add channel-card long-press as an alternate PTT input for every functional channel.
- Keep audio route selection independent from PTT source: use the RSM audio route whenever actual RSM audio is usable, otherwise use Android's default local audio route.
- Preserve existing ready beep, not-ready error beep, recording-after-beep, release/cancel, max-duration, and channel dispatch semantics for phone-originated PTT sessions.
- Allow phone long-press to act as PTT even while RSM audio is available; in that case audio still routes through the RSM.
- Use phone loudspeaker local audio only as a fallback when the actual RSM audio route is unavailable.

## Capabilities

### New Capabilities
- `phone-channel-card-ptt`: Defines channel-card long-press PTT input, source-independent audio route selection, and phone local audio fallback semantics.

### Modified Capabilities
- `main-device-dashboard`: Channel cards gain long-press PTT behavior in addition to tap-to-activate behavior.
- `channel-routing`: PTT routing becomes source-independent and applies the same readiness, beep, and dispatch behavior to RSM and phone-originated PTT sessions.

## Impact

- Main dashboard Compose channel cards need press/long-press handling without breaking tap activation or config buttons.
- UI actions and service APIs need explicit phone-originated PTT press/release entry points.
- PTT dispatch needs to resolve an audio route per session instead of assuming SCO.
- Audio abstractions need a phone-local route/output path that records from the phone microphone and plays through the phone loudspeaker local route.
- Existing RSM/SCO behavior must remain unchanged when the actual RSM audio route is usable.
