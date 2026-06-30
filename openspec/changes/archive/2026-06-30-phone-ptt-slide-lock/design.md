## Context

Phone PTT currently lives in `MainDashboardScreen.kt` as `Modifier.phonePttInput`. It waits for a Compose long-press on channel-card text regions, calls `phonePttPressed(channelId)`, then calls `phonePttReleased(channelId)` after `waitForUpOrCancellation()` returns. The dashboard itself is scrollable, and the active channel cards also contain a dedicated configuration `IconButton`.

This makes On-a-pinch PTT too fragile: thumb movement during the long-press threshold can cancel arming, and movement/cancellation after recording starts is treated like release. The change keeps the channel-card long-press entry point but adds a locked state reached by sliding inward from the initial press side.

Gesture sketch:

```text
left-side press                         right-side press

┌─────────────────────────────┐        ┌─────────────────────────────┐
│ ● ───────────────▶ LOCK     │        │ LOCK ◀────────────── ●      │
└─────────────────────────────┘        └─────────────────────────────┘

Idle → Holding/Arming → RecordingHeld ──slide inward──▶ RecordingLocked
                          │                                  │
                          └─release────────────────▶ Finalize│
                                                             └─Stop/max duration/focus loss──▶ Finalize
```

## Goals / Non-Goals

**Goals:**

- Preserve long-press-on-channel-card as the phone PTT start gesture.
- Make active phone PTT tolerant of incidental movement.
- Add side-dependent inward slide-to-lock from the original down position.
- Keep locked recording active after finger release.
- Provide an explicit stop affordance for locked recording.
- Keep config buttons independently tappable and excluded from PTT hit testing.
- Keep existing audio routing, ready-beep timing, capture max duration, and channel dispatch behavior.

**Non-Goals:**

- No tap-to-toggle PTT mode.
- No dedicated global bottom PTT paddle.
- No changes to RSM hardware PTT, Android Auto PTT, SCO routing, capture-service timing, or channel controller semantics.
- No new channel model or persisted preference.
- No hidden background recording after app focus loss.

## Decisions

1. **Use inward horizontal locking from the initial press side.**

   The pointer-down X coordinate is compared with the card's usable PTT width center. Presses on the left lock by crossing a positive horizontal threshold; presses on the right lock by crossing a negative horizontal threshold.

   Alternative considered: one fixed slide direction. Rejected because it forces edge-side thumbs to slide toward the screen edge, which is less reachable and easier to miss one-handed.

2. **Treat movement as state input, not cancellation, once the long-press arms PTT.**

   After the long-press threshold dispatches `phonePttPressed`, horizontal movement only contributes to lock detection. Vertical movement and small horizontal drift do not end the session. Finger release ends the session only while not locked.

   Alternative considered: rely on Compose `awaitLongPressOrCancellation` / `waitForUpOrCancellation` defaults. Rejected because those APIs encode the current failure mode: touch slop and cancellation become release/cancel semantics.

3. **Model the phone gesture as a pure state machine and keep service dispatch boring.**

   The UI should derive states like `Idle`, `Arming`, `RecordingHeld`, and `RecordingLocked` from pointer events and callbacks. Service calls remain a simple edge protocol:

   - held start: `phonePttPressed(channelId)`
   - unlocked release: `phonePttReleased(channelId)`
   - locked stop: `phonePttReleased(channelId)`

   If UI recomposition needs shared state, add a small UI-local state holder rather than teaching `PttForegroundService` about lock gestures.

   Alternative considered: add locked session state to `AppState`. Rejected unless implementation proves cross-component observation is needed; lock is an actuator gesture detail, not capture routing state.

4. **Show lock and stop affordances on the same channel card.**

   During held PTT, the active card should show directional lock instruction based on the initial press side. During locked PTT, the card should show a prominent stop action. This keeps channel identity and recording state colocated.

   Alternative considered: global overlay. Rejected for the first implementation because it adds layout/focus complexity and is unnecessary while only functional cards start phone PTT.

5. **Exclude configuration controls from the PTT gesture region.**

   The main card surface remains tap-to-select and long-press-to-talk. The config icon remains a dedicated control that opens configuration without starting PTT or selecting the channel.

## Risks / Trade-offs

- **Nested clickable/pointer-input conflicts** → Put PTT pointer handling on the main card surface only, not the trailing config button; keep the settings `IconButton` outside the PTT modifier.
- **Scrollable dashboard steals gestures before arming** → Use an explicit long-press arming threshold and consume the gesture after PTT is armed; do not consume ordinary tap/scroll before the long press is recognized.
- **Locked recording could continue unintentionally** → Show a prominent locked state and stop affordance; finalize on max duration and app focus loss.
- **Duplicate release calls during recomposition or pointer cancellation** → Route all gesture exits through a single state-machine transition that emits at most one release for each successful press.
- **Center presses have ambiguous lock direction** → Choose direction from exact initial side of card center. A small center dead zone may be added if testing shows accidental locks, but it is not required by the interaction contract.
- **Tests cannot run full Compose gestures on the JVM-only suite today** → Extract pure gesture-state decision logic for JVM tests; keep rendering assertions lightweight unless Compose UI test infrastructure is added later.
