## Context

`InputMode` is already the product-level authority for routing: `Work` maps to the RSM, `OnTheRoad` maps to the car, and `OnAPinch` maps to the phone. The actuator layer exists to move the app into the correct mode before dispatch: RSM PTT transitions to `Work`, Android Auto PTT transitions to `OnTheRoad`, and phone channel long-press transitions to `OnAPinch`.

The current implementation still has a lower-level ambiguity. `ScoAudioController.isActive()` checks only `communicationDevice.type == TYPE_BLUETOOTH_SCO`, `resolveAudioRoute()` treats any active SCO route as usable, and `AndroidPcmOutput.play()` does not set a preferred output device for normal PCM playback. With both the car and the B02PTT-FF01 RSM connected, those type-only checks can make one mode reuse the other mode's physical endpoint.

## Goals / Non-Goals

**Goals:**

- Preserve `InputMode` as the sole route-selection authority.
- Preserve existing actuator-to-mode transitions.
- Make `Work` route resolution require the B02PTT-FF01 RSM input/output endpoint.
- Make `OnTheRoad` route resolution require the Telecom car call-audio capture path and car media response output path.
- Make `OnAPinch` route resolution avoid SCO and use phone/local audio only.
- Ensure all Work PCM output, including echo playback and debug TTS output, explicitly targets the RSM communication device.
- Add mixed car-plus-RSM tests that fail if generic SCO type checks are used as endpoint proof.

**Non-Goals:**

- No redesign of the channel controller state machines.
- No change to channel readiness rules except endpoint-specific route validity.
- No support for simultaneous RSM and car captures.
- No new UI beyond any existing status text needed to report route failure.
- No persistence change for mode selection.
- No hidden Android APIs.

## Decisions

### D1. Keep mode-authoritative dispatch; make routes endpoint-bound

`PttSource` remains only the press/release ownership tag. `dispatchPttPressed(source)` still asks `InputModeController.autoTransitionFor(source)` first, publishes the resulting mode, then calls `resolvePttAudioRoute(inputModeController.mode)`. The change is inside route resolution: each mode returns a route that can only acquire and output through that mode's physical endpoint.

```
RSM PTT          -> InputMode.Work      -> RSM endpoint route
Car PTT/call     -> InputMode.OnTheRoad -> Telecom car route
Phone long-press -> InputMode.OnAPinch  -> local phone route
```

Alternative considered: make `PttSource` directly choose the route. Rejected because the product rule is mode-authoritative routing; actuators only move the app into a mode.

### D2. Split normal RSM SCO from Telecom car call-audio

The current `ScoAudioController` should become RSM-specific for normal Work-mode SCO. It should find only the B02PTT-FF01 communication device, reject generic SCO fallbacks, and treat an active communication device as active only when it matches the selected RSM endpoint.

The OnTheRoad path should use a separate `ScoRoute` implementation for Telecom car capture. This route should not call `setCommunicationDevice()` with the RSM device. It should only verify that the Telecom lifecycle has reported an acceptable car call-audio route for the active Subspace call, then let `CaptureService` proceed. Release remains owned by `TelecomCapturePcmOutput.releaseRoute()` / `play()` so the call route is torn down immediately before any media response playback.

Alternative considered: keep one `ScoAudioController` and add flags for car vs RSM. Rejected because a single controller would still mix reference counting, warm retention, and endpoint identity for two different lifecycles: Work keeps RSM SCO warm; OnTheRoad must drop call SCO immediately.

### D3. Remove Work local fallback from PTT route resolution

`Work` means RSM. If the RSM endpoint is not available, `Work` should be unavailable and RSM PTT auto-transition should fail before dispatch. Work route resolution should not silently fall back to phone mic or phone speaker, because that violates the mode contract and hides endpoint failures.

`OnAPinch` remains the explicit phone/local fallback mode. That is where phone mic and media output belong.

Alternative considered: keep `resolveAudioRoute()` fallback for Work. Rejected because it makes the visible mode label lie: the user sees Work/RSM but the app may capture or play locally.

### D4. Bind SCO PCM output to the selected communication device for all playback

The SCO-backed output should set `AudioTrack.setPreferredDevice(...)` for ready beeps, error beeps, echo playback, TTS playback, and any other PCM playback. The preferred device should come from the endpoint-bound route, not from a generic type-only `audioManager.communicationDevice` assumption.

At minimum, `AndroidPcmOutput.play(recording)` must follow the same explicit preferred-device behavior as `playReadyBeep()` and `playErrorBeep()`. If the selected endpoint is unavailable at playback time, the output should fail/report an error instead of intentionally falling through to another mode's endpoint.

Alternative considered: rely on `USAGE_VOICE_COMMUNICATION` and Android default routing. Rejected because the reported bug is exactly Android default policy choosing the wrong output when multiple Bluetooth endpoints are present.

### D5. Keep Telecom response playback media-only after route switch

OnTheRoad response playback remains a car media/A2DP concern after the Telecom call route is released. `TelecomCapturePcmOutput.play(recording)` and `releaseRoute()` are the mode boundary: they release the call/SCO path, await Telecom disconnect, then either play media output or complete silently for no-response channels.

The implementation should verify that response playback after an OnTheRoad capture cannot target the RSM SCO output even if the RSM is connected and Work is available.

Alternative considered: play OnTheRoad response audio through SCO call audio. Rejected by the existing on-the-road design because it keeps car media controls unavailable for the next PTT cycle.

### D6. Test endpoint identity with fake route devices

Unit tests should model at least two distinct SCO endpoints: RSM and car. Tests that only expose `hasAvailableScoDevice=true` are insufficient because they reproduce the current generic-SCO bug.

New fakes should prove:

- Work availability is false when only car SCO exists.
- Work route acquisition does not accept active car SCO.
- Work PCM playback sets preferred output to the RSM endpoint.
- OnTheRoad capture does not accept an RSM active route as car call-audio readiness.
- OnTheRoad response playback uses media output after route switch and not RSM SCO.
- OnAPinch never calls SCO acquisition.

## Risks / Trade-offs

- **Android device identity may be unstable across reconnects** -> Match the B02PTT-FF01 by current `AudioDeviceInfo` properties at acquisition time, not by persisting device IDs across sessions.
- **Some Android builds may omit product names for communication devices** -> Treat missing RSM identity as Work unavailable and surface route failure rather than falling back to another endpoint.
- **Telecom call-audio APIs expose route state differently across cars** -> Keep the existing Telecom route lifecycle as the authority for OnTheRoad readiness and fail safe if it does not report an acceptable route before timeout.
- **Removing Work local fallback can make failures more visible** -> This is intentional; `OnAPinch` is the explicit phone/local mode.

## Migration Plan

1. Add endpoint-aware route test fakes and failing tests for mixed car-plus-RSM cases.
2. Refactor the normal SCO controller into an RSM-only route.
3. Add a Telecom car route implementation that verifies Telecom lifecycle readiness and does not select the RSM device.
4. Update `resolvePttAudioRoute(mode)` to return `Work` = RSM route, `OnTheRoad` = Telecom car route, `OnAPinch` = local route.
5. Update SCO-backed PCM output so every playback method uses the selected endpoint as preferred device.
6. Run unit tests and device checks with both car and RSM connected.

Rollback is local: restore the previous generic SCO route resolver and `AndroidPcmOutput` behavior. No persisted data migration is involved.

## Open Questions

None for implementation. If a device reports no usable product name for the RSM communication endpoint, the implementation should fail Work availability rather than guessing.
