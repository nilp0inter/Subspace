## Context

Keyboard runtime readiness currently mirrors `bridgeConnectedFlow`, and `decidePttDispatch` converts every not-ready active channel into an immediate `ErrorBeep` decision. That prevents `KeyboardRuntime.prepareInput()` from participating when the Sleepwalker bridge is disconnected. The BLE connection API starts an asynchronous scan/GATT sequence but exposes no bounded operation that a PTT setup can await.

PTT setup is centrally owned by `PttAudioSessionManager`. It reserves one session, resolves and gates the input-mode route, asks the selected runtime to prepare an immutable channel target, and only then starts capture and plays the ready beep. Setup failure already plays the problem beep on the session route and releases the route exactly once.

## Goals / Non-Goals

**Goals:**

- Let a configured, enabled Keyboard runtime recover a disconnected Sleepwalker bridge during PTT setup.
- Wait for a definitive `Connected`, failure, cancellation, or bounded timeout result before channel commitment and ready-beep playback.
- Reuse one BLE attempt when the bridge is already scanning or connecting.
- Preserve centralized PTT ownership, route-correct problem feedback, immutable channel targeting, and exactly-once cleanup.
- Keep passive Keyboard readiness truthful: disconnected remains Standby/not ready outside an active recovery attempt.

**Non-Goals:**

- Background or periodic Sleepwalker auto-reconnect.
- Changes to Sleepwalker discovery filters, GATT setup, MTU, framing, ACK handling, or HID safety ordering.
- Changes to RSM SPP reconnect, HFP/SCO ownership, input-mode routing, or non-Keyboard readiness behavior.
- Capturing speech before the Sleepwalker connection has succeeded.
- Persisting connection intent or changing channel configuration/UI controls.

## Decisions

### 1. Distinguish recoverable Keyboard unavailability at dispatch

`decidePttDispatch` will continue returning `ErrorBeep` for not-ready non-Keyboard runtimes. For an enabled/configured Keyboard instance whose live bridge dependency is disconnected, it will return `Dispatch`, allowing the centralized session manager to reserve the PTT and run runtime preparation.

The runtime remains the final authority: invalid configuration, disabled state, missing controller, or failed connection still returns `Refused`/`Unavailable`. This avoids redefining `isReady` to mean “possibly recoverable,” which would incorrectly show a disconnected channel as Ready on the dashboard and Android Auto.

Alternative considered: mark disconnected Keyboard instances ready. Rejected because it makes passive readiness false and weakens the existing live-dependency contract.

### 2. Make channel input preparation suspendable

`ChannelRouter.prepareInput` and `ChannelRuntime.prepareInput` will become suspend functions. Existing Journal and Debug runtimes retain immediate checks; Keyboard preparation may await BLE recovery. `PttAudioSessionManager.runSetup` already executes in a setup coroutine, so the wait remains within the single active session rather than creating a second dispatcher-owned state machine.

The existing route-first setup order remains: resolve/gate the input-mode route, then prepare the channel, then start capture. This keeps connection-failure feedback on the route selected for the active input mode and reuses existing failure and exactly-once release handling. The connection wait is bounded to prevent holding the route indefinitely.

Alternative considered: perform a Keyboard-specific asynchronous retry in `PttDispatcher` and redispatch PTT after connection. Rejected because it would duplicate session ownership, race PTT release/cancellation, and bypass the centralized setup terminal-claim rules.

Alternative considered: connect before route acquisition. Rejected for this change because a failed connection would require a second route-only session to satisfy route-correct problem feedback. The bounded wait limits the cost of retaining the route during recovery.

### 3. Add one coalesced, bounded BLE connection operation

`SleepwalkerBleConnection` will expose a suspendable ensure-connected operation with a typed result such as `Connected`, `Failed(reason)`, or `TimedOut`. It will:

- return immediately when already `Connected`;
- start scanning when `Disconnected` and connection prerequisites permit;
- join the current state transition when already `Scanning` or `Connecting`;
- complete only after the existing GATT initialization reaches `Connected`;
- fail on scan/GATT setup failure or terminal `Disconnected` after an attempt starts;
- time out after one shared constant and stop/close the stale scan or GATT attempt;
- serialize callers so one physical scan/GATT attempt is active.

`KeyboardRuntimeFactory` will receive this operation as an injected suspend callback. This keeps Android adapter/context ownership in the service/BLE layer and keeps the runtime testable without Android Bluetooth objects.

Alternative considered: poll `connectionState.value` in the runtime. Rejected because polling cannot reliably identify attempt ownership or terminal failure and would duplicate BLE lifecycle cleanup.

### 4. Preserve PTT terminal semantics while connecting

The active audio session remains pending and uncommitted while Keyboard preparation awaits the bridge. No ready beep, capture start, or channel-visible `Started` event occurs during this interval.

- Success while PTT is still held returns an accepted immutable target and continues through existing capture preflight and ready beep.
- Failure or timeout returns refusal; the session manager plays the problem beep, releases the route once, and clears the session.
- Release or cancellation while waiting claims the existing terminal state. A later connection result cannot start capture or play the ready beep. The shared BLE attempt may finish and leave the bridge connected for the next PTT, but the released session remains terminal.
- A repeated press while the session is pending cannot create another session or BLE attempt.

### 5. Keep observability behavioral and non-sensitive

Use the existing `SubspaceRoute` tag to record recovery start/join, terminal result, timeout, and cancellation/session-obsolescence. Logs will contain state and reason only; they will not add Bluetooth addresses or payload data.

## Risks / Trade-offs

- [BLE recovery holds the selected audio route until completion] → Use a bounded connection timeout and existing exactly-once route release; do not start capture or the ready beep during the wait.
- [A release can race a successful BLE callback] → Re-check active-session identity, PTT-down state, and terminal claim after suspend points before accepting the target or starting capture.
- [Multiple UI/PTT callers can initiate connection] → Coalesce at `SleepwalkerBleConnection`, not at individual callers.
- [A scan can otherwise remain indefinitely in `Scanning`] → Make timeout cleanup part of the ensure-connected operation and return to `Disconnected`.
- [Broadening `prepareInput` to suspend touches all runtimes] → Keep non-Keyboard implementations synchronous in behavior and cover registry lease accounting against refusal/cancellation.

## Migration Plan

1. Add the coalesced suspendable BLE connection operation without changing existing manual connect/disconnect controls.
2. Make runtime/router preparation suspendable and inject the BLE recovery callback into Keyboard runtimes.
3. Allow only recoverable Keyboard not-ready dispatches to enter centralized setup.
4. Add focused unit tests for connection success, failure, timeout, coalescing, release/cancellation races, and unchanged non-Keyboard behavior.
5. Roll back by reverting the dispatch exception and suspendable preparation path; no persisted data or protocol migration is involved.

## Open Questions

None. The implementation will use one named constant for the bounded attempt so the timeout can be tuned from device evidence without changing the behavioral contract.
