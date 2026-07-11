## Why

Automatic RSM monitoring now starts from a service created only by the activity binding. When the app loses visibility or the phone locks, `MainActivity.onStop()` removes the final binding, Android destroys the service, and RSM button handling stops despite foreground promotion.

## What Changes

- Establish automatic RSM monitoring under a genuinely started foreground-service lifetime rather than a bound-only activity lifetime.
- Keep the SPP connection, reconnect scheduler, foreground notification, and RSM button handling alive when the app is backgrounded or the phone is locked.
- Preserve explicit **Disconnect serial** as the action that ends monitoring, removes the foreground notification, and stops the service.
- Add regression coverage for automatic startup followed by activity backgrounding and screen lock, without requiring the user to press **Connect serial** first.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `device-auto-reconnect`: Require eligible automatic monitoring to be owned by a started foreground service and to survive loss of the activity binding, including app backgrounding and phone lock, until explicit disconnect or Android terminates the process.

## Impact

- Android lifecycle ownership in `MainActivity` and `PttForegroundService`.
- Automatic SPP connection and reconnect startup sequencing.
- Foreground-service notification lifetime and explicit-disconnect teardown.
- Focused lifecycle regression tests plus physical-device acceptance checks.
- No external API, persisted-data, dependency, audio-route, channel-runtime, or permission changes.
