## Why

When the car connects to the phone over Bluetooth, the RSM readiness shown by the dashboard can remain disconnected until the user opens Device Link and taps `Retry readiness checks`. That button already performs the correct readiness refresh; the missing behavior is to repeat that refresh automatically while the device is not ready.

## What Changes

- Add a periodic readiness refresh while the foreground device-link service is active and the aggregate device readiness gate is false.
- Reuse the existing `refreshReadiness()` behavior so the automated path checks the same permissions, Bluetooth adapter state, bonded target, and SCO headset availability as the manual button.
- Stop periodic refresh work once the device becomes ready, the user explicitly disconnects serial monitoring, or the service is destroyed.
- Preserve the existing manual `Retry readiness checks` button as an immediate fallback.
- Do not add new pairing, scanning, serial-connect, or Bluetooth-profile connection behavior in this change.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `device-auto-reconnect`: Extend automatic reconnect/readiness behavior so the app periodically refreshes readiness while the device is disconnected/not ready, matching the manual retry-readiness action.

## Impact

- Affected code: `PttForegroundService` readiness/reconnect lifecycle and adjacent tests.
- Affected behavior: dashboard/device-link connected state can recover without manual `Retry readiness checks` after transient Bluetooth/audio routing changes.
- No external APIs, dependencies, permissions, or data formats are changed.
