## Context

The Android app currently uses `PttForegroundService` as the owner of the target device link. `connectSerial()` creates an `SppClient`, starts foreground service ownership, collects parsed button events, and stops the foreground service when the SPP event flow ends. `disconnectSerial()` is the explicit user-controlled shutdown path.

This means an unplanned SPP loss and an intentional disconnect converge on nearly the same terminal behavior: serial monitoring stops, the notification is removed, and the user must return to the phone to connect again. That conflicts with the hardware-first goal because the PTT controls can disappear after a transient device/radio interruption.

Constraints:

- Support remains limited to the bonded `B02PTT-FF01` target device.
- Android public Bluetooth APIs only; no hidden reconnection APIs.
- Bluetooth, permission, bonding, SPP, and SCO readiness checks remain separate.
- No phone speaker or phone microphone fallback.

## Goals / Non-Goals

**Goals:**

- Preserve the user's active monitoring intent after an unexpected serial disconnect.
- Automatically retry the SPP connection to the bonded target while prerequisites remain valid.
- Keep foreground service ownership active while reconnecting so background hardware operation can recover without opening the app.
- Prevent duplicate concurrent SPP connection attempts.
- Stop retrying immediately on explicit user disconnect, service destruction, missing permissions, disabled Bluetooth, or loss of a bonded target.
- Keep the existing manual connection controls as setup and fallback actions.

**Non-Goals:**

- No automatic scan or pairing flow.
- No automatic Bluetooth enablement or Android settings mutation.
- No persistent auto-connect preference across process death or device reboot.
- No changes to button token parsing, hardware mode behavior, echo behavior, or SCO route selection.
- No support for devices other than `B02PTT-FF01`.
- No new external dependencies.

## Decisions

### PttForegroundService owns reconnect policy

Keep `SppClient` focused on one SPP socket session and keep retry policy in `PttForegroundService`.

Rationale: the service already owns permissions, Bluetooth readiness, bonded target lookup, foreground lifecycle, user actions, echo cancellation, and app state updates. Reconnect policy needs all of that context to distinguish explicit disconnect from unplanned loss.

Alternative considered: make `SppClient.events()` retry internally. That hides service-level prerequisites from the client and makes explicit disconnect, foreground ownership, and app-state updates harder to reason about.

### Track monitoring intent explicitly

Add service-local state for whether serial monitoring is user-requested. Set it when `connectSerial()` starts a monitoring session. Clear it in `disconnectSerial()` and service destruction. When the event flow ends, retry only if this intent is still active and the end was not caused by explicit disconnect.

Rationale: current code has no durable distinction between a manual disconnect and an unexpected socket loss. A small intent flag is enough for the current single-device app and avoids persistence or a broader domain model.

Alternative considered: infer intent from `serialJob?.isActive` or `SppState`. Those signals are ambiguous during cancellation, failure, and teardown.

### Retry with one serialized coroutine and bounded delay

Use one reconnect job owned by the service. Before each attempt, refresh readiness, confirm permissions are granted, Bluetooth is enabled, and the target is bonded. If an attempt fails while intent remains active, schedule the next attempt after a delay with an upper bound.

Rationale: serialization prevents duplicate sockets and backoff prevents a tight failure loop when the device is powered off or out of range.

Alternative considered: immediate recursive calls to `connectSerial()`. That is simpler but risks fast retry loops and makes cancellation/race behavior less clear.

### Keep foreground ownership while reconnecting

Do not call `stopForegroundIfNeeded()` or `stopSelf()` for an unexpected serial loss while monitoring intent remains active. Keep the notification visible until reconnection succeeds, the user disconnects, or prerequisites fail.

Rationale: background recovery requires a living service owner. Stopping the foreground service after loss recreates the current failure mode.

Alternative considered: stop foreground service after each disconnect and restart it for each retry. That causes notification churn and is less reliable under Android background execution limits.

### Reuse existing connection states unless implementation proves insufficient

Use existing `SppState.Connecting`, `Connected`, `Failed`, and `Disconnected` to represent retry attempts and outcomes. During an automatic attempt, the serial channel can be shown as connecting. After a failed attempt, the existing failure state and error text can remain until the next attempt starts.

Rationale: this keeps the data model minimal and avoids adding UI states that are not required by the capability. The user-visible requirement is automatic recovery, not a new status taxonomy.

Alternative considered: add `SppState.Reconnecting`. That may improve copy but requires broader UI/test updates without changing behavior.

## Risks / Trade-offs

- Indefinite retry can consume battery when the device is powered off -> Use delayed bounded retry attempts and stop on explicit disconnect or invalid prerequisites.
- Foreground notification may remain visible while reconnecting -> This is intentional because the user still requested monitoring; manual disconnect remains the escape hatch.
- Bluetooth or permission state can change during a retry -> Refresh readiness before attempts and stop retrying if prerequisites are invalid.
- Race between manual disconnect and an in-flight retry -> Use a single reconnect job and clear monitoring intent before cancelling active serial work.
- Process death still loses reconnect intent -> Accepted for this change because persistent auto-connect is explicitly out of scope.

## Migration Plan

1. Add unit-testable retry policy or service-local state transitions before changing service behavior.
2. Update `PttForegroundService` serial lifecycle to keep monitoring intent across unexpected event-flow termination.
3. Add serialized delayed retry behavior guarded by permissions, Bluetooth enabled state, and bonded target availability.
4. Preserve explicit disconnect behavior: cancel active serial work, cancel retry work, clear intent, release echo/audio, remove notification, and stop the service.
5. Run JVM tests and assemble the debug APK through the Nix devshell.
6. Manually test on `B02PTT-FF01` by connecting serial, interrupting device/radio availability, restoring it, and confirming hardware button events resume without pressing `Connect serial` again.

Rollback is straightforward: remove the retry job/intent state and restore the previous event-flow terminal path that cancels echo, removes foreground ownership, stops the service, and refreshes readiness.

## Open Questions

- Exact retry delay constants may need tuning after physical-device testing; the implementation should choose conservative defaults and keep them easy to adjust.
