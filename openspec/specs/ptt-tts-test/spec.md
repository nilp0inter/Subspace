## Purpose

Defines a push-to-talk text-to-speech test mode on the connected monitor/test surface that loads Supertonic 3 on device, exposes supported synthesis parameters, and plays synthesized speech through the connected Bluetooth SCO route.

## Requirements

### Requirement: Supertonic model loads on app startup
The system SHALL start loading the Supertonic 3 text-to-speech model after app startup without blocking initial UI rendering, alongside the existing Parakeet model load.

#### Scenario: Startup begins model loading
- **WHEN** the app process starts and the foreground service is created by the launched app
- **THEN** the system starts Supertonic model loading in the background
- **AND** the app remains responsive while loading continues

#### Scenario: Model becomes ready
- **WHEN** Supertonic model loading completes successfully
- **THEN** the system marks TTS model readiness as ready
- **AND** subsequent TTS test syntheses can run without reloading the model

#### Scenario: Model load fails
- **WHEN** Supertonic model loading fails
- **THEN** the system marks TTS model readiness as failed
- **AND** the connected monitor/test surface shows the model load error instead of silently ignoring TTS requests

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

### Requirement: TTS test synthesizes and plays text on demand
The system SHALL synthesize speech from the text in the TTS test text box using Supertonic with the user-selected parameters and play the resulting audio through the connected Bluetooth SCO route.

#### Scenario: User triggers TTS synthesis while enabled
- **WHEN** TTS test mode is enabled, the headset SCO route is available, and the user requests synthesis (for example by pressing PTT or tapping a synthesize control)
- **THEN** the system acquires the Bluetooth SCO route
- **THEN** the system synthesizes speech from the current text box content with the selected voice style, language, total steps, and speed
- **AND** the system plays the synthesized audio through the headset SCO route
- **AND** the system shows a synthesizing status while synthesis is in progress and a playback status while audio is playing

#### Scenario: Synthesis completes successfully
- **WHEN** Supertonic returns audio for the requested text
- **THEN** the system resamples the 44.1 kHz output to the SCO output rate
- **AND** the system plays the resampled audio through the headset
- **AND** the system returns to an idle TTS status after playback completes

#### Scenario: Text box is empty
- **WHEN** TTS test mode is enabled and the user requests synthesis while the text box is empty
- **THEN** the system does not invoke Supertonic
- **AND** the system shows an empty-text TTS status

#### Scenario: Model is still loading when synthesis is requested
- **WHEN** TTS test mode requests synthesis while Supertonic model loading is still in progress
- **THEN** the system waits for model readiness without blocking the UI thread
- **AND** the connected monitor/test surface shows a waiting-for-model or synthesizing status

#### Scenario: Synthesis fails
- **WHEN** Supertonic synthesis returns an error
- **THEN** the system stores a TTS error status
- **AND** the connected monitor/test surface shows the error instead of playing audio

#### Scenario: Audio would leave the device
- **WHEN** TTS test mode processes text or audio
- **THEN** the system MUST NOT send the text, transcript, or synthesized audio to a network service

### Requirement: TTS test exposes Supertonic-controllable parameters
The system SHALL expose the Supertonic parameters that the integration supports below the TTS test toggle and use them when synthesizing.

#### Scenario: User changes a Supertonic parameter
- **WHEN** the user changes the voice style, language, total steps, or speed control below the TTS test toggle
- **THEN** the system stores the new parameter value in app state
- **AND** the next TTS synthesis uses the updated parameter value

#### Scenario: Invalid language is rejected
- **WHEN** the user selects a language Supertonic does not support
- **THEN** the system does not attempt synthesis
- **AND** the system shows a TTS error status describing the invalid language

### Requirement: TTS sessions are cancelled on disconnect or mode change
The system SHALL stop active or pending TTS synthesis or playback work when the relevant device session or active test mode ends.

#### Scenario: Serial disconnect occurs during TTS work
- **WHEN** TTS test mode has active or pending synthesis or playback work and the serial connection is disconnected
- **THEN** the system cancels active TTS work
- **AND** the system releases any acquired SCO route
- **AND** the system leaves no TTS work active after disconnect completes

#### Scenario: TTS mode is disabled during playback
- **WHEN** TTS test mode is playing audio and the user disables TTS test mode
- **THEN** the system stops playback
- **AND** the system releases any acquired SCO route

#### Scenario: Another mode is enabled during TTS work
- **WHEN** TTS test mode has active or pending work and the user enables echo, STT, or STT↔TTS test mode
- **THEN** the system cancels TTS work
- **AND** the system enables the newly selected mode
