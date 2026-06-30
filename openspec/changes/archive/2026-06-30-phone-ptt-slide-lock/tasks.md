## 1. Gesture State Model

- [x] 1.1 Add a pure phone PTT gesture state model for idle, armed/held, locked, finalized states
- [x] 1.2 Implement side-dependent inward lock detection from initial card press position
- [x] 1.3 Ensure movement without lock threshold crossing does not emit release or cancel after PTT arms
- [x] 1.4 Ensure each successful phone PTT press emits exactly one release on unlocked release, locked stop, max duration handoff, or focus-loss cleanup

## 2. Dashboard Integration

- [x] 2.1 Move phone PTT pointer handling to the functional channel card main surface while excluding config controls
- [x] 2.2 Preserve tap-to-select behavior for functional channel card main surfaces
- [x] 2.3 Wire long-press to start phone PTT and select the pressed channel
- [x] 2.4 Wire inward slide-to-lock so finger release does not release locked phone PTT
- [x] 2.5 Add explicit stop affordance for locked phone PTT that releases the active phone session
- [x] 2.6 End any locked phone PTT session when foreground interaction is lost or the gesture is system-cancelled

## 3. Dashboard Feedback

- [x] 3.1 Show held-recording feedback on the source channel card during unlocked phone PTT
- [x] 3.2 Show rightward lock instruction for left-side initial presses
- [x] 3.3 Show leftward lock instruction for right-side initial presses
- [x] 3.4 Show locked-recording state and stop affordance after lock threshold crossing
- [x] 3.5 Keep readiness, active, standby, and config visuals intact outside active phone PTT states

## 4. Verification

- [x] 4.1 Add JVM tests for lock direction, lock threshold crossing, and incidental movement tolerance
- [x] 4.2 Add JVM tests for unlocked release, locked release persistence, explicit stop, and duplicate-release prevention
- [x] 4.3 Add JVM tests that config-button interactions do not start, lock, stop, or release phone PTT
- [x] 4.4 Run the affected JVM test suite through the repository Nix devshell
