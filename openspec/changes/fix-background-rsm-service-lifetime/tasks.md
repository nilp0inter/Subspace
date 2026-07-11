## 1. Started Foreground-Service Ownership

- [x] 1.1 Update `MainActivity.onStart()` to issue `ContextCompat.startForegroundService()` with `PttForegroundService.ACTION_START_MONITORING` before binding to `PttForegroundService`.
- [x] 1.2 Keep repeated activity starts idempotent: retain the existing serialized reconnect path and do not create a second SPP attempt when monitoring or connection work is already active.
- [x] 1.3 Preserve service-lifetime disconnect semantics: repeated start intents MUST NOT re-enable monitoring after explicit disconnect in the same service instance, and `disconnectSerial()` must still remove foreground ownership and stop reconnect work.
- [x] 1.4 Keep the started foreground service and readiness refresh active when reconnect is blocked by missing permissions, disabled Bluetooth, or temporary target unavailability while monitoring intent remains active; retain terminal teardown for `MonitoringNotRequested`.

## 2. Physical Behavioral Smoke Test

- [x] 2.1 Build and install the debug APK through the repository Nix devshell, force-stop it, then launch it with permissions granted, Bluetooth enabled, and the bonded RSM available without tapping **Connect serial**.
- [x] 2.2 Confirm the automatic path establishes SPP plus started foreground-service ownership and exposes the ongoing `Subspace connected` notification.
- [x] 2.3 Background the app and confirm the activity binding is removed while the service, notification, SPP monitoring, and RSM pressed/released events remain active.
- [x] 2.4 Lock the phone and confirm the service, notification, SPP monitoring, and RSM pressed/released events remain active while the process remains alive.
- [x] 2.5 Use **Disconnect serial** and confirm the foreground notification is removed, the service stops after UI unbinding, and readiness refresh does not reconnect during that service lifetime.
- [x] 2.6 Disable and re-enable Bluetooth while the app is backgrounded, then confirm the foreground service survives the blocked interval and automatically restores RSM readiness without reopening the app.

## 3. Regression Coverage and Cleanup

- [x] 3.1 After the physical smoke test passes, add focused Android lifecycle coverage that fails when automatic monitoring has only bound-service ownership after activity unbinding; do not substitute a pure reconnect-policy test for Android ownership behavior.
- [x] 3.2 Add focused coverage for repeated startup idempotency and explicit-disconnect suppression only where those contracts are exercised through the production lifecycle entry points.
- [x] 3.3 Add focused coverage proving recoverable reconnect block reasons retain foreground ownership and `MonitoringNotRequested` remains terminal.
- [x] 3.4 Run only the added or modified lifecycle tests through the repository Nix devshell and record the exact passing command.
- [x] 3.5 Remove temporary lifecycle diagnostics or test scaffolding and confirm no Bluetooth, audio-route, channel-runtime, persistence, permission, or `START_NOT_STICKY` behavior changed outside this specification.
