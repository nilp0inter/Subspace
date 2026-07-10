## ADDED Requirements

### Requirement: Non-Work problem feedback honors route-release gates
When a PTT request selects a non-Work route but cannot start capture because the selected channel is unavailable or refuses input, the system SHALL await the resolved route's route gate before playing problem feedback. It SHALL NOT play non-Work feedback while a prior Work/RSM route is still observed as active.

#### Scenario: Warm Work route precedes unavailable phone channel
- **WHEN** a warm Work/RSM route exists
- **AND** phone PTT selects On-a-pinch
- **AND** the selected channel cannot accept input
- **THEN** the system SHALL await observed Work-route release before attempting local problem feedback
- **AND** SHALL not retain the RSM communication device solely because capture was refused

#### Scenario: Work-route release gate fails before feedback
- **WHEN** a non-Work problem-feedback attempt cannot observe Work-route release before its configured timeout
- **THEN** the system SHALL not play feedback through the stale route
- **AND** SHALL perform the resolved route cleanup contract without leaving a new route lease behind

## RENAMED Requirements

- FROM: `### Requirement: Ready beep plays at SCO activation instant with reliable audible output`
- TO: `### Requirement: Ready beep follows SCO and recorder preflight with reliable audible output`

## MODIFIED Requirements

### Requirement: Ready beep follows SCO and recorder preflight with reliable audible output
The system SHALL play the ready beep only after the SCO route is active and the opened recorder has passed required startup, silencing, and PCM-liveness preflight. The beep output SHALL be audibly routed through the SCO headset and SHALL mark the boundary after which captured PCM may become channel-visible.

#### Scenario: Cold-start SCO ready beep after recorder preflight
- **WHEN** SCO was inactive, the system acquires SCO successfully, and recorder preflight succeeds while PTT remains held
- **THEN** the system SHALL route the AudioTrack explicitly to the SCO communication device via `setPreferredDevice`
- **AND** send a priming PCM buffer through the SCO route before the beep waveform to establish the link-layer SCO packet stream
- **AND** play the ready beep after recorder preflight without exposing pre-beep capture PCM
- **AND** the user SHALL hear the beep through the Bluetooth headset

#### Scenario: Warm-start SCO ready beep after recorder preflight
- **WHEN** SCO remains active from a previous warm session and recorder preflight succeeds while PTT remains held
- **THEN** the system SHALL play the ready beep through the SCO device route without cold-start output priming
- **AND** SHALL NOT expose pre-beep capture PCM

### Requirement: Recording always starts after ready beep completes
The production recorder MAY already be running behind the pre-commit discard boundary for readiness proof, but channel-visible recording SHALL start only after ready-beep playback has fully completed and the discard reader has stopped and joined.

#### Scenario: Beep completes before channel-visible recording starts
- **WHEN** the ready beep finishes playing and PTT is still held
- **THEN** the system SHALL hand the opened Bluetooth SCO microphone source to the committed capture session
- **AND** only post-beep PCM SHALL reach live frames, VU state, or terminal recording
- **AND** the system SHALL report status transition: `Beeping → Recording`

#### Scenario: PTT is released during beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system SHALL NOT create a channel-visible recording session
- **AND** SHALL discard and close the preflighted recorder
- **AND** cancel setup
- **AND** retain the warm SCO route through the capture service's release contract

#### Scenario: PTT is released during SCO acquisition (short tap)
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system SHALL continue SCO acquisition to completion
- **AND** SHALL NOT open a recorder or play the ready beep
- **AND** SHALL retain the SCO route warm for 30 seconds after capture-service cleanup
