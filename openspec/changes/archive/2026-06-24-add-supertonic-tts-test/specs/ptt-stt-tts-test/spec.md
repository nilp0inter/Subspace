## ADDED Requirements

### Requirement: STT↔TTS test control is available beside the other test controls
The system SHALL show a speech-to-text-to-speech round-trip test toggle on the connected monitor/test surface that contains the echo, STT, and TTS test toggles. The STT↔TTS test toggle SHALL NOT show a text box; the input text comes from the user's voice.

#### Scenario: Connected test surface is displayed
- **WHEN** the user opens the connected monitor/test surface
- **THEN** the system shows an STT↔TTS test toggle alongside the echo, STT, and TTS test toggles
- **AND** below the STT↔TTS test toggle the system shows a status/transcript area and no text input box

#### Scenario: Round-trip test is not yet run
- **WHEN** the connected test surface is displayed before any STT↔TTS result exists
- **THEN** the status/transcript area below the STT↔TTS toggle shows an idle state

### Requirement: Echo, STT, TTS, and STT↔TTS test modes are mutually exclusive
The system SHALL allow at most one of echo test mode, STT test mode, TTS test mode, and STT↔TTS test mode to be enabled at a time.

#### Scenario: STT↔TTS is enabled while another mode is enabled
- **WHEN** exactly one of echo, STT, or TTS test mode is enabled and the user enables STT↔TTS test mode
- **THEN** the system enables STT↔TTS test mode
- **AND** the system disables and cancels the previously enabled mode

#### Scenario: Another mode is enabled while STT↔TTS is enabled
- **WHEN** STT↔TTS test mode is enabled and the user enables echo, STT, or TTS test mode
- **THEN** the system enables the newly selected mode
- **AND** the system disables and cancels STT↔TTS test mode

#### Scenario: STT↔TTS mode is disabled
- **WHEN** STT↔TTS test mode is enabled and the user disables it
- **THEN** the system leaves all four test modes disabled
- **AND** the system cancels any active STT↔TTS work

### Requirement: STT↔TTS test records audio from PTT activity
The system SHALL record audio for the STT↔TTS round-trip using the same PTT-controlled Bluetooth SCO recording path as the STT test.

#### Scenario: PTT is pressed while STT↔TTS is enabled
- **WHEN** STT↔TTS test mode is enabled and the user presses PTT
- **THEN** the system acquires the Bluetooth SCO route
- **AND** the system plays the ready beep through the selected communication audio route
- **AND** the system starts recording mono PCM audio

#### Scenario: PTT is released after audio is recorded
- **WHEN** STT↔TTS test mode is enabled, recording is active, and the user releases PTT
- **THEN** the system stops recording
- **AND** the system submits the captured audio to the local Parakeet transcriber
- **AND** the system does not play the captured audio back as echo

#### Scenario: PTT is released before recording starts
- **WHEN** STT↔TTS test mode is enabled and the user releases PTT before recording starts
- **THEN** the system cancels the pending recording session
- **AND** the system does not submit empty audio to Parakeet

#### Scenario: Recording reaches maximum duration
- **WHEN** STT↔TTS test mode is enabled and the user holds PTT past the maximum recording duration
- **THEN** the system stops recording at the maximum duration
- **AND** the system waits for PTT release before submitting the retained recording

### Requirement: STT↔TTS test transcribes then synthesizes then plays back
The system SHALL transcribe captured STT↔TTS audio on-device with Parakeet, synthesize speech from the resulting transcript on-device with Supertonic, and play the synthesized audio through the connected Bluetooth SCO route.

#### Scenario: Round-trip completes successfully
- **WHEN** STT↔TTS test mode submits captured audio and Parakeet returns text and Supertonic returns audio for that text
- **THEN** the system stores the transcript as the latest STT↔TTS transcript
- **AND** the system shows a transcribing status during transcription and a synthesizing status during synthesis
- **AND** the system resamples the 44.1 kHz Supertonic output to the SCO output rate
- **AND** the system plays the synthesized audio through the headset
- **AND** the connected monitor/test surface shows the transcript in the STT↔TTS status/transcript area

#### Scenario: Captured audio contains no samples
- **WHEN** STT↔TTS test mode stops recording and the captured audio is empty
- **THEN** the system does not invoke Parakeet
- **AND** the system shows an empty-audio status

#### Scenario: Transcription returns empty text
- **WHEN** Parakeet returns an empty transcript for the captured audio
- **THEN** the system does not invoke Supertonic
- **AND** the system shows an empty-transcript status

#### Scenario: Transcription fails
- **WHEN** Parakeet transcription returns an error
- **THEN** the system shows a transcription error status
- **AND** the system does not invoke Supertonic

#### Scenario: Synthesis fails
- **WHEN** Supertonic synthesis returns an error for the transcript
- **THEN** the system shows a synthesis error status
- **AND** the system does not play audio

#### Scenario: A model is still loading when its stage runs
- **WHEN** STT↔TTS test mode reaches the transcription stage while Parakeet is still loading, or reaches the synthesis stage while Supertonic is still loading
- **THEN** the system waits for the required model readiness without blocking the UI thread
- **AND** the connected monitor/test surface shows a waiting-for-model status for the relevant stage

#### Scenario: Audio or text would leave the device
- **WHEN** STT↔TTS test mode processes captured audio, transcripts, or synthesized audio
- **THEN** the system MUST NOT send the captured audio, transcript, or synthesized audio to a network service

### Requirement: STT↔TTS sessions are cancelled on disconnect or mode change
The system SHALL stop active or pending STT↔TTS recording, transcription, synthesis, or playback work when the relevant device session or active test mode ends.

#### Scenario: Serial disconnect occurs during STT↔TTS work
- **WHEN** STT↔TTS test mode has active or pending work and the serial connection is disconnected
- **THEN** the system cancels active STT↔TTS work
- **AND** the system releases any acquired SCO route
- **AND** the system leaves no STT↔TTS work active after disconnect completes

#### Scenario: Another mode is enabled during STT↔TTS work
- **WHEN** STT↔TTS test mode has active or pending work and the user enables echo, STT, or TTS test mode
- **THEN** the system cancels STT↔TTS work
- **AND** the system enables the newly selected mode