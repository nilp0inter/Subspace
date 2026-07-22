## MODIFIED Requirements

### Requirement: Lua provider implements the ChannelImplementationProvider contract
The Lua provider SHALL implement the `ChannelImplementationProvider` contract used by the existing provider registry. A production installed-package provider SHALL expose a stable package-specific implementation identifier derived by the host from its resolved durable repository identity, validated package presentation metadata, a schema-version-1 configuration provider compiled from the package-declared configuration schema, a configuration-field list, a required-capability set compiled from the package-declared capabilities, one immutable program image, and an opaque provider-revision fingerprint equal to the active artifact digest. The provider SHALL NOT trust a package-selected built-in or internal identifier, register an alternative provider registry, add a special-case path in core dispatch, modify catalogue persistence, or create a Lua actor or Lua state at package materialization or provider registration time. Runtime construction SHALL create one Lua state and actor only for the addressed channel instance through standard registry reconciliation.

#### Scenario: Installed Lua provider is registered
- **WHEN** the host materializes and publishes a validated active installed package provider
- **THEN** it SHALL appear alongside built-in Kotlin providers through the same descriptor contract
- **AND** its descriptor, configuration-provider ID, installed snapshot key, and host-derived repository provider ID SHALL agree
- **AND** registration SHALL NOT create a Lua actor or Lua state

#### Scenario: Multiple instances reference one installed provider
- **WHEN** two catalogue definitions reference one installed Lua provider implementation ID
- **THEN** both SHALL resolve the same immutable active provider revision
- **AND** the registry SHALL construct independent runtime generations keyed by their distinct instance IDs
- **AND** each generation SHALL receive its own actor, state, cache, timers, tasks, logs, generation context, and capability scope

#### Scenario: Installed Lua provider constructs a runtime
- **WHEN** the registry invokes the provider's runtime constructor for a validated configuration definition
- **THEN** the provider SHALL validate and load its host-resolved immutable program image
- **AND** it SHALL create one Lua state and one actor for that definition's instance ID
- **AND** it SHALL validate the entry callback table through the existing Lua Runtime v1 contract

#### Scenario: Package declares configuration and capabilities
- **WHEN** a package-format-v1 manifest contains valid configuration schema fields and required capability declarations
- **THEN** package validation SHALL accept the artifact and compile the declarations into the provider snapshot
- **AND** the provider SHALL expose the compiled configuration provider and capability eligibility set to the host registry

#### Scenario: Lua provider is unavailable
- **WHEN** a catalogue definition references an installed Lua provider that is absent, loading, removed, corrupt, incompatible, or failed materialization
- **THEN** the host SHALL preserve and project the instance with a typed unavailability reason
- **AND** it SHALL NOT silently construct a generic Lua provider, default program image, retained old revision, or alternative runtime

#### Scenario: Configuration replacement spawns fresh generation
- **WHEN** an active channel's configuration is modified in the catalogue
- **THEN** the host SHALL stop predecessor admission, complete any committed terminal callback, drain and cancel descendants, revoke all capability leases, close the predecessor generation, and start a fresh generation with a new configuration snapshot
- **AND** it SHALL NOT modify the running predecessor actor in place

### Requirement: Lua provider/runtime adapter uses generation-safe execution context
The provider SHALL construct a runtime that receives an opaque host-owned generation execution context. This context SHALL supply typed, generation-bound timer scheduling, background-task admission, and a liveness query without exposing the registry gate, owning actor, Kotlin `CoroutineScope`, `RuntimeGenerationInvocationGate`, `RuntimeGeneration`, `CapabilityScopeIdentity`, or any Android or platform object. The context SHALL be bound to one generation. Timer or task admission after close or replacement SHALL return typed `CLOSED`; resource exhaustion SHALL return distinct typed `CAPACITY_EXHAUSTED`. Accepted timer callbacks SHALL be suppressed after close. The provider SHALL NOT construct a runtime outside standard registry reconciliation or bypass invocation, registry, capability, or generation gates. The host SHALL NOT deliver operation tokens, coroutine references, userdata, or Kotlin platform objects to plugin code.

#### Scenario: Spawned task uses context for internal async sleep
- **WHEN** a spawned background task calls `subspace.runtime.sleep(1.0)` and timer admission succeeds
- **THEN** the context SHALL own the generation-bound timer and invoke its callback at most once only while the generation remains live
- **AND** the context SHALL NOT expose a `CoroutineScope`, actor reference, operation token, or registry gate to the runtime

#### Scenario: Context rejects admission after generation close
- **WHEN** the owning runtime generation is closed or replaced and the runtime attempts to schedule a timer or admit a task
- **THEN** the generation execution context SHALL reject admission with typed `CLOSED`
- **AND** it SHALL NOT schedule work, invoke a callback, enter Lua, or resume a coroutine

