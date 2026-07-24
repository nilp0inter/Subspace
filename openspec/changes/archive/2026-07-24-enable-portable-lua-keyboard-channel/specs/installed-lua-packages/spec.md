## ADDED Requirements

### Requirement: Installed revisions preserve dynamic choices and keyboard-output eligibility
The installed-package store and index SHALL preserve validated dynamic-choice source declarations and public `keyboard.output` eligibility as part of each immutable package revision. Every load, recovery, materialization, update, rollback, and restart SHALL rehash and reparse exact artifact bytes before trusting those declarations. Cached metadata SHALL NOT synthesize, omit, mutate, or grant a dynamic source or capability, and provider publication SHALL execute no Lua or host effect.

#### Scenario: Keyboard-output package survives restart
- **WHEN** an installed revision declaring `keyboard.output` and dependent keyboard platform, layout, and profile sources is restored after process restart
- **THEN** exact stored bytes SHALL be revalidated into the same declarations before provider publication
- **AND** registration SHALL create no actor, Lua state, dynamic-source lookup, preparation, queue entry, connection, or output operation

#### Scenario: Cached declaration disagrees with artifact
- **WHEN** cached index metadata for a dynamic source or capability differs from the reparsed exact artifact
- **THEN** the host SHALL treat the revision as invalid or corrupted according to the store contract
- **AND** it SHALL not publish authority from the cached metadata

### Requirement: Package replacement revokes predecessor keyboard output atomically
Updating, rolling back, removing, or reinstalling an installed provider SHALL use the existing atomic provider/runtime reconciliation path. Before a successor generation becomes ready, the host SHALL stop predecessor admission, finish or cancel committed callbacks within their contract, revoke predecessor keyboard-output leases and queued operations, close predecessor actor state, and suppress late completions. Compatible scalar profile configuration SHALL remain preserved; incompatible payloads SHALL remain unchanged and explicitly unavailable.

#### Scenario: Package update replaces live generation
- **WHEN** a live keyboard-output package revision is explicitly updated
- **THEN** predecessor keyboard-output authority and queued not-yet-effective operations SHALL be revoked before successor readiness
- **AND** the successor SHALL acquire distinct generation authority using the preserved compatible configuration

#### Scenario: Package removal preserves catalogue instance
- **WHEN** the external Keyboard package is removed while catalogue instances reference it
- **THEN** those definitions and scalar payloads SHALL remain preserved through the generic missing-provider path
- **AND** no built-in Keyboard provider SHALL be substituted or rebound

### Requirement: Installed keyboard-output packages receive no identity special case
Official classification, provider identity, installation, materialization, instance creation, configuration, update, rollback, removal, and reinstall SHALL depend only on generic repository/release/asset identity and validated package declarations. The application SHALL NOT branch on the external Keyboard repository coordinates, repository ID, label, manifest version string, asset digest, or implementation ID to grant capabilities or create behavior.

#### Scenario: Community package declares keyboard output
- **WHEN** a package from another repository passes the same validation and declares `keyboard.output`
- **THEN** it SHALL receive the same generic provider and runtime contracts
- **AND** official owner classification SHALL not change its API shape or host authorization semantics
