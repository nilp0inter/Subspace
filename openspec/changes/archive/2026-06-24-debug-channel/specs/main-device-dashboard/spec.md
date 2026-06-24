## MODIFIED Requirements

### Requirement: Dashboard shows channels
The system SHALL show a channel panel on the dashboard. Real channels like Captain's Log and Debug Channel SHALL appear as functional cards with their configuration states. Mock channels SHALL continue to display as non-functional previews.

#### Scenario: Captain's Log not configured
- **WHEN** the dashboard is visible and the Captain's Log has no directory selected
- **THEN** the system SHALL show the Captain's Log channel card with a prompt to configure it

#### Scenario: Channel configured and active
- **WHEN** the dashboard is visible and a channel (e.g. Captain's Log or Debug Channel) is configured and active
- **THEN** the system SHALL show the channel card as active with its current configuration state

#### Scenario: Channel configured but inactive
- **WHEN** the dashboard is visible and a channel is configured but another channel is active
- **THEN** the system SHALL show the channel card as inactive

#### Scenario: Mock channels still shown
- **WHEN** the dashboard is visible
- **THEN** the system SHALL continue to show non-functional mock channel entries for Command Uplink

#### Scenario: Channel card opens configuration
- **WHEN** the user taps a functional channel card
- **THEN** the system SHALL show the respective channel configuration surface
