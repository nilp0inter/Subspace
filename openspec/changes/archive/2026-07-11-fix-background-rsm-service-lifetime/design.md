## Context

`MainActivity` binds `PttForegroundService` with `BIND_AUTO_CREATE` while visible and removes that binding from `onStop()`. Commit `3e00060` moved automatic serial-monitoring intent into `PttForegroundService.onCreate()`, allowing the automatic reconnect path to establish SPP and call `startForeground()` without any preceding `startService()` or `startForegroundService()` request. Foreground promotion does not give a bound-only service started-service lifetime, so Android destroys it when app backgrounding or screen lock removes the last activity binding. Service destruction cancels reconnect and SPP jobs and closes the RSM socket.

The existing explicit **Connect serial** path first issues `startForegroundService(ACTION_START_MONITORING)` and therefore survives activity unbinding. The fix must give automatic startup the same ownership without changing RSM protocol handling, audio routing, channel execution, or explicit-disconnect semantics.

## Goals / Non-Goals

**Goals:**

- Establish started foreground-service ownership before the activity binds to the device-link service.
- Keep automatic SPP monitoring, reconnect scheduling, readiness refresh, the foreground notification, and RSM button dispatch alive across app backgrounding and phone lock.
- Keep monitoring and readiness refresh alive through recoverable prerequisite failures while monitoring intent remains active.
- Keep service startup idempotent across repeated activity start/stop cycles.
- Preserve explicit serial disconnect as the operation that clears monitoring, cancels work, removes foreground ownership, and permits service destruction.
- Verify both Android service ownership and end-to-end RSM behavior on the physical target device.

**Non-Goals:**

- Restarting monitoring after Android kills the application process, device reboot, force-stop, or OEM task removal.
- Persisting monitoring intent across service lifetimes beyond the existing automatic-start behavior.
- Keeping an activity binding after `MainActivity.onStop()`.
- Changing `START_NOT_STICKY`, Bluetooth pairing, SPP framing, reconnect timing, foreground-service types, permissions, audio routes, channel runtimes, or PTT dispatch semantics.
- Adding boot receivers, alarms, jobs, wake locks, retries outside the existing reconnect policy, or compatibility shims.

## Decisions

### D1: Start the foreground service before binding the activity

`MainActivity.onStart()` will issue `ContextCompat.startForegroundService()` with the existing `ACTION_START_MONITORING` intent before calling `bindService()`. The start request gives the service independent started ownership; the binding remains only the UI control and observation channel. `PttForegroundService.onStartCommand()` will continue to promote the service synchronously for that action.

```text
MainActivity.onStart
    ├── startForegroundService(ACTION_START_MONITORING)
    │       └── started service ownership + foreground notification
    └── bindService(BIND_AUTO_CREATE)
            └── UI access only

MainActivity.onStop
    └── unbindService
            └── started service and RSM monitoring remain alive
```

This reuses the already-correct explicit-connect startup mechanism and removes the path-dependent difference between automatic and manual connection.

**Alternatives considered:**

- Keeping the activity bound in `onStop()` was rejected because it couples background work to an Activity instance, risks leaking the Activity, and still provides no durable started-service ownership.
- Calling `startForeground()` from the bound service was rejected because that is the current failure: foreground status does not itself create a started service.
- Having the service start itself from `onCreate()` was rejected because it is re-entrant, obscures ownership, and is more exposed to Android background-start restrictions than a request issued while the Activity is visible.
- Changing to `START_STICKY` was rejected because it does not prevent destruction after the last binding and would broaden behavior to process-death restoration without persisted monitoring state.

### D2: Preserve service-lifetime monitoring state

Automatic `reconnectPolicy.startMonitoring()` remains a once-per-service-lifetime initialization. `ACTION_START_MONITORING` establishes started foreground ownership but does not unconditionally re-enable monitoring in an already-running service. Manual **Connect serial** continues to call `connectSerial()` explicitly. Consequently, repeated activity starts are idempotent, while **Disconnect serial** continues to suppress monitoring for the remainder of the current service instance.

When explicit disconnect calls `stopForeground()` and `stopSelf()`, an existing UI binding may temporarily retain the service object. Once the Activity unbinds, Android can destroy that stopped service. A later service instance may restore automatic startup as required by the existing reconnect specification.

**Alternative considered:** moving `startMonitoring()` into every `ACTION_START_MONITORING` delivery was rejected because an Activity restart could undo an explicit disconnect within the same service lifetime.

### D3: Keep process-death behavior unchanged

The service remains `START_NOT_STICKY`. This change guarantees survival of normal Activity unbinding caused by app backgrounding and screen lock; it does not claim survival after Android terminates the process. Correct process-death restoration would require a separate persisted-intent and restart-policy design.

### D4: Verify ownership before adding regression coverage

Verification will first reproduce the Android lifecycle on the physical target: automatic connection without tapping **Connect serial**, app background/lock, service and notification persistence, and working RSM press/release. Focused automated coverage will then defend startup ordering and explicit-disconnect invariants without pretending a local unit test can emulate Android service ownership.

### D5: Retain foreground ownership through blocked prerequisites

While monitoring intent remains active, reconnect decisions blocked by missing permissions, disabled Bluetooth, or temporary target unavailability will publish the not-ready state but will not call `stopForeground()` or `stopSelf()`. The started service and its readiness-refresh loop remain active. Once the prerequisite recovers, the next refresh re-enters the existing serialized automatic connection scheduler.

`MonitoringNotRequested` remains a terminal block reason: explicit disconnect still removes foreground ownership and allows service destruction.

**Alternative considered:** stopping on every blocked prerequisite was rejected because no component remains to observe recovery while the app is backgrounded; the user would have to reopen the app even though monitoring intent was never cancelled.

## Risks / Trade-offs

- **[Risk] The foreground notification can appear as soon as the app starts, before SPP prerequisites are ready.** → This is consistent with retained automatic monitoring intent and periodic readiness refresh; explicit disconnect remains the user-controlled stop.
- **[Risk] Repeated `onStart()` calls deliver repeated start intents.** → Keep the existing action idempotent and keep reconnect serialization in `ReconnectPolicy`.
- **[Risk] A foreground-service promotion failure still prevents background operation.** → Exercise notification/permission state on the API 35 target during the physical smoke test; do not mask failure by retaining the Activity binding.
- **[Risk] Recoverable prerequisite failures keep an idle foreground notification and readiness poll active.** → Poll only at the existing interval and only while monitoring intent remains active; explicit disconnect terminates both.
- **[Risk] OEM process management can still kill the process while locked.** → Keep this outside the contract; `START_NOT_STICKY` and non-persisted monitoring state remain explicit non-goals.
- **[Trade-off] Started ownership keeps service resources alive independently of UI visibility.** → This is required for hardware PTT; explicit disconnect remains responsible for deterministic teardown.

## Migration Plan

1. Deploy the lifecycle ownership and recoverable-block handling changes without persisted-state or data migration.
2. Confirm automatic startup, background survival, lock survival, recoverable Bluetooth loss, RSM input, and explicit disconnect on the physical Android target.
3. Roll back by reverting the lifecycle changes; no stored data or schema rollback is required.

## Open Questions

None.
