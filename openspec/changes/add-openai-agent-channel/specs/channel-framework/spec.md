## ADDED Requirements

### Requirement: Durable channel operations outlive PTT terminal callbacks
The channel framework SHALL provide an explicit host-owned durable-operation boundary through which a runtime can enqueue a channel-domain user turn or other accepted work before its transient PTT target terminates. Completion of the PTT target and release of its audio route SHALL NOT cancel accepted durable work. Durable work SHALL be identified by channel instance, runtime generation, and run identity and SHALL NOT retain callbacks, recorder state, route leases, or transient audio handles.

#### Scenario: User turn is accepted before terminal callback
- **WHEN** a channel runtime submits a user turn while handling a committed PTT input and the host accepts it
- **THEN** the framework SHALL record or enqueue the turn as durable channel work
- **AND** the PTT terminal callback SHALL be permitted to complete and release all transient input resources
- **AND** processing SHALL continue through host-owned operations after that callback returns

#### Scenario: PTT callback is cancelled after enqueue
- **WHEN** the originating PTT callback is cancelled, times out, or reports a terminal failure after durable work was accepted
- **THEN** the accepted durable work SHALL retain its channel instance and run identity
- **AND** the framework SHALL NOT restart capture, retain the route, or invoke the cancelled callback again

#### Scenario: Channel selection changes after enqueue
- **WHEN** the active channel changes after a user turn has been enqueued
- **THEN** the durable work SHALL remain addressed to its original channel instance
- **AND** it SHALL NOT be redirected to the newly selected channel or use the new channel's runtime implicitly

### Requirement: Durable channel work is serialized per channel instance
The framework SHALL serialize accepted user turns and their response-processing stages for each channel instance in deterministic queue order. A queued turn SHALL NOT begin a conflicting remote run, tool loop, synthesis operation, or response publication concurrently with an earlier turn for that same channel unless the durable operation contract explicitly permits that stage. Queues for different channel instances SHALL remain independently schedulable.

#### Scenario: Multiple turns arrive for one channel
- **WHEN** several released PTT recordings produce accepted turns for one channel instance
- **THEN** the framework SHALL persist their queue order and process them one at a time through the channel's serialized conversation boundary
- **AND** a later turn SHALL remain queued rather than racing the earlier turn

#### Scenario: Turns target different channels
- **WHEN** accepted turns are queued for two different channel instances
- **THEN** each channel's queue SHALL retain its own order and state
- **AND** work for one channel SHALL NOT mutate, reorder, or consume the other channel's queue

### Requirement: Durable responses publish independently of the originating input session
The framework SHALL allow a host-owned channel operation to publish an inbound response, processing state, pending state, or normalized failure after the originating input session has terminated. Publication SHALL use semantic channel projections and asynchronous synthesis/playback capabilities rather than PTT terminal return values or ambient output. Selection, playback admission, heard state, and delayed delivery SHALL remain host-owned policy.

#### Scenario: Response arrives while channel is selected
- **WHEN** a durable response completes for the currently selected channel and host audio playback is admissible
- **THEN** the framework SHALL publish the response and SHALL permit host-owned synthesis and playback without a new PTT session
- **AND** the runtime SHALL NOT access the audio route or output device

#### Scenario: Response arrives while channel is not selected
- **WHEN** a durable response completes for a channel that is not currently selected or whose output is busy
- **THEN** the framework SHALL publish a pending response state for that channel
- **AND** playback SHALL be deferred until the host selection and admission policy permits it
- **AND** returning to the channel SHALL NOT require replaying the original PTT callback

#### Scenario: Response publication is cancelled
- **WHEN** the operation generation is revoked before a response is published or played
- **THEN** the framework SHALL suppress the stale publication and playback effect
- **AND** it SHALL retain only the normalized durable ledger outcome permitted by the run contract

### Requirement: Runtime callbacks do not own asynchronous orchestration
A channel runtime SHALL request durable work, transcription, remote completion, tool execution, synthesis, and playback only through explicit semantic host capabilities. The runtime SHALL NOT create process-global scopes, launch untracked threads, own network clients, retain PTT callbacks, or implement retries, persistence, playback routing, or transport cleanup. The host SHALL expose typed acceptance, progress, terminal, cancelled, failed, and indeterminate outcomes.

#### Scenario: Runtime requests asynchronous work
- **WHEN** a runtime needs to continue channel processing after an input event
- **THEN** it SHALL submit a semantic operation with explicit channel and generation identity
- **AND** the host SHALL own scheduling, durable state, cancellation, and effect delivery
- **AND** no ambient coroutine, thread, SDK client, or platform resource SHALL be injected into the runtime

#### Scenario: Host operation fails
- **WHEN** a host-owned asynchronous stage fails
- **THEN** the framework SHALL deliver a normalized semantic failure associated with the run identity
- **AND** unrelated channel instances and their queues SHALL remain operational

### Requirement: Durable framework contracts are language-neutral
The framework SHALL define durable operation requests, lifecycle events, run identities, channel projections, and typed outcomes using language-neutral host-domain values and opaque handles. It SHALL NOT expose Kotlin classes, Android callbacks, Compose state, OpenAI SDK types, audio-route objects, or transport objects, and it SHALL remain implementable by built-in Kotlin providers without introducing a Lua engine or script-runtime ABI.

#### Scenario: Future language adapter uses the framework
- **WHEN** a future language adapter submits a valid durable operation and consumes its semantic events
- **THEN** the host SHALL enforce the same queue, generation, cancellation, publication, and failure rules
- **AND** the adapter SHALL NOT require access to Kotlin, Android, SDK, or transport implementation types

#### Scenario: Platform implementation changes
- **WHEN** the host replaces its SDK, transcription engine, synthesis engine, or playback implementation
- **THEN** durable channel operation contracts and persisted channel-domain envelopes SHALL remain stable
- **AND** no platform implementation object SHALL become part of a provider or runtime contract
