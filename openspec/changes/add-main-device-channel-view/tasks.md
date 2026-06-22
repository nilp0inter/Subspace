## 1. Dashboard UI

- [x] 1.1 Add a `MainDashboardScreen` Compose screen for the new default app surface.
- [x] 1.2 Display a prominent device connection indicator using `AppState.readyForMonitor` as the connected state.
- [x] 1.3 Display static mock communication channel cards that are visibly non-functional previews.
- [x] 1.4 Keep dashboard styling aligned with the existing Subspace field-terminal visual identity.

## 2. Screen Routing

- [x] 2.1 Add minimal local route state in `MainActivity` for dashboard, legacy connection, and legacy monitor screens.
- [x] 2.2 Make the dashboard the initial route regardless of current readiness.
- [x] 2.3 Wire the dashboard connection indicator to open `ConnectionScreen` when `readyForMonitor` is false.
- [x] 2.4 Wire the dashboard connection indicator to open `MonitorScreen` when `readyForMonitor` is true.
- [x] 2.5 Add back navigation from legacy connection and monitor screens to the dashboard.

## 3. Legacy Behavior Preservation

- [x] 3.1 Preserve existing `PttUiActions` wiring for permissions, Bluetooth settings, scan, pair, connect, retry, disconnect, and echo settings.
- [x] 3.2 Preserve existing service binding and foreground-service start behavior for serial connection.
- [x] 3.3 Verify mock channel taps do not start audio capture, command execution, network communication, persistence, or Bluetooth actions.

## 4. Verification

- [x] 4.1 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 4.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug`.
- [x] 4.3 Manually verify dashboard launch, disconnected indicator routing, connected indicator routing, and back-to-dashboard behavior on device or emulator.
