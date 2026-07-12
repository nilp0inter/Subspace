## ADDED Requirements

### Requirement: Capture contention retains asynchronous responses pending
The host channel router SHALL treat every reserved, acquiring, recording, finalizing, or releasing capture operation as unavailable output admission. Selected-channel responses that become ready during that interval SHALL accumulate durably in channel FIFO order and SHALL remain pending and unheard until capture cleanup completes and the host re-admits playback.

#### Scenario: Selected response arrives while recording
- **WHEN** a response arrives for the active channel while any PTT input operation owns host audio
- **THEN** the router SHALL retain the response pending and unheard
- **AND** it SHALL retry admission after the existing capture terminal-completion notification

#### Scenario: Responses arrive for several channels during recording
- **WHEN** multiple channels gain pending responses while capture owns host audio
- **THEN** the router SHALL preserve each channel's durable response order
- **AND** after capture it SHALL consider only the then-active channel for playback admission

### Requirement: Explicit SOS skip pauses automatic playback for that channel
When host playback control reports that RSM SOS explicitly skipped the active response, the router SHALL mark only that response heard, preserve later responses pending, and set a paused-drain state for that channel. Scheduler wakeups other than deliberate same-channel reselection SHALL NOT clear the pause.

#### Scenario: SOS skips the FIFO head
- **WHEN** response A is playing and responses B and C are pending for the same channel
- **AND** RSM SOS skips A
- **THEN** A SHALL transition to heard by explicit skip
- **AND** B and C SHALL remain pending in order
- **AND** B SHALL NOT start automatically after A's route cleanup

#### Scenario: Audio availability changes while paused
- **WHEN** a paused channel's selected-mode endpoint disconnects and later reconnects
- **THEN** the router SHALL leave the queue paused
- **AND** route recovery SHALL NOT itself admit playback

### Requirement: Shared channel reselection resumes a paused queue
The shared active-channel selection operation SHALL distinguish deliberate reselection from passive projection refresh. Deliberately selecting the already-active paused channel through phone, RSM, or car controls SHALL clear only that channel's paused-drain state and request FIFO playback admission.

#### Scenario: Phone reselects the active channel
- **WHEN** the phone surface deliberately selects the already-active channel whose queue is paused
- **THEN** the router SHALL resume that channel's queue
- **AND** pending responses SHALL become eligible without changing channel order

#### Scenario: RSM or car reselects the active channel
- **WHEN** an RSM or car control deliberately selects the already-active paused channel
- **THEN** the same shared reselection behavior SHALL resume the queue
- **AND** no control-surface-specific playback path SHALL be used

#### Scenario: Catalogue projection repeats active identity
- **WHEN** a passive state refresh republishes the same active channel identity
- **THEN** the router SHALL NOT interpret it as deliberate reselection
- **AND** it SHALL leave paused-drain state unchanged
