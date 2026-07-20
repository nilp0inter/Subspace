## MODIFIED Requirements

### Requirement: Providers expose stable implementation descriptors
The host SHALL register channel implementations through descriptors keyed by a stable, non-blank implementation identifier that is independent of channel instance IDs, display names, catalogue position, and implementation class names. A descriptor SHALL declare presentation metadata, its current configuration schema version, default configuration production, configuration validation and migration, runtime construction, semantic capability eligibility, and generic preparation traits. Descriptor contracts SHALL use host-domain values and SHALL NOT expose Android, transport, hardware, or connection objects. Built-in registrations SHALL NOT include the Debug provider.

#### Scenario: Multiple instances resolve one provider
- **WHEN** two catalogue definitions reference the same registered implementation identifier
- **THEN** the host SHALL resolve both definitions through the same provider descriptor
- **AND** it SHALL construct independent runtime instances keyed by their distinct channel instance IDs

#### Scenario: Descriptor is consumed without platform leakage
- **WHEN** core channel code reads a provider descriptor to create, configure, present, or prepare a channel instance
- **THEN** it SHALL receive only descriptor metadata, opaque configuration values, generic preparation traits, and host capability contracts
- **AND** it SHALL NOT receive an Android context, hardware handle, transport client, connection state machine, or reconnect policy

#### Scenario: Installed provider descriptor exposes configuration and capabilities
- **WHEN** an installed Lua provider descriptor is loaded from a validated package revision
- **THEN** its derived descriptor SHALL expose its validated configuration fields and semantic capability allowlist eligibility to the host
- **AND** it SHALL NOT execute Lua code during snapshot loading or descriptor construction

#### Scenario: Built-in Debug provider is absent
- **WHEN** the registry registers built-in provider descriptors
- **THEN** the Debug built-in provider descriptor SHALL NOT be registered
- **AND** the implementation identifier `builtin:debug` SHALL remain unregistered

#### Scenario: Existing catalogue definitions for Debug become unavailable
- **WHEN** a catalogue definition references implementation identifier `builtin:debug`
- **THEN** resolution SHALL return an explicit unavailable-provider result for that definition
- **AND** the host SHALL NOT automatically redirect, copy configuration, or rebind it to an installed repository provider

### Requirement: Provider contracts remain implementation-neutral
The provider boundary SHALL support implementations supplied independently of the core channel model without requiring exhaustive built-in branches. Both built-in Kotlin and installed Lua providers SHALL share the same implementation descriptor, configuration schema, and runtime-construction boundary, and the host SHALL NOT maintain provider-specific branches for configuration presentation or capability resolution.

#### Scenario: Additional conforming provider is registered
- **WHEN** a new provider implements the descriptor, schema, runtime, and host-capability contracts
- **THEN** its instances SHALL participate in resolution, configuration, construction, readiness, and ordered projection through those generic contracts
- **AND** core routing and catalogue code SHALL NOT require an implementation-specific branch

#### Scenario: Installed Lua provider uses the generic provider boundary
- **WHEN** an installed Lua provider constructs its runtime generation
- **THEN** it SHALL interact with the host solely through the instance-scoped execution context and semantic capability adapters
- **AND** it SHALL NOT bypass the provider boundary or access internal platform APIs directly

#### Scenario: Platform hardening is deployed
- **WHEN** an installed Lua provider attempts to access host resources outside its declared capabilities
- **THEN** the host SHALL deny access and fail-closed
- **AND** the host SHALL NOT execute any Lua code or allocate resources for undeclared capabilities
