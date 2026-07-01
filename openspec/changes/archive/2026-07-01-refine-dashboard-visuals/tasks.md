## 1. Dashboard Routing And Stable Layout

- [x] 1.1 Update dashboard setup routing so the Work/RSM tile can open the existing disconnected connection view or connected monitor view without the standalone connection card.
- [x] 1.2 Apply Android safe-area/status-bar padding at the dashboard content boundary so the `SUBSPACE` header cannot overlap system clock or icons.
- [x] 1.3 Remove the standalone dashboard connection card from the primary dashboard layout while preserving legacy connection and monitor screens as drill-down surfaces.
- [x] 1.4 Keep dashboard primary section order and reserved heights stable across idle, capture, connected, disconnected, available, and unavailable states.

## 2. Mode Tile Visual Redesign

- [x] 2.1 Add a matched local icon family for RSM/headset, car/steering-wheel or car-control, and phone mode tiles using line-art styling consistent with `VISUAL_IDENTITY.md`.
- [x] 2.2 Replace the current text-heavy mode segments with fixed-height icon-first tiles that always render Work, OnTheRoad, and OnAPinch.
- [x] 2.3 Render active, available, and unavailable mode states through tile border, icon intensity, label/status text, and compact indicators without changing tile size.
- [x] 2.4 Move RSM readiness status into the Work/RSM tile using the existing `connection.readyForMonitor` / Work availability gate.

## 3. RSM Tile Interaction Semantics

- [x] 3.1 Make tapping an available Work/RSM tile select `InputMode.Work`.
- [x] 3.2 Make tapping an unavailable Work/RSM tile open the RSM setup flow without transitioning to Work mode.
- [x] 3.3 Make long-pressing the Work/RSM tile always open the RSM setup or monitor flow without changing input mode.
- [x] 3.4 Keep unavailable non-Work mode tiles non-selecting and non-transitioning while still visible.

## 4. VU Meter Contract And Tests

- [x] 4.1 Keep `VuMeter` mounted in the dashboard for both idle and capture states, with idle rendering as a dim standby track.
- [x] 4.2 Update stale dashboard/VU meter JVM tests so idle state expects a present standby meter with stable layout, not hidden/no-space behavior.
- [x] 4.3 Add or update pure interaction tests for Work/RSM tile tap and long-press behavior if the behavior can be extracted without Compose UI infrastructure.

## 5. Verification

- [x] 5.1 Run `nix develop --no-write-lock-file -c gradle test` and fix regressions caused by this change.
- [x] 5.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug` and fix compile or resource errors.
- [x] 5.3 Perform dashboard visual QA on a device or emulator: title clear of Android status bar, three fixed-height icon tiles visible, RSM setup reachable by unavailable tap and long-press, VU meter present while idle and active, and no major section shifts across state changes.
