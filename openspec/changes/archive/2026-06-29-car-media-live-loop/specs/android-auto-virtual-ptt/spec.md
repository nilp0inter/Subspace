## MODIFIED Requirements

### Requirement: Virtual PTT provides driver-safe feedback
The system SHALL provide visible and audible feedback for car-originated virtual PTT state transitions, with the visible feedback projected onto the Android Auto Media now-playing card as a contract combining the active channel's identity, the live PTT state pill, and a compact pending-backlog summary.

#### Scenario: Virtual PTT starts recording
- **WHEN** car-originated virtual PTT starts a capture
- **THEN** the system SHALL expose recording state through the media session playback state
- **AND** the now-playing metadata title SHALL be the active channel's display name
- **AND** the now-playing metadata subtitle SHALL include a recording state pill
- **AND** the now-playing bitmap SHALL be the recording-state tinted bitmap from the Subspace visual identity palette
- **AND** the system SHALL play the same ready/start feedback used by the PTT capture path

#### Scenario: Virtual PTT stops recording with a response
- **WHEN** car-originated virtual PTT ends a capture and response audio is available
- **THEN** the system SHALL expose finalizing state through the media session playback state
- **AND** the now-playing metadata subtitle SHALL include a finalizing state pill
- **AND** the now-playing bitmap SHALL be the finalizing-state tinted bitmap from the Subspace visual identity palette
- **AND** the system SHALL play the response via the A2DP path per the on-the-road PTT session spec
- **AND** the system SHALL provide audible completion feedback after playback when available

#### Scenario: Virtual PTT stops recording without a response
- **WHEN** car-originated virtual PTT ends a capture and no response audio is available
- **THEN** the system SHALL expose ready state through the media session playback state
- **AND** the now-playing metadata subtitle SHALL include an active or ready state pill
- **AND** the now-playing bitmap SHALL be the ready-state tinted bitmap
- **AND** the system SHALL not imply pending audio when none exists

#### Scenario: Now-playing subtitle conveys pending backlog
- **WHEN** the active channel has pending unheard messages greater than zero
- **THEN** the now-playing metadata subtitle SHALL append a compact pending summary such as "<count> pending"
- **WHEN** an inactive channel has pending unheard messages greater than zero
- **THEN** the now-playing metadata subtitle MAY append a compact per-channel pending summary
- **AND** the subtitle SHALL remain under 40 characters and SHALL truncate the pending portion first

#### Scenario: NotReady state surfaces without implying capture capability
- **WHEN** the live PTT state is NotReady (permissions missing, headset audio unavailable, or active channels not ready)
- **THEN** the now-playing metadata title SHALL still reflect the active channel's display name when one is selected
- **AND** the subtitle SHALL include a not-ready state pill
- **AND** the now-playing bitmap SHALL be the not-ready tinted bitmap
- **AND** the system SHALL NOT imply that a capture can start