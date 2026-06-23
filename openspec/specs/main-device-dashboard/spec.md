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

### Requirement: Dashboard shows mock channels
The system SHALL show a list of mock communication channels on the dashboard without enabling real channel communication.

#### Scenario: Dashboard channel list displayed
- **WHEN** the dashboard is visible
- **THEN** the system shows multiple channel entries
- **AND** each channel entry is presented as a non-functional mock-up or preview

#### Scenario: Mock channel selected
- **WHEN** the user taps a mock channel entry
- **THEN** the system does not start audio capture, command execution, network communication, or persistent storage
