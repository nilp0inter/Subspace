## Purpose

TBD. Defines durable asynchronous channel messages and runs: persisted records, per-channel serialization, restart recovery without context restoration, volatile conversation isolation, monotonic pending/heard state, selection-aware delayed playback, generic projections, and exclusion of per-call approval and camera behavior.

## Requirements

### Requirement: Channel message and run records are durable
The host SHALL persist an ordered, channel-instance-scoped record for every accepted user turn, outbound model request, and resulting assistant response. Each record SHALL have a stable message identity, direction, lifecycle state, and association with its run; each run SHALL have a stable run identity, ordered channel association, processing state, and terminal outcome. The host SHALL persist a transcribed user turn before acknowledging it to the originating input operation and SHALL persist each outbound request envelope before attempting remote submission. It SHALL retain records needed to recover queued, active, and completed work after process or service interruption.

#### Scenario: A released PTT turn is accepted
- **WHEN** local transcription produces a valid user turn for an agent channel
- **THEN** the host SHALL durably record that turn before reporting acceptance to the PTT operation
- **AND** the record SHALL identify the channel instance and a new run
- **AND** the run SHALL enter the channel's durable processing order

#### Scenario: An assistant response arrives asynchronously
- **WHEN** a remote completion produces assistant text for a run after the originating PTT callback has returned
- **THEN** the host SHALL durably record the response against the same channel instance and run
- **AND** the response SHALL remain available to channel projections independently of the PTT session that created the turn

#### Scenario: An outbound request is submitted
- **WHEN** the host prepares a model request for a durable run
- **THEN** it SHALL persist the outbound request envelope and its run association before attempting remote submission
- **AND** restart recovery SHALL use that record to distinguish an unsubmitted request from one whose remote effect is unproven

#### Scenario: A process stops during agent work
- **WHEN** the service or process stops while a turn, run, or response is queued or active
- **THEN** durable records SHALL remain available after restart
- **AND** the host SHALL NOT require the originating PTT callback or audio route to reconstruct the work

### Requirement: Turns are queued and runs are serialized per channel
The host SHALL accept later user turns while an earlier run for the same channel is queued, running, or waiting for a tool result. It SHALL preserve acceptance order and SHALL process at most one run at a time for each channel instance. A queued turn SHALL remain durable until its run reaches a terminal state, and a failed or cancelled run SHALL NOT reorder or silently discard later accepted turns.

#### Scenario: A second turn arrives while the first run is active
- **WHEN** a user releases PTT for a channel whose prior run has not reached a terminal state
- **THEN** the host SHALL durably enqueue the new user turn
- **AND** the host SHALL not start its run concurrently with the prior run
- **AND** the second run SHALL use the channel's acceptance order after the first run completes or terminates

#### Scenario: The first run fails
- **WHEN** the earliest queued run reaches a failed, cancelled, or indeterminate terminal outcome
- **THEN** the host SHALL retain the next accepted turn in queue order
- **AND** it SHALL process that turn without requiring the user to resubmit it

#### Scenario: Multiple channels have queued work
- **WHEN** more than one channel instance has queued turns
- **THEN** each channel's order SHALL be preserved independently
- **AND** work for one channel SHALL NOT cause another channel's queued turn to be dropped or reordered

### Requirement: Restart recovery reconciles durable work without restoring conversation context
On restart, the host SHALL recover durable user turns, runs, assistant responses, queue order, and pending/heard state. It SHALL reconcile any nonterminal run or outbound operation into a defined recoverable, failed, cancelled, or indeterminate state before accepting new work. Recovery SHALL preserve the run and message identities and SHALL NOT silently duplicate a terminal response. Persisted messages SHALL remain a record and SHALL NOT automatically become model conversation context after restart.

#### Scenario: Queued work is recovered
- **WHEN** the host restarts with a queued user turn
- **THEN** the turn SHALL remain queued for its channel
- **AND** the host SHALL resume it in order without creating a duplicate user turn or run

#### Scenario: A run was active at interruption
- **WHEN** restart finds a run recorded as active or waiting for a remote/tool completion
- **THEN** the host SHALL reconcile it using its durable run envelope and recorded completion evidence
- **AND** it SHALL either resume only work proven not to have been submitted, or mark the run failed or indeterminate without replaying an unproven effect
- **AND** it SHALL expose the resulting state through the generic channel projection

#### Scenario: A terminal response was persisted before interruption
- **WHEN** restart finds a response already recorded as terminal
- **THEN** the host SHALL retain exactly one response for that run
- **AND** recovery SHALL not issue another completion request solely because playback or projection had not yet finished

#### Scenario: Restart creates a new runtime generation
- **WHEN** the service restarts after durable agent messages exist
- **THEN** the channel SHALL start a new volatile conversation for its new runtime generation
- **AND** the host SHALL not preload prior message text as model context unless a later explicit product contract adds that behavior

### Requirement: Volatile conversation state is distinct from durable history
While a channel runtime generation remains alive, an agent channel SHALL maintain one volatile conversation per channel instance and SHALL use it to preserve context across that channel's sequential turns. The volatile conversation SHALL be discarded when the service or runtime generation restarts, when the channel's relevant configuration is replaced, or when SOS resets the channel. Discarding volatile context SHALL NOT delete durable messages, runs, responses, or their pending/heard state.

#### Scenario: Sequential turns share live context
- **WHEN** two accepted turns for the same channel are processed during one runtime generation
- **THEN** the second model request SHALL use the first turn and its assistant/tool outcomes from that live volatile conversation
- **AND** the host SHALL keep the conversation separate from other channel instances

