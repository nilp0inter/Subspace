## Purpose

Defines the host-managed installed package store, atomic transactions, recovery,
and asynchronously published snapshots for installed channel providers.

## Requirements

### Requirement: Installed packages use a separate provider-level store
The host SHALL maintain installed Lua package revisions in app-private storage separate from the channel catalogue. The installed-package index SHALL map one durable provider identity to exactly one active revision and zero or one rollback revision. A revision SHALL reference immutable exact artifact content by SHA-256 and retain its validated manifest, runtime requirements, presentation, and host-resolved repository/release/asset source record. The host SHALL NOT persist Lua source, artifact path, digest, release version, or package revision in a `ChannelDefinition` or its opaque configuration. Multiple channel instances referencing one installed provider SHALL resolve the same active revision while retaining independent instance IDs and configurations.

#### Scenario: Multiple instances use one installed provider
- **WHEN** two catalogue definitions reference the same installed provider implementation ID
- **THEN** both definitions SHALL resolve the provider's one active package revision
- **AND** neither definition SHALL contain or independently select an artifact digest or package version

#### Scenario: Installed provider has no channel instances
- **WHEN** a valid package is active but no catalogue definition references its provider ID
- **THEN** the package SHALL remain installed and discoverable to host provider surfaces
- **AND** loading the installed index or publishing its provider SHALL NOT create a Lua actor or Lua state

#### Scenario: Package store is absent on upgrade
- **WHEN** the application starts without an installed-package index or content directory
- **THEN** the host SHALL treat installed packages as an empty snapshot
- **AND** it SHALL NOT migrate or rewrite the existing channel catalogue

### Requirement: Package installation is an atomic prepare-commit-publish transaction
An install request SHALL provide one exact artifact and one host-resolved repository/release/asset identity. The installed-package repository SHALL serialize mutations for the addressed provider, stream the artifact into private staging while hashing and bounding it, validate the complete package without executing Lua, commit immutable digest-addressed content, atomically replace the complete installed index, and only then publish a new immutable installed-provider snapshot. Any failure before index commit SHALL preserve the prior committed index, active provider revision, published provider snapshot, and live runtime generations unchanged. Incomplete staging content SHALL never be materialized or published.

#### Scenario: First installation succeeds
- **WHEN** a compatible package passes identity, archive, manifest, source, digest, and program-image validation and storage commit succeeds
- **THEN** its revision SHALL become the active revision for that provider in one committed index transition
- **AND** the host SHALL publish the provider only after that commit

#### Scenario: Validation fails before commit
- **WHEN** an incoming artifact is malformed, incompatible, over bounds, identity-mismatched, or otherwise invalid
- **THEN** the install SHALL return one typed failure
- **AND** the previous index, active package, provider snapshot, catalogue, and runtimes SHALL remain unchanged

#### Scenario: Process stops with incomplete staging content
- **WHEN** the process stops after staging begins but before the complete index commit
- **THEN** restart recovery SHALL retain the previously committed active revision
- **AND** it SHALL discard or ignore the incomplete staging content
- **AND** it SHALL NOT infer a successful install from the presence of staged bytes

### Requirement: Update and idempotent reinstall preserve exact revision semantics
Installing a different validated revision for an existing provider SHALL atomically make the incoming revision active and retain the former active revision as the sole rollback revision. Any older rollback revision SHALL cease to be referenced by the new index and MAY be removed as orphaned content after commit. Installing the same provider, exact source identity, and exact artifact digest as the current active revision SHALL be idempotent and SHALL NOT publish a new provider revision or replace live runtimes. Package-version text alone SHALL NOT determine equality or ordering.

#### Scenario: Provider update succeeds
- **WHEN** a different compatible artifact digest for an installed provider commits successfully
- **THEN** the incoming revision SHALL become active
- **AND** the former active revision SHALL become the only rollback revision
- **AND** the published provider fingerprint SHALL change to the incoming digest

#### Scenario: Exact active revision is reinstalled
- **WHEN** an install request identifies the same provider, exact release/asset source, and exact digest as the active revision
- **THEN** the repository SHALL return the existing active result idempotently
- **AND** it SHALL NOT change the index generation, rollback slot, provider fingerprint, or runtime generation

#### Scenario: Update storage fails
- **WHEN** incoming content validates but content or index commit fails
- **THEN** the former active and rollback bindings SHALL remain authoritative
- **AND** the host SHALL NOT publish the incoming provider or partially advance the index

### Requirement: Rollback is explicit and removal preserves channel definitions
Rollback SHALL be available only when one committed rollback revision exists. The repository SHALL revalidate that exact retained artifact and atomically swap the active and rollback revisions; it SHALL NOT search for another version, download content, or infer ordering from version strings. Removal SHALL atomically delete the provider binding from the installed index and make its active and rollback content eligible for orphan cleanup. Rollback and removal SHALL NOT modify, delete, reorder, disable, rename, or reinterpret channel definitions. The host SHALL NOT automatically roll back after corruption, compatibility failure, runtime failure, or application upgrade.

