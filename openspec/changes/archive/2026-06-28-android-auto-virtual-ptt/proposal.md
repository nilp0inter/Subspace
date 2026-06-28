## Why

The `B02PTT-FF01` handheld PTT device is not safe or practical as the primary capture actuator while driving. Subspace needs a driver-safe Android Auto control path that treats the car's media play/pause signal as a latched virtual PTT button while preserving the existing press/release capture model.

## What Changes

- Add an Android Auto virtual PTT capability driven by the car media play/pause transport signal.
- Register Subspace as a minimal Android Auto media source with an active media session so steering-wheel media controls can reach the app.
- Interpret car media play/pause as a toggle that emits synthetic PTT press/release events for the active channel.
- Add fail-safe release behavior for Android Auto disconnect, media-session loss, audio errors, max duration, and service shutdown.
- Add visible and audible recording feedback suitable for driving.
- Keep channel dispatch, readiness checks, error beeps, and capture finalization on the existing PTT routing path.

## Capabilities

### New Capabilities
- `android-auto-virtual-ptt`: Defines Android Auto media transport integration and the latched virtual PTT behavior.

### Modified Capabilities
- `channel-routing`: Extends PTT routing requirements so virtual car-originated PTT uses the same active-channel readiness and dispatch semantics as RSM and phone-originated PTT.

## Impact

- Android manifest entries for Android Auto/media service discovery.
- Gradle dependencies for Android media/session and, if needed, Android for Cars integration.
- `PttForegroundService` or a new adapter around it for car-originated synthetic PTT events.
- `PttSource` model to represent car media-originated PTT sessions.
- Media session lifecycle, playback state, and metadata used to keep Android Auto controls available and reflect recording state.
- Unit tests for virtual PTT toggle, fail-safe release, and channel-routing behavior.
