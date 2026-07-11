## MODIFIED Requirements

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
