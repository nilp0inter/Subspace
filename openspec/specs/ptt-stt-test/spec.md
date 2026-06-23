## Purpose

Defines a push-to-talk speech-to-text test mode on the connected monitor/test surface that records Bluetooth SCO audio via the existing PTT-controlled path and transcribes it on-device with Parakeet v3, alongside the existing echo test mode.

## Requirements

### Requirement: Parakeet model loads on app startup
The system SHALL start loading the Parakeet v3 speech-to-text model after app startup without blocking initial UI rendering.

#### Scenario: Startup begins model loading
- **WHEN** the app process starts and the foreground service is created by the launched app
- **THEN** the system starts Parakeet model loading in the background
- **AND** the app remains responsive while loading continues

#### Scenario: Model becomes ready
- **WHEN** Parakeet model loading completes successfully
- **THEN** the system marks STT model readiness as ready
- **AND** subsequent STT test recordings can be transcribed without reloading the model

#### Scenario: Model load fails
- **WHEN** Parakeet model loading fails
- **THEN** the system marks STT model readiness as failed
- **AND** the connected monitor/test surface shows the model load error instead of silently ignoring STT requests

### Requirement: STT test control is available beside echo test
The system SHALL show a speech-to-text test toggle on the connected monitor/test surface that contains the echo test toggle.

#### Scenario: Connected test surface is displayed
- **WHEN** the user opens the connected monitor/test surface
- **THEN** the system shows the existing echo test toggle
- **AND** the system shows an STT test toggle
- **AND** the system shows a transcript text box below the STT test toggle

#### Scenario: No transcript is available yet
- **WHEN** the connected monitor/test surface is displayed before any STT result exists
- **THEN** the STT transcript text box shows an empty or idle STT state

### Requirement: Echo and STT test modes are mutually exclusive
The system SHALL allow at most one of echo test mode and STT test mode to be enabled at a time.

#### Scenario: STT is enabled while echo is enabled
- **WHEN** echo test mode is enabled and the user enables STT test mode
- **THEN** the system enables STT test mode
- **AND** the system disables echo test mode

#### Scenario: Echo is enabled while STT is enabled
- **WHEN** STT test mode is enabled and the user enables echo test mode
- **THEN** the system enables echo test mode
- **AND** the system disables STT test mode

#### Scenario: Active mode is disabled
- **WHEN** exactly one test mode is enabled and the user disables that mode
- **THEN** the system leaves echo test mode disabled
- **AND** the system leaves STT test mode disabled

#### Scenario: PTT event is received while both modes are disabled
- **WHEN** echo test mode is disabled, STT test mode is disabled, and a PTT press or release event is received
- **THEN** the system updates normal button state
- **AND** the system does not start echo recording or STT recording

### Requirement: STT test records audio from PTT activity
The system SHALL record audio for STT using the same PTT-controlled Bluetooth SCO recording path as the echo test.

#### Scenario: PTT is pressed while STT is enabled
- **WHEN** STT test mode is enabled and the user presses PTT
- **THEN** the system acquires the Bluetooth SCO route
- **AND** the system plays the ready beep through the selected communication audio route
- **AND** the system starts recording mono PCM audio for transcription

#### Scenario: PTT is released after audio is recorded
- **WHEN** STT test mode is enabled, recording is active, and the user releases PTT
- **THEN** the system stops recording
- **AND** the system submits the captured audio to the local Parakeet transcriber
- **AND** the system does not play the captured audio back as echo output

#### Scenario: PTT is released before recording starts
- **WHEN** STT test mode is enabled and the user releases PTT before recording starts
- **THEN** the system cancels the pending STT recording session
- **AND** the system does not submit empty audio to Parakeet

#### Scenario: Recording reaches maximum duration
- **WHEN** STT test mode is enabled and the user holds PTT past the maximum recording duration
- **THEN** the system stops recording at the maximum duration
- **AND** the system waits for PTT release before submitting the retained recording to Parakeet

### Requirement: STT transcription is local and result-driven
The system SHALL transcribe captured STT test audio on-device with Parakeet and expose the resulting text in app state.

#### Scenario: Captured audio is transcribed successfully
- **WHEN** STT test mode submits captured audio and Parakeet returns text
- **THEN** the system stores the returned text as the latest STT transcript
- **AND** the connected monitor/test surface shows that transcript in the STT transcript text box

#### Scenario: Captured audio contains no samples
- **WHEN** STT test mode stops recording and the captured audio is empty
- **THEN** the system does not invoke Parakeet
- **AND** the connected monitor/test surface shows an empty-audio STT status

#### Scenario: Model is still loading when audio is submitted
- **WHEN** STT test mode submits captured audio while Parakeet model loading is still in progress
- **THEN** the system waits for model readiness without blocking the UI thread
- **AND** the connected monitor/test surface shows a waiting-for-model or transcribing status

#### Scenario: Transcription fails
- **WHEN** Parakeet transcription returns an error
- **THEN** the system stores an STT error status
- **AND** the connected monitor/test surface shows the error instead of stale transcript text

#### Scenario: Audio would leave the device
- **WHEN** STT test mode processes captured audio
- **THEN** the system MUST NOT send the captured audio or transcript to a network service

### Requirement: STT sessions are cancelled on disconnect or mode change
The system SHALL stop active or pending STT recording work when the relevant device session or active test mode ends.

#### Scenario: Serial disconnect occurs during STT work
- **WHEN** STT test mode has active or pending recording or transcription work and the serial connection is disconnected
- **THEN** the system cancels active STT recording work
- **AND** the system releases any acquired SCO route
- **AND** the system leaves no STT recording active after disconnect completes

#### Scenario: STT mode is disabled during recording
- **WHEN** STT test mode is recording and the user disables STT test mode
- **THEN** the system stops the STT recording session
- **AND** the system does not submit the cancelled recording to Parakeet

#### Scenario: Echo mode is enabled during STT work
- **WHEN** STT test mode has active or pending recording work and the user enables echo test mode
- **THEN** the system cancels STT recording work
- **AND** the system enables echo test mode