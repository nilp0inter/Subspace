## MODIFIED Requirements

### Requirement: Dashboard shows mock channels
The system SHALL show a channel panel on the dashboard. When the Captain's Log channel is configured, it SHALL appear as a real channel card with its configuration state. Remaining mock channels SHALL continue to display as non-functional previews.

#### Scenario: Captain's Log not configured
- **WHEN** the dashboard is visible and the Captain's Log has no directory selected
- **THEN** the system SHALL show the Captain's Log channel card with a prompt to configure it

#### Scenario: Captain's Log configured and active
- **WHEN** the dashboard is visible and the Captain's Log is configured and active
- **THEN** the system SHALL show the Captain's Log channel card with its current toggle states (save voice, save text) and the selected directory

#### Scenario: Captain's Log configured but inactive (test mode active)
- **WHEN** the dashboard is visible and the Captain's Log is configured but a test mode is active
- **THEN** the system SHALL show the Captain's Log channel card as inactive

#### Scenario: Mock channels still shown
- **WHEN** the dashboard is visible
- **THEN** the system SHALL continue to show non-functional mock channel entries for Command Uplink and Diagnostics

#### Scenario: Channel card opens configuration
- **WHEN** the user taps the Captain's Log channel card
- **THEN** the system SHALL show the channel configuration surface (directory picker, toggle controls, activate/deactivate)
