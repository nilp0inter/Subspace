## ADDED Requirements

### Requirement: Channels access effects only through semantic host capabilities
The host SHALL expose effectful services to channel runtimes only through semantic capability contracts. The host SHALL retain exclusive ownership of Android resources, audio routes and capture, Bluetooth and other hardware transports, connection and reconnection policy, concurrency, cleanup, and detailed platform diagnostics. Capability contracts SHALL NOT expose Android, BLE, GATT, HID transport, Telecom, audio-route, recorder, file-descriptor, socket, or hardware objects to providers or runtimes.

#### Scenario: Runtime requests a semantic operation
- **WHEN** a runtime needs an effect supported by the host
- **THEN** it SHALL invoke a semantic host capability using host-domain values or opaque handles
- **AND** the host SHALL select and operate the required platform resources and policy internally

#### Scenario: Runtime is created
- **WHEN** the host constructs a provider runtime
- **THEN** it SHALL supply only the semantic capabilities authorized for that runtime instance
- **AND** it SHALL NOT inject an Android context, platform service, hardware client, transport connection, or reconnect controller

### Requirement: Capability acquisition is explicit and instance scoped
A runtime SHALL acquire each host capability through its own instance-scoped capability scope. The host SHALL associate every acquired capability and operation with the channel instance ID and the current runtime generation, SHALL enforce descriptor-declared eligibility, and SHALL return a typed unavailable or denied result when acquisition cannot be satisfied. A runtime SHALL NOT obtain capabilities through ambient globals or another instance's scope.

#### Scenario: Authorized capability is acquired
- **WHEN** a live runtime requests a capability declared by its provider and available from the host
- **THEN** the host SHALL return an opaque capability lease bound to that runtime instance and generation
- **AND** operations through that lease SHALL be attributed to the same instance

#### Scenario: Capability is unavailable
- **WHEN** an authorized capability cannot currently be provided
- **THEN** acquisition SHALL return a typed unavailable result with a semantic reason
- **AND** the host SHALL NOT expose the underlying platform object or transport failure object

#### Scenario: Runtime requests an undeclared capability
- **WHEN** a runtime requests a capability not declared for its provider
- **THEN** the host SHALL deny acquisition
- **AND** it SHALL NOT grant access through another instance, a global singleton, or an implementation-specific fallback

### Requirement: Capability leases are revocable and lifecycle bound
Every acquired capability lease SHALL be revocable by the host and SHALL be bounded by the acquiring runtime generation. Runtime replacement, retirement, or closure SHALL revoke its leases and cancel or terminate instance-owned capability operations according to the capability contract. Revocation and release SHALL be idempotent, and use after revocation SHALL fail without producing an effect.

#### Scenario: Runtime is replaced
- **WHEN** a runtime generation is retired because its definition is updated
- **THEN** the host SHALL revoke that generation's capability leases
- **AND** the replacement generation SHALL acquire separate leases even when it has the same channel instance ID

#### Scenario: Revoked lease is used
- **WHEN** a runtime invokes an operation through a lease after revocation
- **THEN** the host SHALL reject the operation with a typed closed or cancelled result
- **AND** no platform, hardware, playback, publication, or persistence effect SHALL occur

#### Scenario: Lease is released repeatedly
- **WHEN** runtime cleanup and host teardown both attempt to release the same capability lease
- **THEN** the host SHALL perform terminal capability cleanup exactly once
- **AND** each release attempt SHALL complete without reacquiring the capability

### Requirement: Audio capabilities use high-level opaque handles
Any host audio capability made available to a channel SHALL represent work through semantic requests and opaque, lifecycle-bound handles. The host SHALL remain the sole owner of route selection, capture acquisition, ready and error beeps, recorder state, playback routing and ordering, and route release. Channels SHALL NOT inspect, select, retain, or release platform audio devices or routes.

#### Scenario: Channel requests host audio work
- **WHEN** a channel requests an authorized semantic audio operation
- **THEN** the host SHALL return a typed result or opaque operation handle
- **AND** the host SHALL choose and manage platform audio resources without revealing them to the channel

#### Scenario: Runtime returns synthesized playback
- **WHEN** a runtime creates an opaque playback operation from synthesized audio and returns it as its terminal input result
- **THEN** capability creation SHALL NOT play audio through an ambient or process-wide output
- **AND** the audio-input subsystem SHALL resolve the opaque operation and play its PCM exactly once through the active session's resolved output
- **AND** it SHALL preserve the endpoint-specific playback and route-release ordering before completing terminal cleanup

#### Scenario: PTT capture is prepared
- **WHEN** the active runtime accepts a channel-level input target
- **THEN** the host SHALL acquire and operate capture and routing through the existing host-owned audio lifecycle
- **AND** the runtime SHALL receive only high-level opaque channel audio handles and terminal events

#### Scenario: Opaque audio operation is cancelled
- **WHEN** the runtime generation closes or cancellation reaches an outstanding opaque audio operation
- **THEN** the host SHALL cancel or detach that operation according to its semantic contract
- **AND** completion after cancellation SHALL NOT publish playback or another late effect

### Requirement: Text output transport is host owned
The host text-output capability SHALL accept channel text and logical profile information and SHALL own compilation to transport-specific output, Sleepwalker BLE/GATT/HID access, connection state, connection attempts, reconnect policy, serialization, and cleanup. A channel runtime SHALL NOT receive or control the Sleepwalker connection or its transport primitives. Each accepted submission SHALL complete exactly once as `Delivered`, `Rejected`, `Failed`, or `Indeterminate`; the host SHALL NOT automatically replay a terminal submission.

