## 1. Service Refresh Loop

- [x] 1.1 Add a `readinessRefreshJob` and fixed refresh interval to `PttForegroundService`
- [x] 1.2 Add an idempotent refresh-loop helper that starts periodic work only when the service is active and `AppState.readyForMonitor` is false
- [x] 1.3 Make the refresh-loop body call `refreshReadiness()` and no other setup or connection action
- [x] 1.4 Stop the refresh loop automatically when `AppState.readyForMonitor` becomes true

## 2. Lifecycle Integration

- [x] 2.1 Invoke refresh-loop lifecycle updates after readiness or connection state changes that can affect `readyForMonitor`
- [x] 2.2 Cancel the refresh loop from `disconnectSerial()` so explicit disconnect does not leave periodic work active
- [x] 2.3 Cancel the refresh loop from `onDestroy()` so service teardown releases periodic work
- [x] 2.4 Preserve the existing `Retry readiness checks` action as an immediate manual `refreshReadiness()` call

## 3. Tests And Verification

- [x] 3.1 Add unit coverage for the refresh-loop start/stop decision path, using a small testable helper if needed
- [x] 3.2 Verify periodic refresh does not call scan, pair, Bluetooth settings, `connectSerial()`, or `startSerialSession()`
- [x] 3.3 Run the relevant Gradle unit tests through the repository Nix devshell
- [x] 3.4 Run or document the manual device-flow check: connect RSM, let car Bluetooth disrupt readiness, wait for automatic readiness recovery without tapping `Retry readiness checks`

Manual device-flow note: not executed in this session because it requires the physical RSM and car Bluetooth environment. Acceptance check is to connect RSM serial, let car Bluetooth disrupt readiness, avoid tapping `Retry readiness checks`, and confirm the dashboard/device-link readiness recovers after the periodic refresh loop observes restored readiness.
