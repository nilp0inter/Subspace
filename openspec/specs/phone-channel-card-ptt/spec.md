## Purpose

TBD. Defines phone-originated PTT from functional channel cards and its interaction with channel routing, audio route selection, and PTT session timing.

## Requirements

### Requirement: Channel-card long-press starts PTT
The system SHALL allow the user to start a PTT session by long-pressing any functional channel card on the main dashboard.

#### Scenario: Long-press starts PTT for selected channel
- **WHEN** the user long-presses a functional channel card
- **THEN** the system SHALL set that channel as the active channel
- **AND** the system SHALL start a PTT session for that channel

#### Scenario: Long-press release ends PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the user releases or cancels the press
- **THEN** the system SHALL end the PTT session

### Requirement: PTT source is independent from audio route
The system SHALL resolve the audio route for each PTT session independently from whether the session was started by the RSM or by phone channel-card long-press.

#### Scenario: Phone PTT uses RSM audio when available
- **WHEN** the user starts PTT from a phone channel-card long-press
- **AND** the actual RSM audio route is usable
- **THEN** the system SHALL play beeps and record audio through the RSM route

#### Scenario: Phone PTT uses local audio when RSM audio is unavailable
- **WHEN** the user starts PTT from a phone channel-card long-press
- **AND** the actual RSM audio route is unavailable
- **THEN** the system SHALL play beeps through the phone loudspeaker local audio route
- **AND** record audio from the phone microphone

### Requirement: Phone-originated PTT preserves session timing
The system SHALL apply the same PTT session timing semantics to phone-originated PTT sessions as it applies to RSM-originated PTT sessions.

#### Scenario: Recording starts after ready beep
- **WHEN** a phone-originated PTT session starts on a ready channel
- **THEN** the system SHALL play the ready beep on the resolved audio route
- **AND** start recording only after the ready beep completes and the press is still held

#### Scenario: Release during ready beep cancels recording
- **WHEN** a phone-originated PTT session is playing the ready beep
- **AND** the user releases or cancels the press before the beep completes
- **THEN** the system SHALL NOT start recording

#### Scenario: Max duration applies to phone PTT
- **WHEN** a phone-originated PTT session remains held beyond the maximum capture duration
- **THEN** the system SHALL stop recording according to the same max-duration behavior used by RSM-originated PTT

### Requirement: Phone-originated PTT supports every functional channel
The system SHALL make phone channel-card PTT available for every functional channel that supports PTT behavior through the RSM path.

#### Scenario: Functional channel long-press is supported
- **WHEN** a functional channel card is visible on the main dashboard
- **THEN** the user SHALL be able to long-press that card to start a PTT session for that channel
