## Purpose

Defines implementation-neutral channel provider descriptors, construction,
configuration, availability, and host-capability boundaries.

## Requirements

### Requirement: Providers expose stable implementation descriptors
The host SHALL register channel implementations through descriptors keyed by a stable, non-blank implementation identifier that is independent of channel instance IDs, display names, catalogue position, and implementation class names. A descriptor SHALL declare presentation metadata, its current configuration schema version, default configuration production, configuration validation and migration, runtime construction, and generic preparation traits. Descriptor contracts SHALL use host-domain values and SHALL NOT expose Android, transport, hardware, or connection objects.

#### Scenario: Multiple instances resolve one provider
- **WHEN** two catalogue definitions reference the same registered implementation identifier
- **THEN** the host SHALL resolve both definitions through the same provider descriptor
- **AND** it SHALL construct independent runtime instances keyed by their distinct channel instance IDs

#### Scenario: Descriptor is consumed without platform leakage
- **WHEN** core channel code reads a provider descriptor to create, configure, present, or prepare a channel instance
- **THEN** it SHALL receive only descriptor metadata, opaque configuration values, generic preparation traits, and host capability contracts
- **AND** it SHALL NOT receive an Android context, hardware handle, transport client, connection state machine, or reconnect policy

### Requirement: Provider registration is deterministic
The provider registry SHALL contain at most one current descriptor for each implementation identifier. Built-in providers SHALL be registered deterministically once; registration of a blank built-in identifier or duplicate built-in identifier SHALL fail and SHALL NOT replace or shadow the original. Installed providers SHALL be supplied only as one complete immutable host-owned snapshot. Before replacing the installed snapshot, the registry SHALL validate that every installed identifier is canonical, unique, derived from its resolved durable provider identity, consistent across snapshot key, descriptor, and configuration provider, and noncolliding with every built-in or host-reserved identifier. Any invalid installed entry SHALL reject the complete candidate snapshot with a typed validation result. Unchanged built-in or host-reserved registrations and resolved descriptors SHALL remain fully operational.

#### Scenario: Duplicate built-in provider is registered
- **WHEN** a built-in descriptor is registered with an implementation identifier already present in immutable built-in registrations
- **THEN** registration SHALL fail with a typed provider-registration error
- **AND** resolution of that identifier SHALL continue to return the original descriptor

#### Scenario: Installed provider collides with a built-in
- **WHEN** a candidate installed snapshot contains an implementation identifier equal to a built-in or host-reserved identifier
- **THEN** replacement SHALL reject the complete candidate snapshot with a typed collision result
- **AND** the prior provider resolution snapshot SHALL remain unchanged

#### Scenario: Installed snapshot contains inconsistent identity
- **WHEN** an installed snapshot key, host-derived repository provider ID, descriptor implementation ID, or configuration-provider ID differs from the others
- **THEN** replacement SHALL reject the complete candidate snapshot
- **AND** no descriptor from that candidate snapshot SHALL become resolvable

#### Scenario: Valid installed snapshot replaces its predecessor
- **WHEN** a complete collision-free installed snapshot validates successfully
- **THEN** the registry SHALL publish it atomically alongside unchanged built-ins
- **AND** concurrent resolution SHALL observe either the complete predecessor or complete successor map

#### Scenario: Unknown provider is resolved
- **WHEN** a catalogue definition references an implementation identifier absent from the current complete provider snapshot
- **THEN** resolution SHALL return an explicit unavailable-provider result for that definition
- **AND** the host SHALL NOT select another provider by display name, configuration shape, package version, catalogue position, or built-in kind
### Requirement: Providers own versioned configuration schemas
Each provider SHALL own the default payload, supported schema versions, validation rules, and forward migrations for its configuration. Before runtime construction or a configuration commit, the host SHALL ask the resolved provider to migrate the preserved payload to its current schema version and validate the migrated result. Provider schema processing SHALL be deterministic and SHALL NOT perform hardware, network, Android, or other externally visible effects.

#### Scenario: New instance requests default configuration
- **WHEN** the host creates a channel definition from a registered provider without user-supplied configuration
- **THEN** it SHALL obtain the default payload and schema version from that provider
- **AND** it SHALL validate the result through the same provider schema contract used for persisted payloads

