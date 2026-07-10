## ADDED Requirements

### Requirement: Channel-visible PCM begins after ready beep completion
When capture preflight starts a recorder before the ready beep to prove recorder readiness, the capture service SHALL discard all pre-commit PCM until ready-beep playback has completed. Neither live channel frames nor terminal `RecordedPcm` SHALL contain samples captured before ready-beep completion.

#### Scenario: Recorder is preflighted before ready beep
- **WHEN** the selected capture source must start recording before the ready beep to prove startup readiness
- **THEN** the capture service SHALL drain and discard pre-commit source data while the ready beep plays
- **AND** start channel-visible frame delivery only after the ready beep completes
- **AND** terminal recorded PCM SHALL contain only post-beep samples

#### Scenario: PTT is released during pre-commit drain
- **WHEN** PTT is released while recorder preflight or pre-commit draining is active
- **THEN** the capture service SHALL discard the opened source without delivering frames or terminal PCM
- **AND** return a cancelled setup outcome

### Requirement: Explicit recorder silencing rejects capture before commitment
The capture service SHALL reject an opened recorder before ready beep and channel handoff when Android explicitly reports the app's recording client as silenced. Recorder configuration that is unavailable or does not report silencing SHALL remain unknown rather than being treated as proof of silence or proof of readiness.

#### Scenario: Android reports client silenced
- **WHEN** an opened production recorder reports its client as silenced
- **THEN** the capture service SHALL close the recorder
- **AND** return `RecordingSilenced` before ready-beep playback
- **AND** the audio input subsystem SHALL not start the selected channel

#### Scenario: Android does not expose silencing evidence
- **WHEN** the opened recorder has no active recording configuration or no silencing value
- **THEN** the capture service SHALL retain that evidence as unknown
- **AND** SHALL NOT infer silencing from empty or quiet PCM
