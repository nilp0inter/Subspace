## MODIFIED Requirements

### Requirement: Providers expose stable implementation descriptors
The host SHALL register channel implementations through descriptors keyed by a stable, non-blank implementation identifier that is independent of channel instance IDs, display names, catalogue position, and implementation class names. A descriptor SHALL declare presentation metadata, its current configuration schema version, default configuration production, configuration validation and migration, runtime construction, semantic capability eligibility, and generic preparation traits. Descriptor contracts SHALL use host-domain values and SHALL NOT expose Android, transport, hardware, or connection objects. Built-in registrations SHALL NOT include the Debug or Keyboard providers.

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

#### Scenario: Built-in Debug and Keyboard providers are absent
- **WHEN** the registry registers built-in provider descriptors
- **THEN** the Debug and Keyboard built-in provider descriptors SHALL NOT be registered
- **AND** implementation identifiers `builtin:debug` and `builtin:keyboard` SHALL remain unregistered

#### Scenario: Existing catalogue definitions for removed built-ins become unavailable
- **WHEN** a catalogue definition references `builtin:debug` or `builtin:keyboard`
- **THEN** resolution SHALL return an explicit unavailable-provider result for that definition
- **AND** the host SHALL NOT automatically redirect, copy configuration, or rebind it to an installed repository provider
