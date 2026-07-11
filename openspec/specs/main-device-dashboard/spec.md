## Purpose

TBD. Defines the main device dashboard home surface and its relationship to the legacy connection and monitor views.

## Requirements

### Requirement: Dashboard is the default app view
The system SHALL show the main dashboard as the default interactive app view after core bootstrap readiness completes. Before that readiness result, the system SHALL show passive loading, actionable setup, or bootstrap recovery according to authoritative bootstrap state. RSM and other optional external readiness SHALL NOT prevent dashboard entry after core readiness.

#### Scenario: App starts while core bootstrap is incomplete
- **WHEN** the app launches and core bootstrap has not completed successfully
- **THEN** the system shows loading, setup, or recovery for the current bootstrap state
- **AND** the system does not render the dashboard from default placeholder state

#### Scenario: App starts while device is not ready
- **WHEN** core bootstrap reaches ready and the RSM device readiness gate is false
- **THEN** the system shows the main dashboard
- **AND** the system does not automatically show the legacy connection screen
- **AND** the Work/RSM tile reflects the unavailable or setup state

#### Scenario: App starts while device is ready
- **WHEN** core bootstrap reaches ready and the RSM device readiness gate is true
- **THEN** the system shows the main dashboard
- **AND** the system does not automatically show the legacy monitor screen

#### Scenario: Optional peripheral remains unavailable
- **WHEN** core bootstrap reaches ready while Keyboard BLE, Android Auto, or another optional external capability is unavailable
- **THEN** the system shows the main dashboard
- **AND** the optional capability continues to communicate its own readiness inside the dashboard or drill-down surface

#### Scenario: Bootstrap completes after setup
- **WHEN** a setup action returns to loading and all core readiness conditions subsequently complete
- **THEN** the system shows the main dashboard automatically
- **AND** no separate dashboard-entry acknowledgement is requested

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

### Requirement: Legacy views are secondary screens
The system SHALL keep the dashboard as the home surface and treat the legacy connection and monitor views as drill-down screens opened from the dashboard.

#### Scenario: Returning from a legacy view
- **WHEN** the user is viewing a legacy connection or monitor screen opened from the dashboard and requests back navigation
- **THEN** the system returns to the dashboard instead of switching directly between legacy screens

### Requirement: Dashboard shows channels
The system SHALL render one functional card per channel instance in the authoritative catalogue order. Each card SHALL use the instance's stable ID, display name, readiness, execution status, active state, and kind-specific summary. Functional cards SHALL remain mutually exclusive activation zones and phone-side PTT zones. Dedicated configuration and catalogue-management controls SHALL remain outside the phone PTT gesture surface.

The channel panel SHALL provide access to add supported built-in instances, rename instances, reorder instances, and remove instances subject to catalogue invariants. Kind-specific configuration surfaces SHALL be addressed by instance ID rather than fixed singleton routes. The dashboard SHALL NOT render nonfunctional mock channel cards as catalogue entries.

#### Scenario: Ordered catalogue renders
- **WHEN** the dashboard receives an ordered runtime snapshot containing channel instances
- **THEN** it SHALL render exactly one functional card per instance
- **AND** card order SHALL match the catalogue order

#### Scenario: Channel selected for activation
- **WHEN** the user taps the main surface area of a functional channel card
- **THEN** the system SHALL set that card's stable instance ID as the single active channel

#### Scenario: Channel long-pressed for PTT
- **WHEN** the user long-presses the main surface area of a functional channel card
- **THEN** the system SHALL set that card's instance ID as active
- **AND** start a phone-originated PTT session for that instance
- **AND** show held-recording feedback on that card

#### Scenario: Held phone PTT shows lock direction
- **WHEN** a phone-originated PTT session is active from a functional channel card and is not locked
- **THEN** the dashboard SHALL show a lock instruction on that card
- **AND** the instruction SHALL point inward from the initial press side

#### Scenario: Locked phone PTT shows stop affordance
- **WHEN** a phone-originated PTT session from a functional channel card has been slide-locked
- **THEN** the dashboard SHALL show that the PTT session is locked on that card
- **AND** it SHALL show an explicit stop affordance

#### Scenario: Unready instance is visible
- **WHEN** a catalogue instance lacks valid configuration or a required live dependency
- **THEN** its card SHALL remain visible at its catalogue position
- **AND** it SHALL display its not-ready state and configuration access

#### Scenario: Channel configured but inactive
- **WHEN** a ready instance is not the active channel
- **THEN** its card SHALL display a visually inactive Ready or Standby state

#### Scenario: Channel card opens configuration
- **WHEN** the user activates the dedicated configuration control on a functional channel card
- **THEN** the system SHALL open the configuration surface for that instance ID and kind
- **AND** it SHALL preserve the active selection
- **AND** it SHALL NOT start, lock, stop, or release phone PTT

#### Scenario: Add channel instance
- **WHEN** the user chooses a supported built-in kind and submits valid initial configuration
- **THEN** the dashboard SHALL request creation of a new catalogue instance
- **AND** the new card SHALL appear at the resulting catalogue position

#### Scenario: Every supported kind remains reachable
- **WHEN** the catalogue-management form is rendered at the minimum supported phone width
- **THEN** the creation control for every supported production kind SHALL remain visible and actionable
- **AND** the presence or absence of an existing instance of that kind SHALL NOT hide or disable creation

#### Scenario: Reorder channel instance
- **WHEN** the user moves an instance through the catalogue-management surface
- **THEN** the dashboard SHALL render the committed order
- **AND** the active instance SHALL remain active

#### Scenario: Remove channel instance
- **WHEN** the user confirms removal of a removable instance
- **THEN** the dashboard SHALL remove its card after the catalogue mutation commits
- **AND** it SHALL render any active-selection repair from the same committed snapshot

#### Scenario: Final channel cannot be removed
- **WHEN** the catalogue contains one instance
- **THEN** the dashboard SHALL prevent or reject removal of that final instance

#### Scenario: No mock catalogue entries
- **WHEN** the dashboard renders the channel panel
- **THEN** every channel card SHALL correspond to a functional catalogue instance
- **AND** the dashboard SHALL NOT render the Command Uplink preview as a channel card

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
