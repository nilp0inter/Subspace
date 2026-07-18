## ADDED Requirements

### Requirement: Provider revision changes participate in runtime reconciliation
The runtime registry SHALL associate every available entry with the stable implementation identifier and opaque provider-revision fingerprint that constructed its current generation. Reconciliation SHALL compare both the effective channel definition and resolved provider revision. Provider addition SHALL allow a formerly missing definition to construct; publication of the same provider fingerprint SHALL NOT replace an unchanged runtime; update or rollback to another fingerprint SHALL replace every affected enabled instance through a fresh generation; provider removal, corruption, incompatibility, or failed materialization SHALL retire affected generations and preserve explicit unavailable entries. Provider revision fingerprints SHALL remain provider-level state and SHALL NOT be persisted in channel definitions.

#### Scenario: Missing installed provider becomes available
- **WHEN** the current catalogue preserves a definition for a missing installed provider and a complete provider snapshot later publishes that provider
- **THEN** reconciliation SHALL validate the definition through the provider and construct a fresh runtime generation when valid
- **AND** it SHALL NOT require a catalogue mutation or package reference in instance configuration

#### Scenario: Same provider revision is republished
- **WHEN** the effective channel definition is unchanged and provider resolution returns the same implementation ID and revision fingerprint
- **THEN** reconciliation SHALL retain the current runtime generation
- **AND** it SHALL NOT rerun Lua startup or create another actor or state solely because a snapshot was republished

#### Scenario: Installed provider revision changes
- **WHEN** update or explicit rollback publishes a different provider-revision fingerprint for an implementation ID referenced by enabled definitions
- **THEN** reconciliation SHALL construct a fresh successor generation for each affected instance
- **AND** each successor SHALL load only the newly active immutable program image
- **AND** instances using other providers SHALL retain their existing generations

#### Scenario: Installed provider is removed or becomes invalid
- **WHEN** provider resolution no longer supplies a usable revision for an existing package-backed definition
- **THEN** the registry SHALL retire and close the old generation through its ordinary lifecycle
- **AND** it SHALL preserve an unavailable entry at the same catalogue position with a typed package or provider reason
- **AND** it SHALL preserve the definition and opaque configuration unchanged

### Requirement: Package-driven replacement retains drain-before-ready ordering
An installed-provider revision replacement SHALL use the existing generation replacement boundary. Before successor generation H may execute Lua startup, receive event/effect authorization, publish readiness, or accept input, predecessor generation G SHALL stop new admission, complete any committed input terminal callback exactly once, cancel or drain descendants and outstanding effects, revoke generation-scoped capabilities, and close exactly once. A package update or rollback SHALL NOT create a parallel hot-swap path, mutate source inside G, transfer G's Lua state to H, or treat a timeout as permission to expose both revisions live.

#### Scenario: Update occurs during committed input
- **WHEN** package update changes the provider revision while generation G owns a committed input target
- **THEN** G's target SHALL receive and complete its terminal callback under G
- **AND** H SHALL NOT start or become ready until G's target lease, descendants, effects, capabilities, actor, and gate have drained or closed in the established order

#### Scenario: Old completion arrives after package replacement
- **WHEN** a timer, task, callback, or host-operation completion from revision G arrives after H becomes current
- **THEN** the host SHALL reject it as stale or closed without entering H's Lua state
- **AND** it SHALL not mutate H's logs, readiness, globals, module cache, status, or effects

#### Scenario: Replacement source fails before successor readiness
- **WHEN** the newly active provider revision cannot construct or activate generation H after G retires
- **THEN** the instance SHALL become unavailable or failed with the typed new-revision reason
- **AND** the registry SHALL NOT restart G or automatically select the package rollback revision

### Requirement: Provider removal and recovery preserve durable definitions but not volatile runtime state
When an installed provider is removed, corrupt, incompatible, or globally unavailable, the registry SHALL preserve every catalogue definition and generic ordered projection while closing any live generation. If the same durable provider identity later becomes available through explicit install, update, or rollback, reconciliation SHALL construct a fresh generation from the then-active exact artifact. It SHALL NOT restore prior Lua globals, module caches, timers, suspended coroutines, operation tokens, readiness/failure latches, actor queues, logs, or old-generation authorization.

#### Scenario: Removed provider is reinstalled
- **WHEN** a previously removed durable provider identity is explicitly installed again and its package becomes available
- **THEN** existing catalogue definitions referencing that identity SHALL be eligible for fresh runtime construction
- **AND** their instance IDs, names, order, enabled state, schema version, and opaque configuration SHALL remain unchanged

#### Scenario: Provider recovers after process restart
- **WHEN** a valid committed package is materialized after restart for a preserved definition
- **THEN** the registry SHALL allocate a fresh runtime generation and capability authorization
- **AND** no prior-process actor or completion SHALL be accepted as current

### Requirement: Installed-provider failures are isolated across providers and built-ins
A package parse, integrity, compatibility, provider-materialization, runtime-construction, activation, update, rollback, or removal failure for one provider SHALL NOT remove, replace, close, or make unavailable a runtime belonging to another provider. A globally unavailable installed-package index SHALL affect only installed-provider resolution; immutable built-in providers and their catalogue instances SHALL remain operational.

#### Scenario: One installed provider is corrupt
- **WHEN** provider A cannot materialize because its active artifact is corrupt while installed provider B and built-in providers are valid
- **THEN** only A's definitions SHALL project the corresponding unavailable state
- **AND** B and built-in runtime generations SHALL remain available and unchanged

#### Scenario: Installed index cannot be recovered
- **WHEN** no complete valid installed-package index generation can be selected
- **THEN** installed-provider definitions SHALL project a typed package-store unavailable reason
- **AND** built-in providers SHALL continue to resolve, construct, prepare input, and close normally
