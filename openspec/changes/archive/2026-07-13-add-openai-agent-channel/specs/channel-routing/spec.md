## ADDED Requirements

### Requirement: Selection-aware asynchronous response playback
The host-owned channel router SHALL admit a synthesized assistant response for playback independently of the PTT session that produced its user turn. Admission SHALL require that the response's channel instance is the current active channel and that the audio subsystem accepts a playback operation. A response that is not admitted SHALL remain pending and unheard in the durable channel message projection rather than being discarded or played on an unrelated channel.

#### Scenario: Response arrives for the selected channel
- **WHEN** a synthesized assistant response arrives for the current active channel
- **AND** the audio subsystem admits a playback operation
- **THEN** the router SHALL schedule playback immediately without opening a new PTT input session
- **AND** the response SHALL remain associated with its channel instance

#### Scenario: Response arrives for an unselected channel
- **WHEN** a synthesized assistant response arrives for a channel instance that is not active
- **THEN** the router SHALL retain the response as pending and unheard
- **AND** it SHALL NOT start playback for that response
- **AND** it SHALL NOT change the active channel

#### Scenario: Selected-channel playback is blocked
- **WHEN** a synthesized assistant response arrives for the active channel
- **AND** the audio subsystem cannot admit playback because another host-owned audio operation has contention
- **THEN** the router SHALL retain the response as pending and unheard
- **AND** it SHALL enqueue the response for a later admission attempt

#### Scenario: User returns to a channel with pending responses
- **WHEN** the user selects a channel instance with one or more pending unheard responses
- **AND** the audio subsystem admits playback
- **THEN** the router SHALL schedule that channel's pending responses in arrival order
- **AND** each response SHALL remain unheard until its playback completes or the user explicitly skips it

#### Scenario: Channel changes before queued admission
- **WHEN** a response is waiting for playback admission
- **AND** the active channel changes before playback starts
- **THEN** the router SHALL cancel that response's pending playback admission
- **AND** retain the response as pending and unheard for the next selection of its channel
#### Scenario: Playback is interrupted before completion
- **WHEN** an admitted response playback is interrupted or cancelled before completion
- **AND** the user has not explicitly skipped that response
- **THEN** the router SHALL retain the response as pending and unheard
- **AND** it SHALL NOT commit the response as heard
- **AND** a later playback attempt SHALL use the durable response order when its channel is selected and audio is admitted

#### Scenario: Heard response is not replayed
- **WHEN** a response playback completes or the user explicitly skips that response
- **THEN** the router SHALL commit the response as heard
- **AND** it SHALL NOT enqueue that same response for automatic replay

### Requirement: Asynchronous response playback is serialized under audio contention
The host-owned playback scheduler SHALL serialize assistant response playback with other host-owned output operations. It SHALL NOT overlap two response playbacks, interrupt an admitted response solely because another channel becomes active, or drop a response because output is temporarily occupied. Each channel's response order SHALL be FIFO by durable response arrival order.

#### Scenario: Output is occupied by another operation
- **WHEN** a selected channel response is ready for playback
- **AND** another host-owned output operation currently owns the audio admission
- **THEN** the scheduler SHALL leave the response queued
- **AND** it SHALL attempt admission after the occupying operation releases output
- **AND** it SHALL preserve the response's channel and arrival order

#### Scenario: Multiple responses arrive for one channel
- **WHEN** multiple synthesized responses arrive for the same channel before playback can complete
- **THEN** the scheduler SHALL queue them in durable arrival order
- **AND** it SHALL play at most one response at a time
- **AND** it SHALL mark no response heard before that response's playback completes or is explicitly skipped

#### Scenario: Responses for different channels contend
- **WHEN** pending responses exist for more than one channel instance
- **THEN** the scheduler SHALL consider only the current active channel for new playback admission
- **AND** responses belonging to inactive channels SHALL remain pending and unheard
- **AND** selecting another channel SHALL make that channel's pending queue eligible without reordering responses within it

### Requirement: Channel routing publishes asynchronous response projections
The host-owned routing projection SHALL identify the active channel instance, per-channel queued user-turn count, processing state, pending unheard response count, and playback state without exposing SDK request/response objects, Android UI objects, audio-route objects, or transport objects. Processing state SHALL distinguish at least queued work, active work, waiting for a client tool, synthesis, pending response, completed idle state, and error state sufficiently for surfaces to render host-owned status.

#### Scenario: User turn or run is processing
- **WHEN** a released user turn is queued or its asynchronous run is transcribing, running, waiting for a tool, or synthesizing
- **THEN** the channel projection SHALL identify that channel as processing
- **AND** the projection SHALL retain the channel's stable instance ID
- **AND** no surface SHALL need to inspect a runtime, SDK, or transport object to render the status
#### Scenario: Queued user turns are visible
- **WHEN** one or more released user turns are waiting to begin asynchronous processing
- **THEN** the channel projection SHALL expose their queued count
- **AND** the count SHALL decrease only when each queued turn enters processing or a host-owned terminal state
- **AND** no surface SHALL need to inspect a runtime, SDK, or transport object to render the status

#### Scenario: Response becomes pending or heard
- **WHEN** a synthesized assistant response cannot be played immediately
- **THEN** the projection SHALL increment that channel's pending unheard response count
- **WHEN** playback completes or the user explicitly skips the response
- **THEN** the projection SHALL mark that response heard
- **AND** the pending unheard response count SHALL decrease accordingly

#### Scenario: Projection changes
- **WHEN** active selection, processing state, pending count, or playback state changes
- **THEN** the router SHALL publish a new host-owned projection
- **AND** phone and Android Auto consumers SHALL be able to refresh without accessing channel implementation internals
