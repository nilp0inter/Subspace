## ADDED Requirements

### Requirement: Single active capture session

The system SHALL own exactly one active PTT audio capture session at a time. A
second start attempt while a session is active SHALL be rejected by the capture
service rather than relying solely on dispatch-layer guards.

#### Scenario: Second start while a session is active is rejected
- **WHEN** a capture session is active and a second start is requested
- **THEN** the system rejects the second start without creating a new `AudioRecord`
- **AND** the system leaves the existing capture session running unchanged

#### Scenario: No session active accepts a start
- **WHEN** no capture session is active and a start is requested
- **THEN** the system creates a single new capture session

### Requirement: Capture input source is selected by the active route

The system SHALL select the capture input source from the active audio route:
Bluetooth SCO when a SCO communication device is available, otherwise the phone
microphone. The capture service SHALL start its `AudioRecord` with the selected
source (`VOICE_COMMUNICATION` for SCO, `MIC` for phone) rather than holding one
recorder class per source.

#### Scenario: SCO route available selects VOICE_COMMUNICATION
- **WHEN** a SCO communication device is available and a capture session starts
- **THEN** the system records using the `VOICE_COMMUNICATION` audio source

#### Scenario: No SCO route selects phone microphone
- **WHEN** no SCO communication device is available and a capture session starts
- **THEN** the system records using the `MIC` audio source

### Requirement: Capture emits live PCM frames to subscribers

While a capture session is active, the system SHALL emit captured PCM chunks as a
live stream that channel pipelines and level consumers can subscribe to. The
stream SHALL NOT backpressure the audio read loop: a slow or absent subscriber
SHALL NOT stall capture.

#### Scenario: Subscriber receives chunks while capturing
- **WHEN** a capture session is active and a subscriber is collecting the frames stream
- **THEN** the subscriber receives PCM chunks as they are read from the microphone

#### Scenario: Slow subscriber does not stall the read loop
- **WHEN** a frames subscriber is slow and the capture session is active
- **THEN** the system drops stale chunks rather than blocking the audio read loop
- **AND** capture continues without underrun

#### Scenario: No subscriber does not prevent capture
- **WHEN** a capture session is active and there is no frames subscriber
- **THEN** capture proceeds normally and the terminal result is still available at session end

### Requirement: Capture returns terminal recorded PCM at session end

The system SHALL return the complete captured PCM (with sample rate) when a
capture session ends, so that consumers which operate on the whole capture (echo
playback, STT transcription) can consume it without subscribing to live frames.

#### Scenario: Session end returns the full capture
- **WHEN** a capture session that recorded audio is ended
- **THEN** the system returns a `RecordedPcm` containing all recorded samples and the sample rate

#### Scenario: Empty session returns empty PCM
- **WHEN** a capture session is ended without having recorded any audio
- **THEN** the system returns an empty `RecordedPcm` with the configured sample rate

### Requirement: Recording starts only after the ready beep completes

The system SHALL start audio recording only after the ready beep playback has
fully completed, preserving the `sco-audio` capture-priming contract. This
sequencing (acquire SCO, play ready beep with cold-start priming, then record)
SHALL be owned by the capture session, not duplicated per channel controller.

#### Scenario: Recording starts after beep completes while PTT held
- **WHEN** the ready beep finishes playing and PTT is still held
- **THEN** the system starts recording from the selected microphone source

#### Scenario: PTT released during the ready beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system does not start recording
- **AND** the system cancels the session
- **AND** the system retains the warm SCO route

#### Scenario: PTT released during SCO acquisition
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system continues SCO acquisition to completion
- **AND** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system retains the SCO route warm for the configured retention window

### Requirement: Maximum capture duration

The system SHALL cap a single capture session at 60 seconds. If PTT remains held
past the cap, the system SHALL end the session at the cap with the audio captured
so far.

#### Scenario: Capture ends at the 60-second cap
- **WHEN** a capture session reaches 60 seconds while PTT is still held
- **THEN** the system ends the session and returns the PCM captured up to the cap

### Requirement: Live capture level signal

While a capture session is active, the system SHALL expose a live audio level
signal (normalized RMS in the range 0..1) computed from the captured PCM in the
audio read loop. When no session is active, the level SHALL be 0.

#### Scenario: Level reflects microphone input while capturing
- **WHEN** a capture session is active and the microphone receives input
- **THEN** the system emits a level greater than 0 derived from the captured PCM

#### Scenario: Level is 0 when not capturing
- **WHEN** no capture session is active
- **THEN** the system exposes a level of 0

#### Scenario: Silent input yields a low level
- **WHEN** a capture session is active and the microphone receives silence
- **THEN** the system emits a level at or near 0

### Requirement: isCapturing transmit-state signal

The system SHALL expose a single `isCapturing` signal that is true while any
capture session is active and false otherwise. This signal SHALL be the unified
transmit-state indicator across all talk modes.

#### Scenario: Signal is true while capturing
- **WHEN** a capture session is active
- **THEN** the `isCapturing` signal is true

#### Scenario: Signal is false when idle
- **WHEN** no capture session is active
- **THEN** the `isCapturing` signal is false

### Requirement: Channel pipelines consume capture via the stream

Channel pipelines (echo, STT, STT↔TTS, journal, and future channels) SHALL obtain
captured audio from the capture service rather than owning their own recorder.
Output format is a property of the consuming pipeline, not of capture.

#### Scenario: In-memory consumer uses the terminal capture
- **WHEN** an in-memory consumer (echo or STT) handles a PTT cycle
- **THEN** it obtains the captured PCM from the capture session's terminal result
- **AND** it does not construct or own an `AudioRecord`

#### Scenario: Journal consumer writes WAV from the stream
- **WHEN** the journal pipeline handles a PTT cycle
- **THEN** it subscribes to the live frames stream and writes PCM incrementally to a WAV file
- **AND** it finalizes the WAV header at session end from the journal-side writer
- **AND** it does not construct a private recorder