#### Scenario: Older supported configuration is loaded
- **WHEN** a definition contains a provider-supported older schema version
- **THEN** the provider SHALL migrate the payload through its declared forward migration path
- **AND** the host SHALL validate the complete migrated payload before constructing a runtime or committing the migrated definition

#### Scenario: Configuration validation fails
- **WHEN** a provider rejects a proposed or migrated configuration payload
- **THEN** the host SHALL return a typed configuration error associated with the implementation identifier and schema version
- **AND** it SHALL NOT construct or update the runtime from that payload
- **AND** it SHALL NOT commit a partially migrated or partially validated payload

### Requirement: Runtime construction is instance scoped
A provider SHALL construct a runtime only from one channel instance's stable ID, effective definition metadata, validated current-version configuration, and an instance-scoped host capability acquisition boundary. The provider SHALL also receive an opaque host-owned generation execution context that supplies typed, generation-bound timer scheduling and background-task admission without exposing a Kotlin `CoroutineScope`, `ActorRuntime`, registry gate, Android object, or any other platform implementation type. This context SHALL be bound exclusively to the single generation being constructed. Admission after that generation is closed or replaced SHALL return a typed `CLOSED` rejection; bounded resource exhaustion SHALL return a distinct typed `CAPACITY_EXHAUSTED` rejection.

#### Scenario: Runtime is constructed for a valid definition
- **WHEN** a registered provider successfully migrates and validates one available channel definition
- **THEN** the host SHALL invoke that provider's runtime constructor with the definition's own instance ID, validated configuration, and an opaque generation execution context
- **AND** any host capabilities made available to the runtime SHALL be scoped to that instance

#### Scenario: Generation execution context is opaque and does not expose platform objects
- **WHEN** the provider inspects the generation execution context supplied to its runtime constructor
- **THEN** the context SHALL expose only lifecycle-continuation authority through typed operations
- **AND** it SHALL NOT expose a `CoroutineScope`, actor identity, registry gate, `lua_State*`, Android `Context`, coroutine reference, userdata, or any native or platform object

#### Scenario: Generation execution context rejects post-close admission
- **WHEN** the constructed runtime generation is closed or replaced and a provider attempts to schedule a timer or admit a task through the execution context
- **THEN** the context SHALL reject the admission with the typed reason `CLOSED`
- **AND** it SHALL NOT schedule work, invoke a callback, forward the operation to an actor or Lua state, or conflate closure with capacity exhaustion

#### Scenario: Accepted timer becomes stale after close
- **WHEN** a provider scheduled a timer while its generation was live and that generation closes before the timer callback is admitted
- **THEN** the host SHALL suppress the callback and dispose its generation-bound timer resources
- **AND** it SHALL NOT invoke the provider, actor, or Lua state

#### Scenario: Sibling configuration remains isolated
- **WHEN** a provider constructs or updates one of multiple instances using the same implementation identifier
- **THEN** it SHALL use only the addressed instance's configuration and capability scope
- **AND** each instance SHALL receive its own generation execution context
- **AND** it SHALL NOT read or mutate a sibling instance by implementation identifier or catalogue order

#### Scenario: Existing Kotlin provider constructs a runtime
- **WHEN** a built-in Kotlin provider constructs a runtime without using the opaque generation execution context
- **THEN** the host SHALL still supply a generation execution context even if the Kotlin provider does not consume it
- **AND** the existing behavior of the Kotlin provider SHALL remain unchanged
- **AND** the provider SHALL NOT be required to reference the context
### Requirement: Provider unavailability preserves channel instances
A definition whose provider is missing, incompatible, fails configuration migration or validation, or fails runtime construction SHALL remain a first-class catalogue instance and SHALL project an explicit unavailable state with a typed, actionable reason. Provider unavailability SHALL NOT discard or rewrite the preserved configuration payload, silently omit the instance from ordered projections, or prevent unrelated providers and instances from operating.

#### Scenario: Persisted provider is not installed
- **WHEN** the catalogue loads a valid definition whose implementation identifier has no registered provider
- **THEN** the host SHALL preserve the definition's instance ID, name, enabled state, order, schema version, and configuration payload unchanged
- **AND** it SHALL expose that instance as unavailable
- **AND** it SHALL refuse runtime preparation for that instance without starting capture

