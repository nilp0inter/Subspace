## 1. Retry Policy Tests

- [x] 1.1 Add a small unit-testable reconnect policy or equivalent service seam for monitoring intent, retry eligibility, and duplicate-attempt prevention.
- [x] 1.2 Add JVM tests for unexpected serial loss scheduling reconnect while monitoring intent remains active.
- [x] 1.3 Add JVM tests for explicit disconnect clearing monitoring intent and cancelling pending reconnect work.
- [x] 1.4 Add JVM tests for missing permissions, disabled Bluetooth, and missing bonded target blocking reconnect attempts.
- [x] 1.5 Add JVM tests proving failed reconnect attempts wait before retrying and do not create concurrent SPP attempts.

## 2. Foreground Service Serial Lifecycle

- [x] 2.1 Add service-local monitoring intent state and reconnect job state to `PttForegroundService`.
- [x] 2.2 Set monitoring intent when the user starts serial monitoring through `connectSerial()`.
- [x] 2.3 Clear monitoring intent from `disconnectSerial()` and `onDestroy()` before cancelling serial and reconnect work.
- [x] 2.4 Refactor SPP session startup so manual connect and automatic reconnect share one serialized connection path.
- [x] 2.5 On unexpected SPP event-flow termination, cancel active echo state, keep foreground ownership, refresh readiness, and schedule reconnect instead of stopping the service.
- [x] 2.6 Before each automatic reconnect attempt, require granted permissions, enabled Bluetooth, and a bonded `B02PTT-FF01` target.
- [x] 2.7 Add delayed bounded retry behavior after failed reconnect attempts while monitoring intent and prerequisites remain valid.
- [x] 2.8 Ensure successful automatic reconnect resumes event collection and existing button parser/state-machine processing.

## 3. Manual Flow Preservation

- [x] 3.1 Preserve explicit disconnect behavior: close SPP, cancel retry work, release echo/audio state, remove the foreground notification, stop the service, and refresh readiness.
- [x] 3.2 Preserve existing permission, Bluetooth settings, scan, pair, retry readiness, and manual connect actions on the connection screen.
- [x] 3.3 Reuse existing connection states unless implementation proves a distinct reconnect state is necessary.

## 4. Verification

- [x] 4.1 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 4.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug`.
- [x] 4.3 On physical `B02PTT-FF01`, connect serial, interrupt the serial link, restore the device/radio path, and verify button events resume without tapping `Connect serial` again.
- [x] 4.4 Verify tapping `Disconnect serial` during connected and reconnect-pending states stops retrying and removes the foreground-service notification.
