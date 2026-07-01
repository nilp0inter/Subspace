## MODIFIED Requirements

### Requirement: Dashboard shows device connection state
The system SHALL show the RSM device connection state on the dashboard as a
compact status indicator inside the Work/RSM input mode tile, using the existing
full readiness gate as the connected state. The dashboard SHALL NOT render a
standalone top-level device connection card as part of the primary dashboard
surface.

#### Scenario: Device is ready
- **WHEN** permissions are granted, Bluetooth is enabled, the target device is bonded, SPP is connected, and Bluetooth SCO communication audio is available
- **THEN** the Work/RSM tile shows the RSM as ready or connected
- **AND** the dashboard does not render a separate top-level device connection card

#### Scenario: Device is not ready
- **WHEN** any readiness requirement is missing
- **THEN** the Work/RSM tile shows the RSM as unavailable or requiring setup
- **AND** the dashboard does not render a separate top-level device connection card

### Requirement: Connection indicator opens legacy connection view when disconnected
The system SHALL open the legacy connection view when the user activates setup
from the Work/RSM mode tile while the device is not ready. The legacy connection
view SHALL keep its existing permissions, Bluetooth, scan, pair, connect, retry,
and settings actions.

#### Scenario: Disconnected RSM tile setup activated
- **WHEN** the dashboard is visible, the device readiness gate is false, and the user taps the Work/RSM tile
- **THEN** the system shows the legacy connection view
- **AND** the legacy connection view keeps its existing permissions, Bluetooth, scan, pair, connect, retry, and settings actions

#### Scenario: Disconnected RSM tile long-pressed
- **WHEN** the dashboard is visible, the device readiness gate is false, and the user long-presses the Work/RSM tile
- **THEN** the system shows the legacy connection view
- **AND** the legacy connection view keeps its existing permissions, Bluetooth, scan, pair, connect, retry, and settings actions

### Requirement: Connection indicator opens legacy monitor view when connected
The system SHALL open the legacy monitor view when the user activates setup from
the Work/RSM mode tile while the device is ready. The legacy monitor view SHALL
keep its existing button-state, hardware-mode, echo-control, audio-status, and
disconnect actions.

#### Scenario: Connected RSM tile long-pressed
- **WHEN** the dashboard is visible, the device readiness gate is true, and the user long-presses the Work/RSM tile
- **THEN** the system shows the legacy monitor view
- **AND** the legacy monitor view keeps its existing button-state, hardware-mode, echo-control, audio-status, and disconnect actions

### Requirement: Dashboard renders the VU meter during capture

The main dashboard SHALL always render the VU meter (see `vu-meter` capability)
at a stable dashboard position driven by the capture service's `level` and
`isCapturing` signals. The dashboard SHALL obtain these signals from
`PttForegroundService` via the existing binder, alongside the existing `appState`
collection. The VU meter SHALL remain mounted and reserve its normal layout space
while idle so the relative height and position of dashboard components do not
shift when capture starts or stops.

#### Scenario: Dashboard shows the meter while capturing
- **WHEN** the dashboard is visible and `isCapturing` is true
- **THEN** the dashboard renders the VU meter reflecting the live `level`
- **AND** the VU meter occupies the same dashboard position and height as it does while idle

#### Scenario: Dashboard shows standby meter while idle
- **WHEN** the dashboard is visible and `isCapturing` is false
- **THEN** the dashboard renders the VU meter in an idle standby state
- **AND** the VU meter reserves its normal dashboard space
- **AND** the dashboard does not shift channel cards or mode controls because capture is idle

#### Scenario: Meter reflects any talk mode
- **WHEN** the dashboard is visible and a capture session is active on any channel (journal, STT, or a future channel)
- **THEN** the dashboard renders the VU meter driven by the unified capture signal, with no per-mode wiring

## ADDED Requirements

### Requirement: Dashboard respects system status area
The dashboard SHALL position the terminal header inside the safe drawing area so
Android status-bar content such as the clock and system icons does not overlap
the `SUBSPACE` title.

#### Scenario: Dashboard starts under Android status bar
- **WHEN** the dashboard is displayed on a device with a visible Android status bar
- **THEN** the `SUBSPACE` title is fully below the status-bar content
- **AND** the title remains readable without being obscured by system icons or the clock

### Requirement: Dashboard primary sections preserve relative height
The dashboard SHALL keep its primary sections mounted at stable relative
positions. State changes SHALL be communicated through labels, colors,
indicators, intensity, or animation inside an existing section rather than by
conditionally inserting, removing, expanding, or collapsing primary sections.

#### Scenario: Capture state changes
- **WHEN** capture starts or stops
- **THEN** the dashboard keeps the header, mode selector, VU meter, and channel panel in the same relative order and reserved heights

#### Scenario: Device availability changes
- **WHEN** RSM, car, or phone mode availability changes
- **THEN** the dashboard keeps all three mode tiles visible at fixed relative size
- **AND** availability is expressed inside the existing tiles
