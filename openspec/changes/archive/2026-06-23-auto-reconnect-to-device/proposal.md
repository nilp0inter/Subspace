## Why

The current device link requires the user to manually open the serial channel again after an unexpected SPP disconnect. That breaks the hardware-first operating model because transient device sleep, radio loss, or app/background transitions can leave the PTT controls unavailable until the user returns to the phone.

## What Changes

- Add automatic recovery for the `B02PTT-FF01` serial control channel after unplanned disconnects.
- Keep retry ownership inside the existing foreground device-link service while the user still intends monitoring to be active.
- Stop automatic retry when the user explicitly disconnects, required permissions are unavailable, Bluetooth is disabled, or the service is destroyed.
- Preserve the existing manual scan, pair, Bluetooth settings, and connect controls as setup and fallback actions.
- Do not add automatic pairing, automatic Bluetooth enablement, hidden Android APIs, or non-SCO audio fallback.

## Capabilities

### New Capabilities
- `device-auto-reconnect`: Defines automatic serial reconnection behavior for the bonded target PTT device after monitoring has been started.

### Modified Capabilities
- None.

## Impact

- Affects the foreground service serial lifecycle in `app/src/main/java/dev/nilp0inter/subspace/service/PttForegroundService.kt`.
- May add small, testable connection retry policy logic under the existing Kotlin app model/service area.
- May adjust connection state presentation to distinguish reconnecting from a hard failure if needed by the implementation.
- No new runtime dependencies, network APIs, persisted channel data, or Android hidden APIs are expected.
