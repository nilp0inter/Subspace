## ADDED Requirements

### Requirement: Work playback owns an explicit target-RSM SCO lease
A Work-mode channel-content playback operation SHALL acquire its own target-RSM-owned HFP/SCO route lease after host playback admission and before writing PCM. An existing warm SCO transport MAY make acquisition immediate, but warmth alone SHALL NOT authorize playback or substitute for an operation lease.

#### Scenario: Work route remains warm after capture
- **WHEN** Work capture has released its client lease but the target RSM SCO transport remains warm
- **AND** channel-content playback is admitted in Work mode
- **THEN** playback SHALL acquire a new Work route client lease before writing PCM
- **AND** it SHALL release that lease exactly once after playback cleanup

#### Scenario: Work playback is requested while capture owns SCO
- **WHEN** Work capture still owns its route lease
- **AND** a response is pending for Work playback
- **THEN** host half-duplex admission SHALL prevent playback route acquisition
- **AND** the response SHALL remain pending rather than sharing the capture lease

#### Scenario: Work playback cannot prove target ownership
- **WHEN** Work playback admission begins but target RSM HFP ownership or SCO transport cannot be proven
- **THEN** playback route acquisition SHALL fail closed
- **AND** no PCM or rejection feedback SHALL be played through car, phone, or an anonymous unproven endpoint
- **AND** the response SHALL remain pending and unheard

### Requirement: Active Work playback supplies rejection feedback through its owned stream
While Work response playback owns the target-RSM route, a PTT rejection beep SHALL be mixed by the active playback operation through that same route and SHALL NOT acquire another SCO client or modify capture route ownership.

#### Scenario: RSM PTT is rejected during Work playback
- **WHEN** the target RSM is playing a response under an owned Work playback lease
- **AND** PTT is pressed
- **THEN** the active stream SHALL duck speech and overlay the rejection beep
- **AND** SCO client ownership SHALL remain with the same playback operation until normal completion or explicit skip
