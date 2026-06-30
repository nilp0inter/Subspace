## Why

Phone-originated PTT in On-a-pinch mode currently depends on a movement-sensitive channel-card long press. Slight thumb drift while arming or recording can cancel the gesture, which makes the phone-only fallback unreliable in the exact one-handed conditions it is meant to support.

## What Changes

- Preserve the existing channel-card long-press as the phone PTT entry point.
- Add an inward horizontal slide-to-lock gesture during a held phone PTT session:
  - If the initial press starts on the left side of the card, sliding right locks.
  - If the initial press starts on the right side of the card, sliding left locks.
- Ignore incidental movement after PTT is armed; movement SHALL NOT cancel an active phone PTT session.
- Once locked, finger release SHALL NOT end the recording. The user ends it with an explicit stop action or the existing maximum-duration cutoff.
- Exclude each card's dedicated configuration button from phone PTT/lock gestures.
- Add visible held, lock-available, locked, and stop affordance states to the channel card.
- No breaking changes to channel routing, audio capture timing, or RSM/Android Auto PTT behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `phone-channel-card-ptt`: Adds robust slide-to-lock semantics, movement-tolerant holding, and explicit stop behavior for locked phone-originated PTT sessions.
- `main-device-dashboard`: Updates functional channel card interaction and visual feedback requirements for phone PTT locking while preserving tap-to-select and config-button behavior.

## Impact

- Affected UI: `app/src/main/java/dev/nilp0inter/subspace/ui/MainDashboardScreen.kt` channel card pointer handling and visual state.
- Affected service boundary: `PttUiActions.phonePttPressed` / `phonePttReleased` may need an additional phone stop/locked-session path or equivalent UI state coordination.
- Affected tests: dashboard/gesture pure-state tests should cover side-dependent lock direction, movement tolerance, locked release behavior, explicit stop, and config-button exclusion.
- No new Android permissions, external dependencies, audio route changes, or protocol changes.
