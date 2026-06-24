## MODIFIED Requirements

### Requirement: Dashboard shows channels
The system SHALL show a channel panel on the dashboard. Real channels like Captain's Log and Debug Channel SHALL appear as functional cards that act as mutually exclusive activation zones. The cards SHALL display their current readiness state.

#### Scenario: Channel selected for activation
- **WHEN** the user taps the main surface area of a functional channel card
- **THEN** the system SHALL set that channel as the single active channel

#### Scenario: Channel card opens configuration
- **WHEN** the user taps the dedicated \"Config\" button on a functional channel card
- **THEN** the system SHALL show the respective channel configuration surface without altering the channel's active state

#### Scenario: Channel configured but inactive
- **WHEN** the dashboard is visible and a channel is configured but another channel is active
- **THEN** the system SHALL show the channel card as visually inactive (Standby/Ready)

#### Scenario: Mock channels still shown
- **WHEN** the dashboard is visible
- **THEN** the system SHALL continue to show non-functional mock channel entries for Command Uplink
