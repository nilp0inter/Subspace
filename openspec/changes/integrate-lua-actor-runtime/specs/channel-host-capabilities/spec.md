## MODIFIED Requirements

### Requirement: Channels access effects only through semantic host capabilities
The host SHALL expose Android- and product-native effects to channel runtimes only through semantic capability contracts. The host SHALL retain exclusive ownership of Android resources, audio routes and capture, Bluetooth and other hardware transports, connection and reconnection policy, concurrency, cleanup, and detailed platform diagnostics. Effects including audio capture, playback, text output, Sleepwalker transport, remote-agent completion, model discovery, synthesis, and tool execution SHALL continue to require semantic host capabilities, and capability contracts SHALL NOT expose Android, BLE, GATT, HID transport, Telecom, audio-route, recorder, file-descriptor, socket, or hardware objects to providers or runtimes. General runtime modules for future language adapters are a distinct mechanism: a runtime module SHALL yield opaque, lifecycle-bound operations and SHALL NOT expose platform objects, Android contexts, SDK clients, transport connections, or credential material. This change SHALL NOT introduce HTTP, filesystem, socket, package, or public API contracts for runtime modules.

#### Scenario: Runtime requests a semantic operation
- **WHEN** a runtime needs an effect supported by the host
- **THEN** it SHALL invoke a semantic host capability using host-domain values or opaque handles
- **AND** the host SHALL select and operate the required platform resources and policy internally

#### Scenario: Runtime is created
- **WHEN** the host constructs a provider runtime
- **THEN** it SHALL supply only the semantic capabilities authorized for that runtime instance
- **AND** it SHALL NOT inject an Android context, platform service, hardware client, transport connection, or reconnect controller

#### Scenario: Android-native effect requires a host capability
- **WHEN** a runtime requests an Android- or product-native effect such as audio capture, playback, text output, or remote completion
- **THEN** the effect SHALL be mediated exclusively through a semantic host capability
- **AND** the runtime SHALL NOT receive or control the underlying Android, Bluetooth, Telecom, recorder, or transport object

#### Scenario: Future runtime module yields opaque operations
- **WHEN** a future general runtime module provides an operation to a language adapter
- **THEN** the module SHALL yield an opaque, lifecycle-bound operation that the host can cancel, revoke, or drain
- **AND** the module SHALL NOT expose a platform object, Android context, SDK client, socket, file descriptor, transport connection, or credential to the adapter

#### Scenario: Runtime module does not bypass host capabilities
- **WHEN** a runtime module and a semantic host capability both relate to the same concern
- **THEN** the module SHALL NOT expose raw platform resources that the host capability owns
- **AND** Android- and product-native effects SHALL continue to require the corresponding semantic host capability

## ADDED Requirements

### Requirement: Plugin policy and operation mechanism remain separate
Plugin-owned policy (protocol selection, retry and backoff, polling cadence, state-machine, and routing decisions expressed in Lua) SHALL be distinct from the runtime operation mechanism that admits, suspends, resumes, cancels, and tears down work. A policy expressed in Lua SHALL NOT become a host-owned mechanism, and a host-owned operation mechanism SHALL NOT dictate plugin policy. The host SHALL remain authoritative for operation admission, generation authorization, capability lease revocation, and teardown sequencing; the plugin SHALL remain authoritative for the policy decisions that govern what work to attempt and how to respond to outcomes.

#### Scenario: Policy chooses retry behavior while mechanism owns cancellation
- **WHEN** a plugin's Lua policy decides to retry an operation after a failure
- **THEN** the decision to retry SHALL be expressed in plugin-owned policy
- **AND** the host-owned operation mechanism SHALL admit, suspend, resume, or cancel the resulting operation without dictating the retry decision

#### Scenario: Mechanism does not dictate plugin policy
- **WHEN** the host-owned operation mechanism admits or cancels an operation
- **THEN** it SHALL NOT prescribe polling cadence, protocol selection, backoff schedule, or state-machine transitions that the plugin owns
- **AND** the plugin SHALL retain authority over what work to attempt next

#### Scenario: Policy cannot bypass generation authorization
- **WHEN** a plugin policy schedules work that would outlive the admitting runtime generation
- **THEN** the host-owned mechanism SHALL cancel or drain that work on generation retirement
- **AND** the policy SHALL NOT retain authorization, capability leases, or effect delivery after the generation closes

### Requirement: No public runtime I/O modules or persistent plugin state ship in this change
This change SHALL promote the internal Lua kernel and actor mechanisms required for lifecycle and scheduling, but SHALL NOT expose public runtime I/O modules (HTTP, filesystem, socket, general networking, or event-loop modules), a channel-accessible persistent key-value store, package filesystem, package installer, or package verifier. General runtime modules described by this change are a forward contract boundary and SHALL NOT be registered or exposed to plugin code. Existing capability contracts SHALL remain usable by built-in Kotlin runtimes without constructing a Lua actor.

#### Scenario: Change is applied without public runtime I/O modules
- **WHEN** the internal Lua actor runtime and channel capability platform are initialized
- **THEN** no HTTP, filesystem, socket, general networking, event-loop, or persistent plugin-state module SHALL be exposed to channel code
- **AND** no package installation or verification path SHALL be introduced

#### Scenario: Built-in runtime does not require Lua
- **WHEN** a built-in Kotlin runtime operates through the existing channel and capability boundaries
- **THEN** it SHALL function without constructing a Lua actor or Lua state
- **AND** no public runtime I/O module or persistent plugin-state capability SHALL be present