#### Scenario: Provider cannot consume the stored schema
- **WHEN** a registered provider does not support the definition's schema version or cannot migrate its payload
- **THEN** the host SHALL preserve the original definition unchanged
- **AND** it SHALL expose a typed incompatible-configuration reason rather than substituting defaults

#### Scenario: One provider fails to load
- **WHEN** one provider throws or returns failure during descriptor initialization or runtime construction
- **THEN** the affected definitions SHALL become unavailable with a normalized provider failure
- **AND** definitions resolved by other providers SHALL remain available and operational

### Requirement: Provider contracts remain implementation-neutral
The provider boundary SHALL support implementations supplied independently of the core channel model without requiring exhaustive built-in branches, while this change SHALL NOT introduce a Lua engine, script execution, package installation, package signing or distribution, persistent script state, or a Lua API surface.

#### Scenario: Additional conforming provider is registered
- **WHEN** a new provider implements the descriptor, schema, runtime, and host-capability contracts
- **THEN** its instances SHALL participate in resolution, configuration, construction, readiness, and ordered projection through those generic contracts
- **AND** core routing and catalogue code SHALL NOT require an implementation-specific branch

#### Scenario: Platform hardening is deployed
- **WHEN** this change is completed without an external script runtime or package subsystem
- **THEN** all provider requirements in this capability SHALL remain satisfiable by Kotlin built-in providers
- **AND** the host SHALL NOT attempt to discover, install, verify, or execute Lua packages

### Requirement: Provider configuration uses global profile and per-channel model choices
A provider that uses a remote model SHALL represent its connection choice as a stable global profile identifier and its model choice as a per-channel stable model identifier. The global profile SHALL own the endpoint and host-managed credential, SHALL NOT own a model selection, and SHALL NOT be copied into the channel payload. The provider SHALL resolve profile and model choices through semantic host-domain configuration services rather than embedding credentials, clients, network connections, SDK values, Android objects, or Compose state.

#### Scenario: Provider configuration selects a profile and model
- **WHEN** a user configures a remote-model channel instance
- **THEN** the provider SHALL persist the selected profile identifier and model identifier in its versioned opaque configuration
- **AND** the host SHALL resolve the endpoint and credential from the global profile at operation time
- **AND** the channel payload SHALL NOT contain the bearer credential or protocol client

#### Scenario: One profile serves multiple channel instances
- **WHEN** two channel instances select the same global profile with different model identifiers
- **THEN** each instance SHALL retain its own model choice and configuration identity
- **AND** changing a profile endpoint or credential SHALL NOT overwrite either instance's selected model

#### Scenario: Profile is unavailable
- **WHEN** a selected profile is deleted, disabled, invalid, or unavailable
- **THEN** provider validation or runtime readiness SHALL expose a typed unavailable reason for the addressed instance
- **AND** it SHALL NOT select another profile, endpoint, credential, or model implicitly

### Requirement: Provider model choices are dynamically discovered and schema-safe
A provider SHALL obtain model-choice metadata through an asynchronous host-owned discovery capability scoped to the selected profile. Provider schema validation SHALL remain deterministic and SHALL NOT perform network access; it SHALL validate a selected model against the latest available host discovery snapshot or return a typed pending or unavailable result when discovery is incomplete. A missing or retired model SHALL remain explicit and SHALL NOT be silently replaced by a default or another discovered model.

#### Scenario: Model discovery refreshes
- **WHEN** the host refreshes the model list for a selected profile
- **THEN** the provider SHALL receive host-domain model identifiers and display metadata without protocol SDK types
- **AND** a newly discovered model SHALL become selectable without a provider implementation or schema-version change

#### Scenario: Persisted model is no longer discovered
- **WHEN** a channel payload names a model that the selected profile no longer advertises
- **THEN** the provider SHALL preserve the model identifier in the payload
- **AND** it SHALL expose a typed model-unavailable state rather than substituting a different model or rewriting the payload

#### Scenario: Discovery is unavailable during configuration
- **WHEN** model discovery is pending or fails while a user edits provider configuration
- **THEN** the provider SHALL expose a typed pending or unavailable choice state
- **AND** schema processing SHALL NOT make a network request, fabricate choices, or commit a silently changed model

