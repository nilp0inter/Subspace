## ADDED Requirements

### Requirement: Telecom-backed car PTT capture session
The system SHALL represent each in-car PTT capture interval as a self-managed Telecom VoIP call owned by Subspace.

#### Scenario: Start car PTT through Telecom
- **WHEN** the driver invokes the car PTT start action while the active channel is ready
- **THEN** the system SHALL place a self-managed Telecom call using Subspace's registered `PhoneAccount`
- **AND** create a Subspace-owned `Connection` for the capture interval

#### Scenario: Do not start capture before call audio route is ready
- **WHEN** a Subspace Telecom car PTT connection is created
- **THEN** the system SHALL wait for Telecom call audio state to report an acceptable capture route before starting microphone recording

#### Scenario: Bluetooth call audio route starts capture
- **WHEN** the active Subspace Telecom car PTT connection reports Bluetooth call audio as active
- **THEN** the system SHALL start microphone capture for the active channel
- **AND** mark the car PTT session as recording

### Requirement: End-call action stops car PTT
The system SHALL treat Telecom disconnect callbacks as the authoritative stop signal for an active car PTT capture.

#### Scenario: Steering-wheel hang-up stops capture
- **WHEN** a Subspace Telecom car PTT capture is recording
- **AND** the car sends an end-call action that causes `Connection.onDisconnect()`
- **THEN** the system SHALL stop microphone capture
- **AND** finalize the active PTT session
- **AND** disconnect and destroy the Telecom connection

#### Scenario: Telecom abort stops capture
- **WHEN** a Subspace Telecom car PTT connection receives an abort, reject, destroy, or call-loss callback while capture is active
- **THEN** the system SHALL stop microphone capture if it is running
- **AND** release the car PTT session state

### Requirement: Telecom car PTT fails safe
The system SHALL fail safe toward released capture state whenever Telecom or car audio state becomes ambiguous.

#### Scenario: Car disconnects during capture
- **WHEN** a Subspace Telecom car PTT capture is active
- **AND** Android Auto, Bluetooth, or Telecom call audio disconnects
- **THEN** the system SHALL stop microphone capture
- **AND** release the active car PTT session

#### Scenario: Capture route timeout
- **WHEN** a Subspace Telecom car PTT connection is created
- **AND** no acceptable call audio route becomes active before the configured timeout
- **THEN** the system SHALL disconnect the Telecom connection
- **AND** leave car PTT released
- **AND** provide error feedback when possible

#### Scenario: Real call conflict
- **WHEN** a real cellular or higher-priority Telecom call conflicts with Subspace car PTT
- **THEN** the system SHALL abort or release the Subspace car PTT capture
- **AND** leave the microphone closed

### Requirement: Response playback uses media audio
The system SHALL play post-capture responses through normal media playback after the Telecom call has ended.

#### Scenario: Response playback after capture finalization
- **WHEN** a Subspace Telecom car PTT capture has finalized
- **AND** response audio is available
- **THEN** the system SHALL ensure the Telecom connection is disconnected
- **AND** request media audio focus
- **AND** play the response through the media output path
- **AND** abandon media audio focus after playback completes

### Requirement: Previous media-session PTT stop path is not used
The system SHALL NOT rely on Android Auto media transport controls as the stop signal for an active microphone capture session.

#### Scenario: Media control arrives during Telecom capture
- **WHEN** a Subspace Telecom car PTT capture is active
- **AND** the system receives a media play, pause, stop, or play-pause command
- **THEN** the command SHALL NOT be required to stop capture
- **AND** Telecom disconnect handling SHALL remain the authoritative stop path
