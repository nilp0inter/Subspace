## MODIFIED Requirements

### Requirement: STT test control is available in Debug Channel configuration
The system SHALL show the speech-to-text test mode option in the Debug Channel configuration screen.

#### Scenario: Debug Channel config surface is displayed
- **WHEN** the user opens the Debug Channel configuration screen
- **THEN** the system shows the STT test mode as a selectable option among the debug modes
- **AND** if STT mode is selected, the system shows a transcript text box below the selection controls

#### Scenario: No transcript is available yet
- **WHEN** the Debug Channel configuration screen is displayed before any STT result exists and STT is the active mode
- **THEN** the STT transcript text box shows an empty or idle STT state

## REMOVED Requirements

### Requirement: Echo and STT test modes are mutually exclusive
**Reason**: Mutual exclusion is now handled natively by the Debug Channel's `mode` property, which enforces exactly one mode at a time.
**Migration**: Select the desired test mode via the Debug Channel mode selector.
