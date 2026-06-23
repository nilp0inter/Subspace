## MODIFIED Requirements

### Requirement: STT test control is available beside echo test
The system SHALL show a speech-to-text test toggle on the connected monitor/test surface that contains the echo test toggle, the TTS test toggle, and the STT↔TTS test toggle.

#### Scenario: Connected test surface is displayed
- **WHEN** the user opens the connected monitor/test surface
- **THEN** the system shows the existing echo test toggle
- **AND** the system shows an STT test toggle
- **AND** the system shows a TTS test toggle
- **AND** the system shows an STT↔TTS test toggle
- **AND** the system shows a transcript text box below the STT test toggle

#### Scenario: No transcript is available yet
- **WHEN** the connected test surface is displayed before any STT result exists
- **THEN** the STT transcript text box shows an empty or idle STT state

### Requirement: Echo and STT test modes are mutually exclusive
The system SHALL allow at most one of echo test mode, STT test mode, TTS test mode, and STT↔TTS test mode to be enabled at a time.

#### Scenario: STT is enabled while another mode is enabled
- **WHEN** exactly one of echo, TTS, or STT↔TTS test mode is enabled and the user enables STT test mode
- **THEN** the system enables STT test mode
- **AND** the system disables and cancels the previously enabled mode

#### Scenario: Echo is enabled while another mode is enabled
- **WHEN** exactly one of STT, TTS, or STT↔TTS test mode is enabled and the user enables echo test mode
- **THEN** the system enables echo test mode
- **AND** the system disables and cancels the previously enabled mode

#### Scenario: Active mode is disabled
- **WHEN** exactly one test mode is enabled and the user disables that mode
- **THEN** the system leaves echo, STT, TTS, and STT↔TTS test modes disabled

#### Scenario: PTT event is received while all modes are disabled
- **WHEN** echo, STT, TTS, and STT↔TTS test modes are all disabled and a PTT press or release event is received
- **THEN** the system updates normal button state
- **AND** the system does not start echo recording, STT recording, TTS synthesis, or STT↔TTS work