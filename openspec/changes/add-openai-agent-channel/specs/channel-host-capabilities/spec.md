## ADDED Requirements

### Requirement: Host owns language-neutral asynchronous agent operations
The host SHALL expose remote-agent operations through semantic, language-neutral capability contracts that accept host-domain profile identifiers, model identifiers, messages, and tool envelopes. The host SHALL resolve the global profile endpoint and credential, own the protocol client, request serialization, networking, retries, cancellation, and durable run correlation, and return typed asynchronous operation results. Provider and runtime code SHALL NOT receive credentials, HTTP clients, SDK request or response types, JSON transport models, Android objects, or connection policy.

#### Scenario: Runtime submits a completion
- **WHEN** a live channel runtime submits a validated user turn with a selected profile and model through the agent capability
- **THEN** the host SHALL accept or reject the request with a typed semantic result and SHALL run accepted work asynchronously
- **AND** the runtime SHALL receive only host-domain run state, messages, tool envelopes, or normalized failures
- **AND** the runtime SHALL NOT receive the resolved endpoint, bearer credential, SDK object, or transport connection

#### Scenario: Profile cannot provide a completion
- **WHEN** the selected profile is missing, disabled, invalid, or unavailable
- **THEN** the capability SHALL return a typed unavailable result without starting a network request
- **AND** the host SHALL preserve the profile identifier and SHALL NOT substitute another profile or model

#### Scenario: Completion outlives the input callback
- **WHEN** a completion is accepted before the originating PTT terminal callback returns
- **THEN** the host-owned operation SHALL continue or reach a terminal durable result after that callback has completed
- **AND** it SHALL NOT retain the PTT callback, recording route, recorder, or transient audio handle

### Requirement: Model discovery is exposed as a host-domain capability
The host SHALL expose asynchronous model discovery as a semantic operation scoped to one global connection-profile identifier. Discovery results SHALL contain only stable model identifiers and host-domain display metadata or typed availability errors. The capability SHALL own endpoint access, credentials, SDK pagination and parsing, caching, cancellation, and refresh policy, and SHALL NOT expose protocol-client or platform objects to providers, runtimes, or configuration surfaces.

#### Scenario: Profile models are discovered
- **WHEN** a configuration surface or provider requests models for an available profile
- **THEN** the host SHALL return a typed asynchronous result containing the models discovered for that profile
- **AND** each model choice SHALL remain associated with that profile identifier
- **AND** the result SHALL contain no SDK request, SDK response, credential, or transport object

#### Scenario: Discovery fails
- **WHEN** the profile model endpoint cannot be reached or returns an unusable response
- **THEN** the capability SHALL return a typed unavailable or failed result with a host-owned semantic reason
- **AND** it SHALL NOT fabricate a model choice or silently select a different profile

### Requirement: Synthesis and playback are host-owned asynchronous effects
The host SHALL expose semantic synthesis and playback capabilities that accept channel-domain text, channel instance identity, runtime generation, and run identity. Synthesis and playback SHALL be allowed to complete after the originating PTT session and SHALL be scheduled independently of the PTT audio route. The host SHALL own synthesis engines, PCM handling, output admission, playback routing, serialization, cancellation, pending/heard state, and cleanup; runtimes SHALL receive only typed outcomes or opaque lifecycle-bound handles.

#### Scenario: Accepted response is synthesized after PTT
- **WHEN** a durable channel run produces final assistant text after its PTT target has released
- **THEN** the host SHALL synthesize and schedule playback through the semantic capabilities without reopening or retaining the PTT route
- **AND** the host SHALL associate the effect with the channel instance, runtime generation, and run identity

#### Scenario: Response arrives while its channel is not selected
- **WHEN** synthesis completes while another channel is active or playback cannot be admitted
- **THEN** the host SHALL retain a typed pending playback result for the addressed channel
- **AND** it SHALL NOT play through an ambient or unrelated channel output
- **AND** it SHALL admit playback only through the host's selection and audio policy when that channel is returned to

