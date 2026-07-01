## MODIFIED Requirements

### Requirement: Dashboard shows channels
The system SHALL show a channel panel on the dashboard. Configured channel instances SHALL appear as functional cards that act as mutually exclusive activation zones and phone-side PTT zones. The cards SHALL display each instance's configured display name, configured order, channel type, and current readiness state. Functional cards SHALL also display phone PTT hold, lock-direction, locked, and stop feedback while a phone-originated PTT session is active from that card. Dedicated configuration controls SHALL remain outside the phone PTT gesture surface.

#### Scenario: Channel selected for activation
- **WHEN** the user taps the main surface area of a functional channel instance card
- **THEN** the system SHALL set that channel instance as the single active channel

#### Scenario: Channel long-pressed for PTT
- **WHEN** the user long-presses the main surface area of a functional channel instance card
- **THEN** the system SHALL set that channel instance as the single active channel
- **AND** start a phone-originated PTT session for that channel instance
- **AND** show held-recording feedback on that channel instance card

#### Scenario: Held phone PTT shows lock direction
- **WHEN** a phone-originated PTT session is active from a functional channel instance card
- **AND** the session is not locked
- **THEN** the dashboard SHALL show a lock instruction on that card
- **AND** the instruction SHALL point right when the initial press started on the left side of the card's PTT surface
- **AND** the instruction SHALL point left when the initial press started on the right side of the card's PTT surface

#### Scenario: Locked phone PTT shows stop affordance
- **WHEN** a phone-originated PTT session from a functional channel instance card has been slide-locked
- **THEN** the dashboard SHALL show that the PTT session is locked on that card
- **AND** the dashboard SHALL show an explicit stop affordance for ending the locked PTT session

#### Scenario: Journal instance not configured
- **WHEN** the dashboard is visible and a Journal channel instance has no directory selected
- **THEN** the system SHALL show that Journal channel instance card with a prompt to configure it

#### Scenario: Channel configured and active
- **WHEN** the dashboard is visible and a channel instance is configured and active
- **THEN** the system SHALL show the channel instance card as active with its current configuration state

#### Scenario: Channel configured but inactive
- **WHEN** the dashboard is visible and a channel instance is configured but another channel instance is active
- **THEN** the system SHALL show the channel instance card as visually inactive (Standby/Ready)

#### Scenario: Mock channels still shown
- **WHEN** the dashboard is visible
- **THEN** the system SHALL continue to show non-functional mock channel entries for Command Uplink

#### Scenario: Channel card opens configuration
- **WHEN** the user taps the dedicated "Config" button on a functional channel instance card
- **THEN** the system SHALL show the respective instance-specific channel configuration surface without altering the channel instance's active state
- **AND** the system SHALL NOT start, lock, stop, or release a phone-originated PTT session from that config-button tap

## ADDED Requirements

### Requirement: Dashboard supports channel instance list editing
The dashboard or dashboard-reachable configuration surface SHALL allow the user to add channel instances from available channel types, edit an instance display name, and change an instance list position.

#### Scenario: User adds a channel instance
- **WHEN** the user chooses an available channel type and confirms creation
- **THEN** the system SHALL create a new channel instance of that type
- **AND** the dashboard SHALL render the new instance in its configured list position

#### Scenario: User changes channel position
- **WHEN** the user changes a channel instance position
- **THEN** the dashboard SHALL render the channel cards in the updated configured order
- **AND** RSM and Android Auto navigation SHALL use the same updated order
