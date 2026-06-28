## ADDED Requirements

### Requirement: Android Auto media controls expose virtual PTT
The system SHALL expose a media session that allows Android Auto steering-wheel media play/pause controls to act as a latched virtual PTT button for Subspace.

#### Scenario: Play/pause starts virtual PTT from released state
- **WHEN** the car media play/pause control is activated while virtual PTT is released
- **AND** the active channel is ready
- **THEN** the system SHALL emit a car-originated PTT press for the active channel
- **AND** mark virtual PTT as pressed

#### Scenario: Play/pause releases virtual PTT from pressed state
- **WHEN** the car media play/pause control is activated while virtual PTT is pressed
- **THEN** the system SHALL emit a car-originated PTT release
- **AND** mark virtual PTT as released

#### Scenario: Stop-like media command releases virtual PTT
- **WHEN** virtual PTT is pressed
- **AND** the system receives a car media pause, stop, or other stop-like transport command
- **THEN** the system SHALL emit a car-originated PTT release
- **AND** mark virtual PTT as released

### Requirement: Virtual PTT fails safe to released
The system SHALL force-release virtual PTT whenever the car media control path becomes unavailable or capture safety cannot be guaranteed.

#### Scenario: Android Auto disconnects during virtual PTT
- **WHEN** virtual PTT is pressed
- **AND** Android Auto or the car media session disconnects
- **THEN** the system SHALL emit a car-originated PTT release
- **AND** mark virtual PTT as released

#### Scenario: Service stops during virtual PTT
- **WHEN** virtual PTT is pressed
- **AND** the foreground service is stopping or being destroyed
- **THEN** the system SHALL emit a car-originated PTT release before releasing capture resources

#### Scenario: Capture reaches maximum duration
- **WHEN** a car-originated PTT session reaches the maximum allowed capture duration
- **THEN** the system SHALL stop recording according to the same max-duration behavior used by other PTT sources
- **AND** mark virtual PTT as released

#### Scenario: Capture cannot start
- **WHEN** the car media play/pause control is activated while virtual PTT is released
- **AND** the system cannot start a valid PTT session
- **THEN** virtual PTT SHALL remain released
- **AND** the system SHALL provide error feedback through the resolved audio route when possible

### Requirement: Virtual PTT provides driver-safe feedback
The system SHALL provide visible and audible feedback for car-originated virtual PTT state transitions.

#### Scenario: Virtual PTT starts recording
- **WHEN** car-originated virtual PTT starts a capture
- **THEN** the system SHALL expose recording state through the media session playback state or metadata
- **AND** play the same ready/start feedback used by the PTT capture path

#### Scenario: Virtual PTT stops recording
- **WHEN** car-originated virtual PTT ends a capture
- **THEN** the system SHALL expose ready or finalizing state through the media session playback state or metadata
- **AND** provide audible stop or completion feedback when available

### Requirement: Virtual PTT is capture-first while car mode is active
The system SHALL treat car media play/pause as virtual PTT control while the Subspace car PTT media session is active.

#### Scenario: Media play command arrives while idle
- **WHEN** the Subspace car PTT media session is active
- **AND** virtual PTT is released
- **AND** the system receives a media play command
- **THEN** the command SHALL be interpreted as a virtual PTT press attempt

#### Scenario: Media pause command arrives while recording
- **WHEN** the Subspace car PTT media session is active
- **AND** virtual PTT is pressed
- **AND** the system receives a media pause command
- **THEN** the command SHALL be interpreted as a virtual PTT release attempt
