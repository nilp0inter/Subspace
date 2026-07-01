## Why

The main dashboard is the operator's field console, but the current layout lets
the Android status bar obstruct the title, gives the RSM Bluetooth link a large
top-level card, and presents the three input modes as text-heavy controls rather
than glanceable operating contexts. The dashboard also needs a hard stable-layout
contract: major sections must keep their relative height and position so touch
targets and visual scanning do not shift when capture or device state changes.

## What Changes

- Make the dashboard respect Android system/status-bar insets so the `SUBSPACE`
  header is never overlapped by the clock or system icons.
- Replace text-heavy input mode segments with fixed-height icon mode tiles:
  RSM/headset, car/steering-wheel, and phone/on-a-pinch.
- Move RSM Bluetooth/readiness status out of the large dashboard connection card
  and into the RSM mode tile as a compact state indicator.
- Change unavailable RSM tile behavior: tapping the unavailable RSM tile opens
  the RSM setup flow; tapping an available RSM tile selects Work mode; long
  pressing the RSM tile always opens the RSM setup flow.
- Preserve stable dashboard geometry: the VU meter and primary dashboard sections
  remain mounted at fixed relative positions, with state expressed through color,
  labels, indicators, and intensity rather than conditional insertion/removal.
- Remove the standalone dashboard connection card from the primary dashboard
  surface, while keeping the existing connection/monitor screens as drill-down
  setup/diagnostic surfaces reached through the RSM tile.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `main-device-dashboard`: Dashboard header safe-area handling, stable layout
  contract, removal of the large standalone connection card from the primary
  dashboard surface, and always-mounted VU meter placement.
- `input-mode`: Mode selector presentation and RSM tile interaction behavior
  change from disabled-unavailable Work control to setup-opening unavailable RSM
  control.
- `vu-meter`: Visibility contract changes from capture-only/no-reserved-space to
  always visible with idle standby rendering.

## Impact

- `app/src/main/java/dev/nilp0inter/subspace/MainActivity.kt`: route wiring may
  need to expose setup/monitor navigation through mode tile actions instead of a
  standalone connection card.
- `app/src/main/java/dev/nilp0inter/subspace/ui/MainDashboardScreen.kt`: header
  safe-area padding, mode selector tile redesign, RSM tile setup gestures, and
  removal of the standalone connection card.
- `app/src/main/java/dev/nilp0inter/subspace/ui/VuMeter.kt`: idle/active rendering
  contract must remain always-mounted and fixed-height.
- `app/src/main/java/dev/nilp0inter/subspace/ui/PttUiActions.kt`: may need a
  dedicated dashboard action for RSM setup/monitor navigation if the current
  `onConnectionClick` callback is folded into mode tile behavior.
- `app/src/test/java/dev/nilp0inter/subspace/ui/MainDashboardVuMeterTest.kt` and
  related UI/pure-state tests must be updated away from the stale hidden-idle
  VU meter expectation.
- No Android SDK, Gradle, service, protocol, Bluetooth transport, audio-routing,
  or persisted-data changes are intended.
