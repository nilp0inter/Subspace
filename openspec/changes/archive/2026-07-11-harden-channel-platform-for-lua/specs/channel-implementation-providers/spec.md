## ADDED Requirements

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
The provider registry SHALL contain at most one descriptor for each implementation identifier. Registration of a blank identifier or a duplicate identifier SHALL fail deterministically and SHALL NOT replace or shadow an already registered descriptor.

#### Scenario: Duplicate provider is registered
- **WHEN** a descriptor is registered with an implementation identifier already present in the registry
- **THEN** registration SHALL fail with a typed provider-registration error
- **AND** resolution of that identifier SHALL continue to return the original descriptor

#### Scenario: Unknown provider is resolved
- **WHEN** a catalogue definition references an implementation identifier absent from the completed provider registry
- **THEN** resolution SHALL return an explicit unavailable-provider result for that definition
- **AND** the host SHALL NOT select another provider by display name, configuration shape, catalogue position, or built-in kind

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
A provider SHALL construct a runtime only from one channel instance's stable ID, effective definition metadata, validated current-version configuration, and an instance-scoped host capability acquisition boundary. Runtime construction SHALL NOT depend on singleton built-in IDs or first-by-implementation catalogue lookup, and the constructed runtime SHALL NOT receive provider-registry mutation access.

#### Scenario: Runtime is constructed for a valid definition
- **WHEN** a registered provider successfully migrates and validates one available channel definition
- **THEN** the host SHALL invoke that provider's runtime constructor with the definition's own instance ID and validated configuration
- **AND** any host capabilities made available to the runtime SHALL be scoped to that instance

#### Scenario: Sibling configuration remains isolated
- **WHEN** a provider constructs or updates one of multiple instances using the same implementation identifier
- **THEN** it SHALL use only the addressed instance's configuration and capability scope
- **AND** it SHALL NOT read or mutate a sibling instance by implementation identifier or catalogue order

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
