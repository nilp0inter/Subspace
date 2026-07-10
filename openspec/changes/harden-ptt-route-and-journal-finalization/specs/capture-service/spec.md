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

### Requirement: Production capture proves PCM liveness before readiness
An opened production capture source that opts into liveness proof SHALL produce at least one nonzero PCM sample during the configured pre-commit observation window before the ready beep and channel handoff. Zero-only or unavailable PCM throughout that window SHALL be treated as recorder-path failure, not as semantic user silence and not as Android client-silencing evidence.

#### Scenario: First recorder produces only digital zero
- **WHEN** the first production recorder produces no nonzero PCM during the pre-commit observation window
- **THEN** the capture service SHALL stop the discard reader and close that recorder
- **AND** retain the already acquired audio route
- **AND** open one replacement recorder after the configured retry delay
- **AND** require the replacement recorder to prove nonzero PCM before playing the ready beep

#### Scenario: Replacement recorder proves PCM liveness
- **WHEN** the replacement recorder produces nonzero PCM within its observation window
- **THEN** the capture service SHALL play the ready beep once
- **AND** transfer only the replacement recorder into the committed capture session
- **AND** SHALL NOT release and reacquire the audio route during recovery

#### Scenario: Every permitted recorder remains zero-only
- **WHEN** every permitted recorder attempt produces only zero PCM through its observation window
- **THEN** the capture service SHALL close every opened recorder
- **AND** return recording failure before the ready beep
- **AND** SHALL NOT expose a channel-visible capture session

#### Scenario: PTT is released during recorder retry
- **WHEN** PTT is released after a failed recorder attempt or during the retry delay
- **THEN** the capture service SHALL cancel before opening or committing another recorder
- **AND** close the current recorder and release setup ownership exactly once

#### Scenario: PTT is released during the final liveness window
- **WHEN** PTT is released while the final permitted recorder is still awaiting nonzero PCM
- **AND** that recorder's observation window expires
- **THEN** the capture service SHALL recheck cancellation before reporting exhausted recorder failure
- **AND** return a cancelled setup outcome without a ready beep or channel-visible session

### Requirement: Pre-commit and committed reads use distinct blocking contracts
Production capture sources SHALL use cancellable nonblocking reads only for the pre-commit discard and liveness phase. After channel handoff, the committed capture session SHALL use the source's normal blocking read so temporarily unavailable PCM does not become a rapid empty-read loop or suppress valid live audio.

#### Scenario: Capture crosses the ready-beep boundary
- **WHEN** a production recorder proves liveness and the ready beep completes
- **THEN** the pre-commit nonblocking discard reader SHALL stop and join
- **AND** the committed session SHALL become the recorder's sole reader
- **AND** committed live frames, VU updates, and terminal PCM SHALL use blocking reads

## MODIFIED Requirements

### Requirement: Recording starts only after the ready beep completes
The production recorder MAY be opened and started before the ready beep for startup, silencing, and PCM-liveness preflight. The capture service SHALL keep that recorder behind an exclusive discard reader until beep completion. Channel-visible recording—live frames, VU updates, terminal PCM, and the running `CaptureSession`—SHALL start only after the ready beep completes and the discard reader has stopped and joined. The capture service SHALL release the SCO route on every `startSession` outcome that acquired SCO and did not hand off a running session (`Cancelled`, `RecordingFailed`, or `RecordingSilenced`); channel controllers SHALL NOT release the same route again.

#### Scenario: Channel-visible recording starts after beep completion
- **WHEN** recorder preflight succeeds, the ready beep finishes, and PTT is still held
- **THEN** the capture service SHALL stop and join the pre-commit discard reader
- **AND** create the running capture session with the opened source as its sole reader
- **AND** only post-beep samples SHALL reach live frames, VU updates, or terminal PCM

#### Scenario: PTT released during the ready beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system SHALL discard and close the preflighted source without channel-visible audio
- **AND** cancel setup
- **AND** release the SCO route through the capture service, triggering the 30-second warmup retention window
- **AND** the channel controller SHALL NOT additionally release the SCO route

#### Scenario: PTT released during SCO acquisition
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system SHALL continue SCO acquisition to completion
- **AND** SHALL NOT open the recorder or play the ready beep
- **AND** the capture service SHALL release the SCO route, triggering the 30-second warmup retention window
- **AND** the channel controller SHALL NOT additionally release the SCO route

#### Scenario: Source open fails during preflight
- **WHEN** the route is acquired, PTT remains held, and the capture source cannot be opened before the ready beep
- **THEN** the system SHALL NOT play the ready beep or create a running capture session
- **AND** the capture service SHALL release the SCO route, triggering the 30-second warmup retention window
- **AND** the channel controller SHALL NOT additionally release the SCO route