#### Scenario: SOS is pressed
- **WHEN** SOS is pressed for an agent channel
- **THEN** the host SHALL discard that channel's volatile conversation immediately
- **AND** subsequent turns SHALL start a fresh conversation
- **AND** existing durable records SHALL remain available for status and playback

#### Scenario: Channel configuration changes
- **WHEN** the channel's relevant provider configuration is replaced
- **THEN** the host SHALL discard the old volatile conversation before processing a turn under the new configuration
- **AND** it SHALL retain prior durable records under their original channel and run identities

### Requirement: Pending and heard state is durable and monotonic
Every inbound assistant response that is eligible for playback SHALL have durable pending/heard state. A response SHALL begin as pending when it has not completed playback or an explicit skip action, SHALL be counted as unheard in channel projections while pending, and SHALL transition to heard at most once after playback completes or the user explicitly skips it. Restart and projection refresh SHALL preserve this state and SHALL NOT make a heard response pending again.

#### Scenario: A response is awaiting playback
- **WHEN** an assistant response is persisted and no playback completion or skip has been recorded
- **THEN** its state SHALL be pending
- **AND** the channel projection SHALL report it as an unheard response

#### Scenario: Playback completes
- **WHEN** host-owned playback completes for a pending response
- **THEN** the host SHALL mark that response heard exactly once
- **AND** subsequent projections SHALL exclude it from the pending count

#### Scenario: The user skips a response
- **WHEN** the user explicitly skips a pending response
- **THEN** the host SHALL mark it heard without replaying it
- **AND** the next pending response, if any, SHALL retain its own pending state

#### Scenario: Restart occurs before playback
- **WHEN** the service restarts while a response is pending or playing
- **THEN** durable recovery SHALL preserve a heard response as heard when its heard transition is already committed
- **AND** recovery SHALL leave a response pending when that transition is not committed, including interrupted playback
- **AND** a response whose heard transition is already committed SHALL NOT be replayed automatically

### Requirement: Delayed playback follows current channel selection
The host SHALL schedule response synthesis and playback independently of the originating PTT session. An arriving response SHALL play immediately only when its channel instance is still selected and the audio subsystem admits playback. Otherwise the response SHALL remain pending and SHALL play in channel order when the user returns to that channel and playback is admitted. Selection changes SHALL NOT redirect a response to another channel.

#### Scenario: Response arrives on the selected channel
- **WHEN** a response becomes playable while its channel instance is selected and the host can admit playback
- **THEN** the host SHALL begin playback without waiting for a new PTT session
- **AND** it SHALL associate the playback with that response's channel and run

#### Scenario: Response arrives on an unselected channel
- **WHEN** a response becomes playable while another channel instance is selected
- **THEN** the host SHALL persist it as pending
- **AND** it SHALL NOT play it on the selected channel

#### Scenario: User returns to a channel with pending responses
- **WHEN** the user selects a channel with one or more pending responses and playback is admitted
- **THEN** the host SHALL play pending responses in their durable response order
- **AND** each response SHALL transition to heard only after its playback completion or explicit skip

#### Scenario: Playback admission is temporarily unavailable
- **WHEN** a response's channel is selected but the audio subsystem cannot admit playback
- **THEN** the host SHALL retain the response as pending
- **AND** it SHALL retry admission according to host policy without requiring another user turn or replaying a response already marked heard

### Requirement: Durable operations expose generic channel projections
The host SHALL project channel-scoped processing status, queued-turn count, pending-unheard response count, and the identities or summaries needed to observe recovery through a language-neutral channel contract. Projections SHALL be safe for phone and vehicle surfaces and SHALL NOT expose provider SDK request/response objects, platform audio objects, transport objects, credentials, or message secrets beyond the product's channel-summary policy.

#### Scenario: A channel is processing
- **WHEN** a channel has a queued, running, waiting-for-tool, failed, cancelled, or indeterminate run
- **THEN** its generic projection SHALL identify the processing state and queued work without requiring a surface to inspect provider implementation types

#### Scenario: Pending count changes
- **WHEN** an assistant response becomes pending or heard
- **THEN** the host SHALL publish an updated channel projection with the corresponding pending-unheard count
- **AND** the count SHALL remain associated with the response's stable channel identity across restart

#### Scenario: A surface observes an unavailable provider
- **WHEN** a channel provider cannot currently process durable work
- **THEN** the projection SHALL expose a typed unavailable or failed state
- **AND** it SHALL retain queued and pending records for later host-owned recovery rather than exposing SDK or platform failure objects

### Requirement: Current asynchronous behavior excludes per-call approval and camera behavior
The current asynchronous message capability SHALL NOT pause an accepted run to request per-call user authorization, and it SHALL NOT expose camera capture, camera state, or a camera tool. Tool execution authorization, when configured for a channel, SHALL be determined by channel configuration rather than an interactive approval step for each call.

#### Scenario: A configured run requests an enabled tool
- **WHEN** an accepted run requests a tool enabled by its channel configuration
- **THEN** the host SHALL continue through automatic execution without presenting a per-call approval gate

#### Scenario: A run requests an unsupported capability
- **WHEN** a run requests a camera or another capability not exposed by the channel configuration
- **THEN** the host SHALL not execute that capability
- **AND** the run SHALL receive a generic unsupported or failed outcome rather than camera access