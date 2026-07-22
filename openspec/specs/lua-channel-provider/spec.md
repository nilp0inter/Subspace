## Purpose

Defines immutable program images and Lua provider construction through the
existing channel implementation registry.

## Requirements

### Requirement: Lua program images are immutable host-supplied bundles
A Lua channel provider SHALL receive its program logic as an immutable program image supplied entirely by the host at construction time. The image SHALL NOT be owned by or decoded from the `ChannelDefinition`; it is a host-supplied construction input. The image SHALL contain the entry module source and all package-local pure-Lua module sources that the program may `require`. The image's source map SHALL be snapshotted once at image creation and SHALL NOT mutate for the lifetime of the image. The host SHALL NOT accept on-demand module downloads, source patches, network-loaded chunks, or code injection after construction. The image source map SHALL associate canonical module names with their Lua source text, limited to non-reserved names (names not beginning with `subspace.` and not exactly `subspace`). The same immutable source bytes MAY be shared across multiple runtime generations without per-generation deep copy; each generation SHALL construct its own isolated Lua state and per-generation module cache.

#### Scenario: Provider is constructed with a valid program image
- **WHEN** the provider constructs a runtime from a host-supplied program image that has a valid entry module and complete source map
- **THEN** the runtime SHALL load the entry module from the host-supplied source map
- **AND** `require` resolutions for non-reserved package-local names SHALL succeed from that same source map

#### Scenario: Provider is constructed with a partial source map
- **WHEN** the program image's source map is missing a module that the entry module tries to `require`
- **THEN** the runtime constructor SHALL return a typed module-resolution construction failure
- **AND** it SHALL NOT download the module, synthesize a stub, or continue without the missing module

#### Scenario: Source map contains a reserved subspace-prefixed entry
- **WHEN** the program image's source map includes an entry for `subspace.custom` or exactly `subspace`
- **THEN** the host SHALL reject that entry during source-map validation
- **AND** a subsequent `require` of that name SHALL raise a typed protected module error

#### Scenario: Source map exceeds configurable bounds
- **WHEN** the program image's source map entry count, per-module source byte length, or total source byte length exceeds a host-configured bound
- **THEN** the host SHALL reject the image during source-map validation before creating a Lua state
- **AND** it SHALL return a typed bounds-exceeded error
- **AND** no Lua state SHALL be created

#### Scenario: Two generations share immutable source bytes but have isolated Lua state and cache
- **WHEN** runtime generation G and generation H for the same Lua provider are constructed from the same program image
- **THEN** the runtime MAY share the same immutable source bytes between generations without deep copy
- **AND** each generation SHALL construct its own isolated Lua state and per-generation module cache
- **AND** no mutation of source text SHALL be possible because the source map is immutable for the image's lifetime

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

### Requirement: Compatibility failure is detected before Lua state creation
Before creating a Lua state for any program image, the host SHALL compare the image's declared `luaVersion` against the host's `LUA_VERSION` and the image's declared `apiVersion` against the host's `API_VERSION` by exact string equality. An incompatible image SHALL receive a typed compatibility-failure outcome and SHALL NOT cause a Lua state, actor, or source loading to occur. The containing channel instance SHALL project an explicit typed compatibility state. The host SHALL preserve the catalogue definition's stored configuration unchanged. The program image is supplied by the host when it constructs the Lua provider and is not part of persisted configuration; the host SHALL NOT persist or store a program-image reference in catalogue configuration. The host SHALL NOT attempt fallback to another runtime or silently adjust the program's declared execution requirements.

#### Scenario: Version incompatibility prevents state creation
- **WHEN** the program image declares a `luaVersion` or `apiVersion` not equal to the host's corresponding constant
- **THEN** the provider SHALL return a typed compatibility-failure outcome
- **AND** it SHALL NOT create a Lua state, load source, or invoke any plugin callback
- **AND** the channel instance SHALL project an explicit unavailable state with the typed compatibility reason
- **AND** it SHALL NOT persist or store a program-image reference in catalogue configuration

#### Scenario: Version compatibility allows state creation
- **WHEN** the program image declares `luaVersion` equal to the host's `LUA_VERSION` and `apiVersion` equal to the host's `API_VERSION`
- **THEN** the provider SHALL create a Lua state and proceed to load the entry module source

### Requirement: Lua provider and runtime do not alter normal startup
Ordinary application startup SHALL register package-specific Lua providers only from the complete validated active installed-provider snapshot. An absent or empty installed-package store SHALL register no production Lua provider. Loading packages, materializing descriptors, and registering providers SHALL create no Lua actor or Lua state. Existing Kotlin providers SHALL remain behaviorally supported and SHALL NOT acquire a Lua runtime. A Lua actor SHALL be created only when the runtime registry constructs a generation for an enabled catalogue definition referencing an available installed Lua provider.

#### Scenario: Application starts with an empty installed store
- **WHEN** the application starts and the installed-provider snapshot is empty
- **THEN** no production Lua provider SHALL be present in provider resolution
- **AND** no Lua actor or Lua state SHALL be created by package loading, provider composition, catalogue loading, selection changes, readiness refresh, or service shutdown
- **AND** existing Kotlin channels SHALL operate normally

#### Scenario: Application loads an installed provider without instances
- **WHEN** startup materializes and registers one valid installed Lua provider but no catalogue definition references it
- **THEN** the provider descriptor SHALL be available for host provider surfaces
- **AND** no Lua actor or Lua state SHALL be created

#### Scenario: Catalogue references an installed provider
- **WHEN** an enabled catalogue definition references a compatible registered installed Lua provider
- **THEN** registration SHALL follow the same provider-resolution and runtime-construction flow as Kotlin providers
- **AND** exactly that instance's runtime construction MAY create one actor and one Lua state
- **AND** definitions using other providers SHALL NOT acquire Lua state

#### Scenario: Installed package loading fails
- **WHEN** the package repository fails to materialize one installed provider during startup
- **THEN** built-in Kotlin providers and valid sibling installed providers SHALL remain registered and operational
- **AND** no Lua state SHALL be created for the failed provider

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
