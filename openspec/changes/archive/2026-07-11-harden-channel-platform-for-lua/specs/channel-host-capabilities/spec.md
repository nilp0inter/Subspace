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
