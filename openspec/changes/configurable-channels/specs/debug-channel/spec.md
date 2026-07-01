## MODIFIED Requirements

### Requirement: Debug Channel configuration and state
The system SHALL provide a built-in channel type named "Debug Channel" that acts as a diagnostic tool. Each Debug Channel instance MUST have a single active mode of operation (Echo, STT, TTS, or STT<->TTS), and that mode SHALL be stored independently from every other Debug Channel instance.

#### Scenario: Channel mode is configured
- **WHEN** the user selects a debug mode for a Debug Channel instance
- **THEN** the system SHALL store the selected mode in that channel instance's configuration
- **AND** the next time that channel instance is activated, it SHALL operate in that mode
- **AND** other Debug Channel instances SHALL keep their existing modes

#### Scenario: Multiple Debug instances exist
- **WHEN** the configured channel list contains two or more Debug Channel instances
- **THEN** each instance SHALL be selectable as a distinct channel
- **AND** each instance SHALL use its own configured Debug mode during PTT routing

### Requirement: Debug Channel configuration screen
The system SHALL provide an instance-specific configuration screen for Debug Channel instances.

#### Scenario: Configuration screen opened
- **WHEN** the user taps the configuration control for a Debug Channel instance on the dashboard
- **THEN** the system SHALL show the Debug Channel configuration screen for that specific instance
- **AND** the screen SHALL display mutually exclusive selection controls for the debug modes (Echo, STT, TTS, STT<->TTS)
- **AND** the screen SHALL provide controls for the instance display name and list position
- **AND** changes SHALL apply only to the selected Debug Channel instance
