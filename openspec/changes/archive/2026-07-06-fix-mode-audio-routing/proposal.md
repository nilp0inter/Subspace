> Superseded: this change was archived as a superseded plan. The implemented route model is captured in `openspec/changes/archive/2026-07-06-targeted-rsm-hfp-routing/`, after diagnostics showed the target phone exposes the RSM SCO transport as an anonymous `AudioDeviceInfo` (`productName` is not `B02PTT-FF01`). Do not sync this change's delta specs into main specs; keep it only as historical context.

## Why

Subspace already models `InputMode` as the authority for audio routing, but the current route primitives still accept generic Bluetooth SCO state. When the car and the RSM are connected at the same time, `Work` can treat a car SCO route as usable and `OnTheRoad` can be confused with normal headset SCO, causing capture or response audio to leave through the wrong physical device.

## What Changes

- Make mode route resolution endpoint-specific: `Work` means the B02PTT-FF01 RSM input/output, `OnTheRoad` means the car Telecom/call-audio capture path plus car media response path, and `OnAPinch` means phone/local input/output with no SCO.
- Keep actuator handling as the existing mode-transition mechanism: RSM PTT switches to `Work`, car PTT switches to `OnTheRoad`, and phone channel long-press switches to `OnAPinch`. Route selection remains owned by the current `InputMode`, not by `PttSource`.
- Tighten Work readiness and route validity so a generic active `TYPE_BLUETOOTH_SCO` device is not enough; the active or selected communication endpoint must match the RSM endpoint.
- Tighten OnTheRoad route validity so car capture uses the Telecom-owned call-audio lifecycle and car response audio is played only after the Telecom route switch returns audio to normal/media output.
- Ensure all Work-mode PCM playback, including echo and synthesized/debug responses, explicitly targets the resolved RSM communication device rather than relying on Android's default routing policy.
- Add tests for mixed car-plus-RSM availability and routing so one mode cannot satisfy its route with the other mode's endpoint.

## Capabilities

### New Capabilities

None. This change tightens already-defined mode routing behavior.

### Modified Capabilities

- `input-mode`: Mode availability and actuator auto-transition remain the same, but each mode's audio route policy becomes endpoint-specific.
- `channel-routing`: PTT dispatch continues to resolve routes from active `InputMode`, but route resolution must reject or avoid endpoints belonging to another mode.
- `sco-audio`: SCO acquisition, warm retention, and AudioTrack routing must distinguish the intended endpoint instead of treating any active Bluetooth SCO route as valid.
- `telecom-voip-car-ptt`: On-the-road capture must remain Telecom/car-route-specific and must not be confused with Work/RSM SCO.
- `on-the-road-ptt-session`: Response playback remains car media/A2DP after call route switch; mixed RSM/car connectivity must not redirect it to the RSM route.

## Impact

- `ScoAudioController` / SCO route abstraction: needs endpoint identity checks for the target RSM device instead of type-only active checks.
- `AndroidPcmOutput`: Work-mode playback must target the resolved communication device for all PCM output, not only ready/error beeps.
- `resolveAudioRoute` / `resolvePttAudioRoute`: must encode mode-specific route policies rather than generic SCO/local fallback behavior.
- `PttForegroundService`: route resolution call sites may need to pass the current mode and target endpoint policy explicitly while preserving existing actuator auto-transition behavior.
- Telecom car route classes: verify OnTheRoad stays isolated from normal RSM SCO and only uses media output after call teardown.
- Unit tests: add mixed endpoint cases for Work, OnTheRoad, OnAPinch, echo playback, error beeps, and readiness gates.
- Manual verification: connect both car and RSM, exercise RSM echo/debug PTT, car PTT, and phone long-press; confirm each mode uses its own input/output pair.
