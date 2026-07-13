## Why

On-the-road PTT currently infers the car from the connected HFP device set and fails whenever exactly one non-RSM candidate cannot be proven. A user-selected, persisted car identity removes that ambiguity while preserving fail-closed, exact-device Telecom routing.

## What Changes

- Add a dedicated car-device configuration view opened by long-pressing the On-the-road/car input-mode tile, without changing the selected input mode.
- Show currently connected HFP devices using user-readable labels while retaining exact `BluetoothDevice` identity for selection; the known target RSM is not eligible to be configured as the car.
- Persist one selected car identity across service and application restarts and show whether that configured device is currently connected for HFP calls.
- **BREAKING** Require the persisted car identity for On-the-road HFP priming, replacing the previous unique non-RSM candidate inference.
- Fail closed before HFP priming or Telecom placement when no car is configured, the configured identity is unavailable or disconnected, or the configured identity conflicts with the target RSM; never fall back to another connected device or infer the car from display names.
- Preserve the existing exact-device HFP prime, cleanup, expected-device reservation, Telecom handoff, and active-route validation after the configured car has been resolved.

## Capabilities

### New Capabilities
- `car-hfp-configuration`: Connected-HFP device discovery, explicit single-car selection, persisted car identity, configuration status, and replacement behavior.

### Modified Capabilities
- `input-mode`: Long-pressing the On-the-road/car mode tile opens car-device configuration without changing input mode.
- `telecom-voip-car-ptt`: Replace unique-candidate car inference with exact resolution of the persisted configured car before existing HFP priming and Telecom ownership handoff.

## Impact

- Adds a dashboard navigation destination and Compose configuration view for the On-the-road/car tile.
- Adds application-owned storage and observable state for one configured car HFP identity.
- Changes car HFP candidate resolution and diagnostic logging in the foreground-service/Telecom startup path while preserving existing route ownership and lifecycle mechanics.
- Requires focused UI, persistence, resolver, service-composition, HFP priming, and regression tests; no new Android permissions or external dependencies are expected.
- Does not identify or change the On-the-road media-playback output route, redesign RSM setup, support multiple configured cars, or weaken exact-device/fail-closed routing.
