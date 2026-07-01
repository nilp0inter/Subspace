## Context

The Device Link `Retry readiness checks` button calls `PttForegroundService.refreshReadiness()`. That method refreshes the same state that gates the dashboard connection indicator: required permissions, Android Bluetooth enabled state, bonded `B02PTT-FF01` presence, and Bluetooth SCO headset availability.

The observed failure mode is that after the car connects to the phone over Bluetooth, the dashboard can keep showing the RSM as disconnected until the user opens Device Link and taps `Retry readiness checks`. Since that manual action is sufficient, the smallest change is to automate the same readiness refresh while the device is not ready.

## Goals / Non-Goals

**Goals:**

- Periodically invoke the same readiness refresh performed by the manual `Retry readiness checks` button while the service is active and the aggregate device readiness gate is false.
- Allow readiness to recover automatically after transient Bluetooth/audio routing changes, including car Bluetooth connection churn.
- Keep serial reconnect behavior separate: if the existing SPP reconnect loop has already restored the serial channel, the periodic readiness refresh can update the stale readiness gate without requiring user interaction.
- Bound background work by stopping the periodic refresh when readiness becomes true, the user explicitly disconnects serial monitoring, or the service is destroyed.

**Non-Goals:**

- Do not add Bluetooth ACL, profile, or `AudioDeviceCallback` event watchers in this change.
- Do not change SPP reconnect policy timing, retry backoff, or connection prerequisites.
- Do not automatically scan, pair, or initiate a new manual serial connection from the readiness refresh loop.
- Do not change the dashboard readiness definition or split the UI indicator into separate serial/audio states.

## Decisions

### Decision: Use a service-owned periodic refresh job

Add a `Job?` owned by `PttForegroundService` that runs on `serviceScope`. The job wakes at a fixed interval and calls `refreshReadiness()` while `_appState.value.readyForMonitor` is false.

Rationale: the manual button already maps to `refreshReadiness()`. Reusing that method avoids introducing a second readiness path and keeps automated and manual behavior identical.

Alternatives considered:

- `AudioDeviceCallback`: better event precision for SCO availability changes, but it is broader than the requested periodic button-equivalent behavior.
- Bluetooth ACL/profile receivers: useful for target-device connection events, but this issue appears to be stale aggregate readiness after Bluetooth/audio routing churn, not missing pairing or scanning.

### Decision: Refresh only while not ready

The loop should start or continue only while `AppState.readyForMonitor` is false. Once readiness becomes true, the job cancels itself.

Rationale: once the RSM is fully ready, repeated readiness polling has no user-visible benefit and consumes unnecessary work.

### Decision: Do not auto-connect serial from the refresh loop

The refresh loop must not call `connectSerial()` or `startSerialSession()`. It only repeats `refreshReadiness()`.

Rationale: the user requested periodic execution of the button behavior, and the button is a readiness refresh, not a serial connect action. Serial reconnection remains owned by `ReconnectPolicy`.

### Decision: Stop the loop on explicit disconnect and service teardown

`disconnectSerial()` and `onDestroy()` should cancel the periodic refresh job. The loop may be re-created by later service activity when the device is not ready.

Rationale: explicit disconnect represents user cancellation of monitoring intent and should not leave background readiness work running.

## Risks / Trade-offs

- Polling can do redundant work while the device is unavailable -> use a fixed modest interval and stop when ready.
- If the underlying issue is a hung SPP connect rather than stale readiness, periodic refresh alone will not fix serial reconnection -> preserve the existing reconnect policy and keep this change scoped to the observed button-equivalent recovery.
- Calling `refreshReadiness()` from a periodic loop can recursively update connection state -> keep loop lifecycle management idempotent and avoid launching duplicate jobs.
