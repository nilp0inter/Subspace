## MODIFIED Requirements

### Requirement: Provider registration is deterministic
The provider registry SHALL contain at most one current descriptor for each implementation identifier. Built-in providers SHALL be registered deterministically once; registration of a blank built-in identifier or duplicate built-in identifier SHALL fail and SHALL NOT replace or shadow the original. Installed providers SHALL be supplied only as one complete immutable host-owned snapshot. Before replacing the installed snapshot, the registry SHALL validate that every installed identifier is canonical, unique, derived from its resolved durable provider identity, consistent across snapshot key, descriptor, and configuration provider, and noncolliding with every built-in or host-reserved identifier. Any invalid installed entry SHALL reject the complete candidate snapshot and preserve the prior installed snapshot. Publication SHALL atomically expose either the complete predecessor or complete successor provider map, never a partial mix.

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

## ADDED Requirements

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