#### Scenario: Keyboard instance submits text
- **WHEN** a Keyboard runtime submits text and a logical host profile through its instance-scoped text-output capability
- **THEN** the host SHALL compile and transport the output using host-owned Sleepwalker policy
- **AND** it SHALL return exactly one terminal `Delivered`, `Rejected`, `Failed`, or `Indeterminate` outcome

#### Scenario: Text transport is disconnected
- **WHEN** an eligible runtime requests text output while the Sleepwalker transport is disconnected
- **THEN** the host capability SHALL apply the host-owned connection or reconnect policy and MAY expose a nonterminal pending state
- **AND** the runtime SHALL NOT initiate BLE discovery, GATT connection, HID transmission, or retry scheduling directly
- **AND** the submission SHALL eventually complete with one defined terminal outcome

#### Scenario: Text delivery reaches a terminal outcome
- **WHEN** a submission completes as `Delivered`, `Rejected`, `Failed`, or `Indeterminate`
- **THEN** the host SHALL NOT automatically replay that submission
- **AND** any later submission SHALL require an explicit new channel request

#### Scenario: Runtime closes during text delivery
- **WHEN** a Keyboard runtime closes while its text-output operation is pending
- **THEN** the host SHALL cancel or detach the instance operation and revoke its lease
- **AND** a later transport completion SHALL NOT update the closed runtime or report its text as newly delivered

### Requirement: Capability failures are normalized and isolated
The host SHALL translate platform exceptions and transport-specific failures at the capability boundary into typed semantic outcomes suitable for channel status and diagnostics. One instance's capability failure SHALL NOT expose sensitive platform details to channel code, terminate the capability service for unrelated instances, or invalidate unrelated runtime scopes.

#### Scenario: Platform operation fails
- **WHEN** a host capability encounters an Android, hardware, transport, or I/O failure
- **THEN** it SHALL return a typed semantic failure to the calling runtime
- **AND** detailed platform diagnostics SHALL remain host-owned
- **AND** unrelated runtime instances SHALL remain operational

#### Scenario: Capability diagnostics are emitted
- **WHEN** the host records diagnostics for acquisition, execution, cancellation, or cleanup
- **THEN** diagnostics SHALL identify the semantic capability, channel instance, operation phase, and normalized outcome
- **AND** they SHALL NOT expose audio payloads, channel text content, secrets, or hardware addresses

### Requirement: No persistent script state capability is introduced
This change SHALL NOT provide a channel-accessible persistent key-value store, package filesystem, package installer, package verifier, Lua engine, or other script-runtime service. Capability contracts added by this change SHALL remain usable by built-in Kotlin runtimes without requiring a package or scripting subsystem.

#### Scenario: Hardened capability boundary is initialized
- **WHEN** the host initializes the channel capability platform delivered by this change
- **THEN** it SHALL expose only capabilities implemented and authorized by the Kotlin host
- **AND** it SHALL NOT initialize persistent script state or Lua/package infrastructure

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

### Requirement: Host owns unified half-duplex audio admission
The host SHALL expose channel audio only through semantic capabilities and SHALL atomically serialize capture and channel-content playback before any physical route acquisition. Provider and runtime code SHALL NOT receive admission locks, `InputMode`, Android audio objects, routes, endpoints, PCM outputs, active playback handles, or device identities.

#### Scenario: Runtime schedules playback while capture owns audio
- **WHEN** a runtime schedules semantic playback while capture owns or reserves host audio
- **THEN** the capability SHALL return or retain a typed pending outcome
- **AND** no playback route object or admission primitive SHALL cross into the runtime

#### Scenario: Host admits delayed playback
- **WHEN** a pending semantic playback request is selected and host audio is free
- **THEN** the host SHALL own current-channel validation, current-mode resolution, route acquisition, PCM playback, interruption, cleanup, and typed outcome publication

### Requirement: Active playback is host-controllable
The host playback capability SHALL represent admitted playback as a lifecycle-bound host operation capable of exact completion, ducked rejection-tone overlay, explicit skip, route-failure interruption, and cleanup. Control operations SHALL affect the active output stream without exposing or reacquiring its route through a channel runtime.

#### Scenario: Rejection tone overlays active speech
- **WHEN** host PTT policy rejects a press during active playback
- **THEN** the active playback operation SHALL temporarily duck speech and mix one rejection tone into its existing output stream
- **AND** it SHALL restore speech and continue the same message without changing its durable lifecycle

#### Scenario: Host explicitly skips playback
- **WHEN** host control policy skips the active operation
- **THEN** the capability SHALL stop the output, complete cleanup, and report an explicit skipped outcome distinct from interruption or route failure

#### Scenario: Route failure interrupts playback
- **WHEN** the active route fails before playback completes
- **THEN** the capability SHALL stop and clean up the operation
- **AND** it SHALL report interruption or failure without reporting successful hearing or explicit skip

### Requirement: Input subsystem integration is boundary-only
The host audio capability SHALL treat the existing audio-input subsystem as an authoritative capture owner. It MAY request or observe a capture admission reservation and SHALL observe existing terminal-completion publication, but it SHALL NOT take over recorder setup, route gates, ready-beep ordering, committed target handling, terminal ownership, or exact-once input cleanup.

#### Scenario: Capture is admitted through host arbitration
- **WHEN** PTT is not blocked by playback and obtains host capture admission
- **THEN** the existing input subsystem SHALL execute its unchanged internal sequence
- **AND** the host arbitration layer SHALL release capture ownership only from the existing terminal-completion boundary
