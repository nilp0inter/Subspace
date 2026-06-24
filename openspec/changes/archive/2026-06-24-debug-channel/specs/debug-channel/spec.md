## ADDED Requirements

### Requirement: Debug Channel configuration and state
The system SHALL provide a built-in channel type named "Debug Channel" that acts as a diagnostic tool. The channel MUST have a single active mode of operation (Echo, STT, TTS, or STT↔TTS).

#### Scenario: Channel mode is configured
- **WHEN** the user selects a debug mode for the Debug Channel
- **THEN** the system SHALL store the selected mode in the channel configuration
- **AND** the next time the channel is activated, it SHALL operate in that mode

### Requirement: Debug Channel configuration screen
The system SHALL provide a dedicated configuration screen for the Debug Channel.

#### Scenario: Configuration screen opened
- **WHEN** the user taps the Debug Channel card on the dashboard
- **THEN** the system SHALL show the Debug Channel configuration screen
- **AND** the screen SHALL display mutually exclusive selection controls for the debug modes (Echo, STT, TTS, STT↔TTS)
- **AND** the screen SHALL provide an activate/deactivate control for the channel
