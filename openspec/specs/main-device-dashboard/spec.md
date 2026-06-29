## Purpose

TBD. Defines the main device dashboard home surface and its relationship to the legacy connection and monitor views.

## Requirements

### Requirement: Dashboard is the default app view
The system SHALL show a main dashboard as the default app view after launch instead of automatically replacing the root view with the legacy connection or monitor screen.

#### Scenario: App starts while device is not ready
- **WHEN** the app launches and the device readiness gate is false
- **THEN** the system shows the main dashboard
- **AND** the system does not automatically show the legacy connection screen

#### Scenario: App starts while device is ready
- **WHEN** the app launches and the device readiness gate is true
- **THEN** the system shows the main dashboard
- **AND** the system does not automatically show the legacy monitor screen

### Requirement: Dashboard shows device connection state
The system SHALL show a glanceable device connection indicator on the dashboard using the existing full readiness gate as the connected state.

#### Scenario: Device is ready
- **WHEN** permissions are granted, Bluetooth is enabled, the target device is bonded, SPP is connected, and Bluetooth SCO communication audio is available
- **THEN** the dashboard connection indicator shows the device as connected

#### Scenario: Device is not ready
- **WHEN** any readiness requirement is missing
- **THEN** the dashboard connection indicator shows the device as not connected

### Requirement: Connection indicator opens legacy connection view when disconnected
The system SHALL open the legacy connection view when the user clicks the dashboard connection indicator while the device is not ready.

#### Scenario: Disconnected indicator clicked
- **WHEN** the dashboard is visible, the device readiness gate is false, and the user clicks the connection indicator
- **THEN** the system shows the legacy connection view
- **AND** the legacy connection view keeps its existing permissions, Bluetooth, scan, pair, connect, retry, and settings actions

### Requirement: Connection indicator opens legacy monitor view when connected
The system SHALL open the legacy monitor view when the user clicks the dashboard connection indicator while the device is ready.

#### Scenario: Connected indicator clicked
- **WHEN** the dashboard is visible, the device readiness gate is true, and the user clicks the connection indicator
- **THEN** the system shows the legacy monitor view
- **AND** the legacy monitor view keeps its existing button-state, hardware-mode, echo-control, audio-status, and disconnect actions

### Requirement: Legacy views are secondary screens
The system SHALL keep the dashboard as the home surface and treat the legacy connection and monitor views as drill-down screens opened from the dashboard.

#### Scenario: Returning from a legacy view
- **WHEN** the user is viewing a legacy connection or monitor screen opened from the dashboard and requests back navigation
- **THEN** the system returns to the dashboard instead of switching directly between legacy screens

### Requirement: Dashboard shows channels
The system SHALL show a channel panel on the dashboard. Real channels like Captain's Log and Debug Channel SHALL appear as functional cards that act as mutually exclusive activation zones and phone-side PTT zones. The cards SHALL display their current readiness state.

#### Scenario: Channel selected for activation
- **WHEN** the user taps the main surface area of a functional channel card
- **THEN** the system SHALL set that channel as the single active channel

#### Scenario: Channel long-pressed for PTT
- **WHEN** the user long-presses the main surface area of a functional channel card
- **THEN** the system SHALL set that channel as the single active channel
- **AND** start a phone-originated PTT session for that channel

#### Scenario: Captain's Log not configured
- **WHEN** the dashboard is visible and the Captain's Log has no directory selected
- **THEN** the system SHALL show the Captain's Log channel card with a prompt to configure it

#### Scenario: Channel configured and active
- **WHEN** the dashboard is visible and a channel (e.g. Captain's Log or Debug Channel) is configured and active
- **THEN** the system SHALL show the channel card as active with its current configuration state

#### Scenario: Channel configured but inactive
- **WHEN** the dashboard is visible and a channel is configured but another channel is active
- **THEN** the system SHALL show the channel card as visually inactive (Standby/Ready)

#### Scenario: Mock channels still shown
- **WHEN** the dashboard is visible
- **THEN** the system SHALL continue to show non-functional mock channel entries for Command Uplink

#### Scenario: Channel card opens configuration
- **WHEN** the user taps the dedicated "Config" button on a functional channel card
- **THEN** the system SHALL show the respective channel configuration surface without altering the channel's active state

### Requirement: Dashboard renders the VU meter during capture

The main dashboard SHALL render the VU meter (see `vu-meter` capability) driven
by the capture service's `level` and `isCapturing` signals, so the operator sees
live microphone level during any capture session. The dashboard SHALL obtain
these signals from `PttForegroundService` via the existing binder, alongside the
existing `appState` collection.

#### Scenario: Dashboard shows the meter while capturing
- **WHEN** the dashboard is visible and `isCapturing` is true
- **THEN** the dashboard renders the VU meter reflecting the live `level`

#### Scenario: Dashboard omits the meter while idle
- **WHEN** the dashboard is visible and `isCapturing` is false
- **THEN** the dashboard does not render the VU meter and reserves no space for it

#### Scenario: Meter reflects any talk mode
- **WHEN** the dashboard is visible and a capture session is active on any channel (journal, STT, or a future channel)
- **THEN** the dashboard renders the VU meter driven by the unified capture signal, with no per-mode wiring