#### Scenario: Explicit rollback succeeds
- **WHEN** a provider has one valid retained rollback revision and rollback is requested
- **THEN** that exact revision SHALL become active atomically
- **AND** the former active revision SHALL become the rollback revision
- **AND** the provider fingerprint SHALL change to the newly active digest

#### Scenario: No rollback revision exists
- **WHEN** rollback is requested for a provider without a retained rollback revision
- **THEN** the repository SHALL return a typed no-rollback result
- **AND** the active revision and provider snapshot SHALL remain unchanged

#### Scenario: Installed provider is removed
- **WHEN** removal commits for a provider referenced by existing channel definitions
- **THEN** the installed provider SHALL disappear from provider resolution
- **AND** every definition SHALL remain persisted with the same instance ID, provider reference, name, enabled state, order, schema version, and opaque payload
- **AND** those instances SHALL project the ordinary missing-provider state

#### Scenario: Active revision is corrupt but rollback is valid
- **WHEN** active content fails integrity validation and a rollback revision remains valid
- **THEN** the host SHALL mark the provider unavailable with a typed corruption reason
- **AND** it SHALL NOT execute or automatically activate the rollback revision

### Requirement: Installed index and immutable content recover without partial publication
The installed-package index SHALL be replaced atomically as one complete document and SHALL have a last-known committed recovery copy or equivalent atomic-file guarantee. On startup the host SHALL validate the selected complete index before publishing any installed-provider snapshot. A valid recovery copy MAY replace an unreadable current index only as a complete document; the host SHALL NOT merge records or fields from two index generations. Exact content not referenced by the selected committed index SHALL be treated as orphaned and MAY be deleted by bounded cleanup. One corrupt active artifact SHALL make only its provider unavailable while valid sibling providers remain materializable. A globally unrecoverable index SHALL publish no installed providers and one actionable store failure while leaving built-in providers operational.

#### Scenario: Current index is corrupt and recovery copy is valid
- **WHEN** startup cannot decode or validate the current index but has one complete valid committed recovery copy
- **THEN** the host SHALL select the recovery copy as one whole index generation
- **AND** it SHALL NOT merge newer fragments from the corrupt document

#### Scenario: One active artifact is corrupt
- **WHEN** one provider record references missing or digest-mismatched content while sibling records are valid
- **THEN** the affected provider SHALL be published as unavailable with a typed package error
- **AND** valid sibling installed providers and all built-ins SHALL remain operational

#### Scenario: Unreferenced content is recovered
- **WHEN** startup finds exact artifact content that is not referenced by the selected active or rollback index records
- **THEN** the host MAY delete it as orphaned content
- **AND** it SHALL NOT activate, register, or execute it merely because it exists in the content directory

### Requirement: Installed providers publish asynchronously without blocking service startup
The installed-package repository SHALL load, hash, parse, and materialize active package revisions on a bounded host worker or I/O boundary rather than the Android main thread. It SHALL expose host-domain loading, ready-snapshot, and failure states. Built-in provider registration, foreground-service startup, and channel-catalogue preservation SHALL continue while installed packages load. Definitions referencing installed providers SHALL remain preserved and unavailable or loading until a complete installed snapshot is published. Every committed install, update, rollback, removal, or recovery change SHALL publish at most one complete immutable installed-provider snapshot.

#### Scenario: Service starts with installed packages
- **WHEN** application service creation begins with one or more committed installed revisions
- **THEN** built-in providers and the foreground service SHALL initialize without waiting for archive parsing on the Android main thread
- **AND** installed providers SHALL become available only after bounded off-main materialization publishes the complete snapshot

#### Scenario: Snapshot publication races package mutation
- **WHEN** an older load result completes after a newer committed index generation exists
- **THEN** the repository SHALL reject the stale publication
- **AND** it SHALL NOT replace the provider snapshot derived from the newer committed generation

#### Scenario: Installed store is empty
- **WHEN** loading completes with no active installed packages
- **THEN** the repository SHALL publish an empty installed snapshot
- **AND** ordinary Kotlin-provider startup SHALL create no Lua actor or Lua state

### Requirement: Package repository shutdown is bounded and late publications are suppressed
Repository shutdown SHALL stop mutation admission, cancel or join staging and load work within the host shutdown bound, close file resources, discard incomplete staging content, and suppress every later provider-snapshot publication. Committed exact content and index records SHALL survive service/process shutdown. Lua actors, states, timers, coroutines, logs, and generation authorization SHALL remain owned and closed by the existing runtime registry rather than by the package repository.

#### Scenario: Shutdown occurs during installation
- **WHEN** service shutdown begins while an artifact is staged but before index commit
- **THEN** the repository SHALL stop or finish only according to the bounded transaction rule
- **AND** it SHALL not publish an uncommitted provider snapshot after shutdown
- **AND** the previously committed index SHALL remain authoritative

#### Scenario: Shutdown occurs after commit before publication
- **WHEN** the index commit succeeds but shutdown prevents in-process provider publication
- **THEN** the committed index SHALL remain authoritative on the next start
- **AND** restart SHALL reconstruct the installed snapshot from that index
- **AND** no prior-process Lua state or runtime generation SHALL be restored
