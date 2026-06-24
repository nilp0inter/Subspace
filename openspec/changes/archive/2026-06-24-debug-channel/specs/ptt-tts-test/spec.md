## MODIFIED Requirements

### Requirement: TTS test control is available in Debug Channel configuration
The system SHALL show the text-to-speech test mode option in the Debug Channel configuration screen.

#### Scenario: Debug Channel config surface is displayed
- **WHEN** the user opens the Debug Channel configuration screen
- **THEN** the system shows the TTS test mode as a selectable option among the debug modes
- **AND** if TTS mode is selected, below the selection the system shows an editable text box prefilled with a short default phrase
- **AND** below the TTS selection the system shows the Supertonic controls the integration exposes (voice style, language, quality/total steps, and speed)

#### Scenario: TTS test is not yet run
- **WHEN** the Debug Channel configuration screen is displayed before any TTS result exists and TTS is the active mode
- **THEN** the TTS status area shows an idle TTS state

## REMOVED Requirements

### Requirement: Echo, STT, TTS, and STT↔TTS test modes are mutually exclusive
**Reason**: Mutual exclusion is now handled natively by the Debug Channel's `mode` property, which enforces exactly one mode at a time.
**Migration**: Select the desired test mode via the Debug Channel mode selector.