#### Scenario: Synthesis or playback is revoked
- **WHEN** the owning runtime generation is retired or the operation is cancelled before playback is admitted
- **THEN** the host SHALL cancel or detach the operation according to its semantic contract
- **AND** completion after cancellation SHALL NOT produce playback, publication, or another late effect

### Requirement: Host executes only declared semantic agent tools
The host SHALL execute agent tool calls through semantic tool descriptors, requests, and normalized results. For this change the host SHALL expose only configured Keyboard `type_text` and `press_enter` operations, SHALL perform enabled calls automatically without per-call authorization, and SHALL own keyboard-profile resolution, text compilation, transport access, serialization, retries, and cleanup. Tool contracts SHALL NOT expose Android input, BLE/HID, filesystem, SDK, or transport objects to channel runtimes.

#### Scenario: Configured Keyboard tool is called
- **WHEN** an agent run requests an enabled `type_text` or `press_enter` tool for its channel
- **THEN** the host SHALL validate the semantic arguments, execute the operation through the configured host keyboard capability, and return one normalized tool result
- **AND** the host SHALL preserve the tool-call identity and SHALL NOT ask for per-call user authorization

#### Scenario: Tool is not enabled or cannot be resolved
- **WHEN** a run requests a tool not declared by the channel or whose keyboard profile is unavailable
- **THEN** the host SHALL return a typed rejected or unavailable tool result
- **AND** it SHALL NOT execute a platform input effect or select a fallback tool/profile

#### Scenario: Tool effect has an indeterminate outcome
- **WHEN** host-owned transport completion cannot prove whether a tool effect was delivered
- **THEN** the host SHALL report an indeterminate normalized result
- **AND** it SHALL NOT automatically replay the text-output or key-press effect

### Requirement: Asynchronous capability effects are generation-safe
Every asynchronous capability operation SHALL carry the channel instance identifier, runtime generation, and durable run identity that authorized it. The host SHALL accept completion, publication, playback, synthesis, and tool results only while that authorization remains current, SHALL revoke it on generation retirement, and SHALL make revocation and terminal completion idempotent. A late completion SHALL be observable only as a normalized stale or cancelled outcome and SHALL NOT mutate current channel state.

#### Scenario: Runtime generation is replaced
- **WHEN** a channel definition change or runtime replacement retires generation G and creates generation H
- **THEN** the host SHALL revoke outstanding capability effects owned by G
- **AND** operations issued by H SHALL use separate authorization even when the channel instance ID is unchanged

#### Scenario: Late completion arrives
- **WHEN** a completion from a revoked generation arrives after replacement, shutdown, or restart
- **THEN** the host SHALL reject or ignore the completion as stale
- **AND** it SHALL NOT publish a response, play audio, execute a tool, or mark a current run complete

#### Scenario: Terminal completion is delivered twice
- **WHEN** the same asynchronous operation reports a terminal result more than once
- **THEN** the host SHALL commit at most one terminal effect and one durable terminal state
- **AND** repeated completion SHALL return a typed already-completed or stale result without replaying the effect

### Requirement: Capability boundaries remain usable by future language adapters
All agent, model-discovery, synthesis, playback, and tool capability contracts SHALL use language-neutral host-domain values, typed outcomes, opaque handles, and explicit lifecycle operations. They SHALL NOT depend on Kotlin implementation classes, Compose state, Android interfaces, OpenAI SDK types, or a Lua-specific ABI, and the host SHALL retain responsibility for adapting those contracts to any future language runtime.

#### Scenario: A non-Kotlin adapter invokes a capability
- **WHEN** a future language adapter invokes an authorized capability using the documented semantic values
- **THEN** the host SHALL apply the same validation, generation, ownership, and failure rules as for a built-in runtime
- **AND** the adapter SHALL NOT need access to SDK, Android, or transport objects

#### Scenario: SDK implementation changes
- **WHEN** the host replaces or upgrades its protocol SDK or synthesis engine
- **THEN** semantic capability inputs, outputs, and lifecycle rules SHALL remain unchanged
- **AND** persisted or projected channel-domain data SHALL NOT contain SDK implementation types
