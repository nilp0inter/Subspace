## Why

The previous Android Auto media-session approach can start capture, but once microphone capture engages car call/HFP routing, media button delivery becomes unreliable and the driver can lose the physical stop control. Subspace needs an in-car PTT architecture whose stop path is deterministic under Android Auto and real head-unit behavior.

## What Changes

- Replace the Android Auto media-button virtual PTT control path with a Telecom self-managed VoIP capture session.
- Use a media action or Car App Library screen action only as the start trigger.
- Start capture through Android Telecom by placing a self-managed VoIP call backed by a `ConnectionService`.
- Treat the steering-wheel/head-unit end-call action as the authoritative stop trigger via `Connection.onDisconnect()`/abort paths.
- Start microphone capture only after Telecom reports an active Bluetooth/HFP call audio route.
- End capture and tear down the Telecom connection before processing the captured message.
- Play processed responses through normal media/A2DP playback instead of HFP call audio.
- Remove or disable the unsafe media-session latched PTT behavior from the previous experiment.

## Capabilities

### New Capabilities
- `telecom-voip-car-ptt`: Defines self-managed Telecom/ConnectionService capture sessions for driver-safe in-car PTT start/stop behavior.

### Modified Capabilities
- `channel-routing`: Extends PTT routing requirements so Telecom-originated car capture uses the same active-channel readiness and dispatch semantics as existing RSM and phone-originated PTT.

## Impact

- Android manifest permissions, `ConnectionService` declaration, and phone-account metadata.
- Telecom registration and call placement code for self-managed VoIP calls.
- New capture coordinator that bridges Telecom connection lifecycle to existing PTT dispatch/capture controllers.
- Removal or replacement of Android Auto media-session virtual PTT behavior that depends on media play/pause as both start and stop.
- Optional Car App Library or minimal media action entry point for starting the capture session.
- Audio route handling: capture over Telecom/HFP after call audio route activation; response playback over media/A2DP.
- Unit and service-level tests for connection lifecycle, fail-safe disconnects, readiness checks, and source ownership.
