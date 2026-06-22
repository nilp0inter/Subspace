## Why

The Bluetooth PTT proof of concept has validated communication with `B02PTT-FF01`, so the app can begin moving toward the final operator-facing interface. The next step is to replace the proof-of-concept auto-switching root with a glanceable main view that shows device connection state and mock communication channels while retaining the validated connection and monitor tools.

## What Changes

- Add a main dashboard as the app's default/root view.
- Show a first-glance device connection indicator on the main dashboard.
- Make the connection indicator clickable.
- Route the connection indicator to the legacy connection view when the device is not ready/connected.
- Route the connection indicator to the legacy monitor view when the device is ready/connected.
- Show a mock list of communication channels on the main dashboard.
- Preserve existing Bluetooth SPP, SCO readiness, button monitor, and echo-test behavior in the legacy views.
- Replace proof-of-concept automatic root navigation with explicit operator navigation from the main dashboard.

## Capabilities

### New Capabilities
- `main-device-dashboard`: Defines the operator-facing main dashboard, its connection indicator behavior, mock channel list, and access to legacy device validation views.

### Modified Capabilities

## Impact

- Affects Compose UI routing in `MainActivity`.
- Adds a new Compose main dashboard screen and likely small supporting UI state/types for mock channels.
- Reuses existing `ConnectionScreen`, `MonitorScreen`, `AppState.readyForMonitor`, and `PttUiActions` behavior.
- Does not change Bluetooth protocol handling, SCO routing, foreground service ownership, parser behavior, or echo logic.
