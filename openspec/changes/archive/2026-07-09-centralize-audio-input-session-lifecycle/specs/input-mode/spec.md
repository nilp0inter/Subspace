## MODIFIED Requirements

### Requirement: Mode-exclusive actuator gating during active capture
The system SHALL prevent a new PTT capture from starting while a PTT audio input session is already active, pending route acquisition, capturing, finalizing, or releasing, regardless of which actuator or mode started it.

#### Scenario: Second actuator press during active capture
- **WHEN** a PTT session is active (capture in progress)
- **AND** any other actuator is pressed
- **THEN** the system SHALL ignore the second press
- **AND** the active capture SHALL continue uninterrupted

#### Scenario: Second actuator press during route acquisition
- **WHEN** a PTT audio input session is acquiring a Work, On-a-pinch, or On-the-road route
- **AND** any other actuator is pressed
- **THEN** the system SHALL ignore or reject the second press
- **AND** the pending session SHALL remain responsible for route setup or cleanup

#### Scenario: Second actuator press during release
- **WHEN** a PTT audio input session is releasing its route
- **AND** any actuator is pressed before release cleanup is complete
- **THEN** the system SHALL NOT let stale release cleanup affect the new press
- **AND** the system SHALL either reject the press until release completes or start the new session with a distinct session identity

## ADDED Requirements

### Requirement: Input mode strategies feed the audio input subsystem
The selected input mode SHALL choose an audio input strategy inside the audio input subsystem. Actuator auto-transition SHALL occur before strategy selection, and the selected channel SHALL receive only the resulting channel input contract.

#### Scenario: Actuator selects home mode before strategy
- **WHEN** RSM, phone, or car PTT is pressed
- **THEN** the system applies the actuator home-mode transition rules
- **AND** the audio input subsystem selects the strategy for the resulting mode

#### Scenario: Strategy failure fails closed
- **WHEN** the selected mode strategy cannot prove ownership of its semantic endpoint
- **THEN** the system SHALL NOT dispatch capture to the selected channel
- **AND** the system SHALL NOT fall back to another mode's route
- **AND** the system SHALL leave no active route lease behind