### Requirement: Provider choices use stable semantic references
Provider configuration SHALL encode external host resources as stable identifiers and scalar host-domain values only. A provider SHALL be able to declare dynamic choice requirements for profiles, models, keyboard profiles, or other host capabilities while leaving resolution and lifecycle ownership to the host. Provider configuration and presentation contracts SHALL remain independent of Android UI, SDK, transport, and connection objects.

#### Scenario: Host choice is rendered
- **WHEN** a provider editor requests choices for a profile, model, or keyboard profile field
- **THEN** the host SHALL supply typed choice metadata and availability reasons through a provider-neutral contract
- **AND** the provider SHALL retain only the selected stable identifier or semantic scalar

#### Scenario: Choice resource changes
- **WHEN** a referenced host resource changes, is removed, or becomes unavailable
- **THEN** the provider SHALL expose an explicit typed configuration or readiness error for that instance
- **AND** it SHALL NOT retain or mutate the removed host object, connection, or UI state

### Requirement: Provider runtime receives resolved semantic operations only
A provider SHALL construct a runtime from validated profile and model identifiers plus instance-scoped semantic host capabilities. Runtime construction SHALL NOT resolve credentials, instantiate protocol clients, call model endpoints, or inspect model-discovery transport state. The runtime SHALL request asynchronous completion, tool, synthesis, and playback work through the supplied capability contracts and SHALL remain usable by a future language adapter.

#### Scenario: Valid remote-model configuration constructs
- **WHEN** profile and model choices are valid according to provider schema and host availability
- **THEN** the provider SHALL construct a runtime with the stable selections and authorized semantic capabilities
- **AND** the host SHALL retain endpoint, credential, SDK client, network, tool transport, and playback ownership

#### Scenario: Runtime attempts platform access
- **WHEN** a provider runtime attempts to access an Android object, SDK client, credential, or transport connection
- **THEN** the host SHALL deny that access at the provider boundary
- **AND** the runtime SHALL receive a typed unavailable or denied result rather than a platform object

### Requirement: Resolved provider revisions are explicit host-domain identity
Every resolved provider SHALL expose an opaque immutable provider-revision fingerprint used by runtime reconciliation. A built-in provider SHALL use a stable host-owned revision for its unchanged implementation composition. An installed Lua provider SHALL use the SHA-256 digest of its exact active artifact. Republishing semantically identical provider source SHALL retain the same fingerprint; activating, updating, or rolling back to different executable content SHALL produce a different fingerprint. Revision fingerprints SHALL NOT be written to channel definitions or exposed as Kotlin, Android, actor, filesystem, or package-store objects.

#### Scenario: Same installed artifact is republished
- **WHEN** provider composition republishes an installed provider with the same implementation ID and exact artifact digest
- **THEN** the resolved provider revision fingerprint SHALL remain equal
- **AND** consumers SHALL be able to classify the publication as the same provider revision

#### Scenario: Installed provider content changes
- **WHEN** update or rollback activates a different exact artifact digest for the same provider identity
- **THEN** the implementation identifier SHALL remain unchanged
- **AND** the resolved provider revision fingerprint SHALL change

#### Scenario: Provider revision crosses the runtime boundary
- **WHEN** the runtime registry records which resolved provider constructed a generation
- **THEN** it SHALL receive only the stable implementation ID and opaque revision fingerprint
- **AND** it SHALL NOT receive package-store paths, mutable archive objects, Android objects, Lua state handles, or repository clients

### Requirement: Provider snapshot publication invokes no provider code under registry synchronization
The registry SHALL construct and validate candidate installed descriptors before its atomic publication boundary. It SHALL NOT parse packages, perform file or network I/O, construct runtimes, invoke configuration migration or validation, create actors, or execute provider callbacks while holding provider-registry synchronization. Snapshot observers SHALL receive one monotonic complete publication and SHALL reconcile outside the provider-registry publication boundary.

#### Scenario: Candidate installed snapshot is prepared
- **WHEN** the package repository materializes descriptors for a candidate snapshot
- **THEN** package parsing, digesting, program-image creation, and descriptor construction SHALL complete before registry publication begins
- **AND** no package callback SHALL execute under registry synchronization

#### Scenario: Snapshot observer reacts to publication
- **WHEN** the registry publishes a new complete installed-provider snapshot
- **THEN** runtime and catalogue reconciliation SHALL run outside provider-registry synchronization
- **AND** unrelated provider resolution SHALL remain available
