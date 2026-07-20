## Purpose

Modifies Channel Host Capabilities to adapt transcription, synthesis, and playback to language-neutral opaque interfaces, and support selection-aware host-routed deferred audio queues.

## MODIFIED Requirements

### Requirement: Audio capabilities use high-level opaque handles
Any host audio capability made available to a channel SHALL represent work through semantic requests and opaque, lifecycle-bound handles. The host SHALL remain the sole owner of route selection, capture acquisition, ready and error beeps, recorder state, playback routing and ordering, and route release. Channels SHALL NOT inspect, select, retain, or release platform audio devices or routes.

#### Scenario: Channel requests host audio work
- **WHEN** a channel requests an authorized semantic audio operation
- **THEN** the host SHALL return a typed result or opaque operation handle
- **AND** the host SHALL choose and manage platform audio resources without revealing them to the channel

#### Scenario: Runtime schedules deferred playback
- **WHEN** a runtime schedules a playback operation from synthesized or captured audio via `subspace.playback.schedule`
- **THEN** the scheduling operation SHALL NOT play audio through an ambient or process-wide output
- **AND** the playback capability SHALL accept the audio into the selection-aware deferred queue exactly once
- **AND** it SHALL return a successful scheduled result to Lua without returning any underlying queue token, route, or platform object

#### Scenario: PTT capture is prepared
- **WHEN** the active runtime accepts a channel-level input target
- **THEN** the host SHALL acquire and operate capture and routing through the existing host-owned audio lifecycle
- **AND** the runtime SHALL receive only high-level opaque channel audio handles and terminal events

#### Scenario: Opaque audio operation is cancelled
- **WHEN** the runtime generation closes or cancellation reaches an outstanding opaque audio operation
- **THEN** the host SHALL cancel or detach that operation according to its semantic contract
- **AND** completion after cancellation SHALL NOT publish playback or another late effect

### Requirement: Synthesis and playback are host-owned asynchronous effects
The host SHALL expose semantic synthesis and playback capabilities that accept channel-domain text or opaque audio handles, channel instance identity, and runtime generation. Synthesis and playback SHALL be allowed to complete after the originating PTT session and SHALL be scheduled independently of the PTT audio route. The host SHALL own synthesis engines, PCM handling, output admission, playback routing, serialization, cancellation, pending/heard state, and cleanup; runtimes SHALL receive only typed outcomes or opaque lifecycle-bound handles.

#### Scenario: Accepted response is synthesized after PTT
- **WHEN** a durable channel run produces final assistant text after its PTT target has released
- **THEN** the host SHALL synthesize and schedule playback through the semantic capabilities without reopening or retaining the PTT route
- **AND** the host SHALL associate the effect with the channel instance, runtime generation, and run identity

#### Scenario: Admitted playback is temporarily ineligible for physical output
- **WHEN** a playback entry has already passed bounded queue admission but another channel is selected or current selection/route policy temporarily prevents physical playback
- **THEN** the host SHALL retain that authorized entry as pending for its addressed channel
- **AND** it SHALL NOT play through an ambient or unrelated channel output
- **AND** it SHALL re-evaluate physical admission only under the host's selection and audio policy while the entry and generation remain current

#### Scenario: Synthesis or playback is revoked
- **WHEN** the owning runtime generation is retired or the operation is cancelled before playback is admitted
- **THEN** the host SHALL cancel or detach the operation according to its semantic contract
- **AND** completion after cancellation SHALL NOT produce playback, publication, or another late effect

#### Scenario: Semantic operation exceeds deadline
- **WHEN** transcription, synthesis, or deferred-queue admission does not complete before its finite host-configured deadline
- **THEN** the host SHALL atomically terminate the operation with the language-neutral timeout outcome
- **AND** it SHALL cancel or detach backend work, clean operation-specific resources, and suppress every late completion or effect

#### Scenario: Deferred queue capacity is exhausted
- **WHEN** queue admission would exceed a finite per-instance, per-generation, or global entry-count or retained-audio-byte bound
- **THEN** the host SHALL reject admission with a typed busy outcome before consuming the caller's opaque audio handle
- **AND** it SHALL NOT create a partial queue entry, reroute existing audio, or evict another channel's entry implicitly

### Requirement: Asynchronous capability effects are generation-safe
Every asynchronous capability operation and queued playback entry SHALL carry the channel instance identifier and runtime generation that authorized it. The host SHALL accept completion, publication, playback, synthesis, and tool results only while that authorization remains current, SHALL revoke it on generation retirement or close, and SHALL make revocation and terminal completion idempotent. A late completion SHALL be observable only as a normalized stale or cancelled outcome and SHALL NOT mutate current channel state or produce deferred playback.

#### Scenario: Runtime generation is replaced
- **WHEN** a channel definition change or runtime replacement retires generation G and creates generation H
- **THEN** the host SHALL revoke outstanding capability effects and queued playback entries owned by G
- **AND** operations issued by H SHALL use separate authorization even when the channel instance ID is unchanged

#### Scenario: Late completion arrives
- **WHEN** a completion from a revoked generation arrives after replacement, shutdown, or restart
- **THEN** the host SHALL reject or ignore the completion as stale
- **AND** it SHALL NOT publish a response, play audio, execute a tool, or mark a current run complete

#### Scenario: Terminal completion is delivered twice
- **WHEN** the same asynchronous operation reports a terminal result more than once
- **THEN** the host SHALL commit at most one terminal effect and one durable terminal state
- **AND** repeated completion SHALL return a typed already-completed or stale result without replaying the effect

### Requirement: No public runtime I/O modules or persistent plugin state ship in this change
This change SHALL promote the internal Lua kernel and actor mechanisms required for lifecycle and scheduling, and the capability-gated public semantic audio modules (`subspace.transcription`, `subspace.synthesis`, `subspace.playback`), but SHALL NOT expose general public runtime I/O modules (HTTP, filesystem, socket, general networking, or event-loop modules), a channel-accessible persistent key-value store, package filesystem, package installer, or package verifier. General runtime modules described by this change are a forward contract boundary and SHALL NOT be registered or exposed to plugin code. Existing capability contracts SHALL remain usable by built-in Kotlin runtimes without constructing a Lua actor.

#### Scenario: Change is applied without public runtime I/O modules
- **WHEN** the internal Lua actor runtime and channel capability platform are initialized
- **THEN** no HTTP, filesystem, socket, general networking, event-loop, or persistent plugin-state module SHALL be exposed to channel code
- **AND** no package installation or verification path SHALL be introduced

#### Scenario: Built-in runtime does not require Lua
- **WHEN** a built-in Kotlin runtime operates through the existing channel and capability boundaries
- **THEN** it SHALL function without constructing a Lua actor or Lua state
- **AND** no public runtime I/O module or persistent plugin-state capability SHALL be present