#### Scenario: Accepted timer races generation close
- **WHEN** a timer was accepted while the generation was live but close wins before its callback is admitted
- **THEN** the context SHALL suppress the callback and the actor SHALL reject any independently racing operation completion through its existing generation-safe terminal admission
- **AND** the runtime SHALL NOT enter Lua or resume the coroutine

#### Scenario: Context is not shared across instances
- **WHEN** two independent channel instances both use the Lua provider
- **THEN** each instance's generation SHALL receive its own execution context
- **AND** a close or cancellation for one instance SHALL NOT affect the other instance's context or operations

#### Scenario: Generation close cancels host work and suppresses late effects
- **WHEN** a runtime generation is closed or replaced by the host
- **THEN** the host SHALL cancel all active host tasks, discard suspended coroutines without re-entering Lua, invalidate all generation-owned audio userdata, revoke all capability leases, and remove any queued playback authorized by the generation
- **AND** it SHALL suppress all late completion, status, log, or playback effects from the predecessor generation

### Requirement: Verification uses immutable black-box fixtures through real provider paths
The change SHALL verify the Lua provider contract using immutable Lua program image fixtures through the real provider registry, runtime registry, capability scope, actor kernel, replacement, and shutdown paths. Verification SHALL exercise independent same-provider instances, package-local `require` of non-reserved names, proactive timer work while unselected, normalized failures, stale-effect suppression, source-map bound enforcement, and the compatibility-failure path. Fixtures SHALL be host-supplied immutable Lua source maps limited to non-reserved names, defining the entry module and any helper modules. Verification SHALL NOT require a Lua provider registered in production and SHALL NOT alter persisted catalogue data.

#### Scenario: Same-provider instances are independent
- **WHEN** verification registers two catalogue definitions referencing the same Lua provider identifier and constructs runtimes for both
- **THEN** each runtime SHALL create its own Lua actor and Lua state
- **AND** mutations to one instance's global state SHALL NOT affect the other instance

#### Scenario: Package-local require resolves correctly
- **WHEN** verification provides a fixture with an entry module that `require`s a non-reserved package-local helper module
- **THEN** the runtime SHALL resolve the helper from the source map
- **AND** the helper's functions SHALL be available to the entry module

#### Scenario: Timer work progresses while unselected
- **WHEN** verification creates a fixture that starts a background timer via `subspace.runtime.sleep` while the channel is not selected
- **THEN** the runtime SHALL still fire the timer and resume the background task
- **AND** the verification SHALL observe the background work completing

#### Scenario: Normalized failure is delivered to Lua
- **WHEN** verification supplies a fixture whose spawned task observes a typed error from `sleep` or `spawn`
- **THEN** the runtime SHALL resume the waiting coroutine with a normalized error table
- **AND** the Lua callback SHALL observe the error through `(nil, error_table)` return convention

#### Scenario: Stale completion after replacement is suppressed
- **WHEN** verification replaces a runtime generation and then submits a completion bearing the old generation identity
- **THEN** the host SHALL reject the completion as stale
- **AND** it SHALL NOT enter the replacement generation's Lua state or affect its state

#### Scenario: Source-map bounds are enforced
- **WHEN** verification supplies a fixture whose source map entry count, per-module bytes, or total bytes exceeds a configured bound
- **THEN** the provider SHALL reject the image during source-map validation without creating a Lua state
- **AND** it SHALL return a typed bounds-exceeded error

#### Scenario: Compatibility failure is exercised before state creation
- **WHEN** verification supplies a fixture whose declared `apiVersion` is not equal to the host's `API_VERSION`
- **THEN** the provider SHALL report a typed compatibility-failure outcome
- **AND** the verification SHALL confirm that no Lua state was created for that fixture

#### Scenario: Verification confirms Diagnostics configuration snapshot is empty
- **WHEN** the host constructs a runtime generation for the Diagnostics provider
- **THEN** the startup callback SHALL receive a configuration snapshot containing schema version 1 and an empty values table `{schema_version=1, values={}}`

#### Scenario: Verification exercises two configured instances of one provider
- **WHEN** the host registers two catalogue definitions with different configuration payloads for the same installed provider ID
- **THEN** it SHALL compile the schemas and run independent generations with isolated configuration snapshot tables
- **AND** neither generation's configuration values SHALL affect or be accessible by the other

#### Scenario: Verification exercises configuration replacement triggers fresh generation
- **WHEN** the host commits a configuration edit for a running instance and triggers runtime reconciliation
- **THEN** the predecessor generation SHALL be closed, all of its pending tasks, timers, and leases SHALL be cancelled/revoked, and a fresh generation SHALL be started with the updated configuration snapshot
- **AND** no predecessor callback or queued playback SHALL be executed or permitted after revocation
