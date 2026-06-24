## MODIFIED Requirements

### Requirement: STT↔TTS test control is available in Debug Channel configuration
The system SHALL show the speech-to-text-to-speech round-trip test mode option in the Debug Channel configuration screen.

#### Scenario: Debug Channel config surface is displayed
- **WHEN** the user opens the Debug Channel configuration screen
- **THEN** the system shows the STT↔TTS test mode as a selectable option among the debug modes
- **AND** if STT↔TTS mode is selected, below the selection the system shows a status/transcript area and no text input box

#### Scenario: Round-trip test is not yet run
- **WHEN** the Debug Channel configuration screen is displayed before any STT↔TTS result exists and STT↔TTS is the active mode
- **THEN** the status/transcript area shows an idle state

## REMOVED Requirements

### Requirement: Echo, STT, TTS, and STT↔TTS test modes are mutually exclusive
**Reason**: Mutual exclusion is now handled natively by the Debug Channel's `mode` property, which enforces exactly one mode at a time.
**Migration**: Select the desired test mode via the Debug Channel mode selector.
