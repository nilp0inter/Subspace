## RENAMED Requirements

- FROM: `### Requirement: Supported built-in instances can be added and updated`
- TO: `### Requirement: Provider-backed instances can be added and updated`

## MODIFIED Requirements

### Requirement: Channel catalogue is the authoritative ordered source
The system SHALL maintain one persisted, nonempty, ordered catalogue of channel definitions and exactly one active channel instance whose ID exists in that catalogue. Each definition SHALL contain a stable opaque instance ID, display name, enabled state, a stable channel implementation provider reference, a provider configuration schema version, and a losslessly preserved opaque configuration payload. Instance IDs SHALL NOT change when a channel is renamed, reordered, migrated, or temporarily unavailable and SHALL NOT be derived from display name, provider reference, or list position.

#### Scenario: Catalogue loads valid definitions
- **WHEN** the application loads a valid persisted catalogue
- **THEN** it SHALL publish the definitions in persisted list order
- **AND** it SHALL publish the persisted active instance ID
- **AND** each definition SHALL retain its stable provider reference and complete opaque configuration payload

#### Scenario: Multiple instances share a provider
- **WHEN** two channel definitions reference the same channel implementation provider
- **THEN** each definition SHALL retain an independent instance ID and configuration payload
- **AND** both SHALL appear independently in the ordered catalogue

#### Scenario: Same-provider configuration update is isolated
- **WHEN** one of multiple same-provider instances receives a configuration update addressed by its instance ID
- **THEN** only that definition SHALL change
- **AND** every sibling definition, catalogue order, and active ID SHALL remain unchanged

#### Scenario: Provider is unavailable
- **WHEN** a structurally valid definition references a provider that is missing, incompatible, or failed to load
- **THEN** the catalogue SHALL retain and publish that definition in its persisted position
- **AND** it SHALL preserve the definition's instance ID, provider reference, schema version, and configuration payload without loss
- **AND** provider unavailability SHALL NOT invalidate the remaining catalogue

#### Scenario: Invalid catalogue is rejected
- **WHEN** a persisted catalogue has an unsupported catalogue document version, duplicate or blank instance IDs, a blank provider reference, no definitions, or an active ID outside the definition list
- **THEN** the system SHALL NOT publish the invalid catalogue as runtime state
- **AND** it SHALL surface an actionable load failure rather than silently constructing partial defaults

### Requirement: Provider-backed instances can be added and updated
The system SHALL allow the user to create an instance from any available registered channel implementation provider, assign a display name, and edit that instance's configuration through the provider's schema. New instances SHALL receive unique opaque IDs, SHALL store the provider's stable reference, and SHALL store the provider configuration as a versioned opaque payload without converting it to a host-owned built-in configuration algebra.

#### Scenario: Add an instance from a registered provider
- **WHEN** the user creates a channel using an available registered provider and configuration valid for that provider's current schema
- **THEN** the system SHALL append a new definition to the catalogue
- **AND** the new definition SHALL have an ID distinct from every existing definition
- **AND** it SHALL persist the provider reference, current schema version, and complete validated payload

#### Scenario: Removed seeded provider instance can be recreated
- **WHEN** a migrated seed instance of an available built-in provider is removed
- **THEN** the user SHALL remain able to create another instance from that provider with a new opaque ID
- **AND** existing instances from that provider SHALL NOT prevent additional instances

#### Scenario: Rename a channel
- **WHEN** the user changes a channel instance's display name
- **THEN** the system SHALL persist the new display name
- **AND** the instance ID, provider reference, configuration payload, active state, and list position SHALL remain unchanged

#### Scenario: Reject invalid configuration
- **WHEN** an add or update request contains configuration invalid for the referenced provider and declared schema version
- **THEN** the system SHALL reject the mutation
- **AND** the persisted catalogue SHALL remain unchanged

#### Scenario: Missing provider cannot validate an edit
- **WHEN** a configuration edit is requested for an instance whose provider is unavailable
- **THEN** the system SHALL reject the configuration mutation with an actionable unavailable result
- **AND** it SHALL preserve the stored provider reference, schema version, and complete payload without field loss or reinterpretation

### Requirement: Legacy channel settings migrate once
When no catalogue document exists, the system SHALL construct and atomically persist an initial provider-backed catalogue from existing Journal, Debug, Keyboard, and active-channel preferences. The initial order SHALL preserve the current built-in order, the seeded definitions SHALL retain the existing built-in instance IDs, each built-in SHALL map to its stable registered provider reference and versioned configuration payload, and existing valid configuration and active selection SHALL be preserved. After a catalogue is committed, it SHALL be the only channel-definition source of truth.

#### Scenario: First start with legacy settings
- **WHEN** no catalogue exists and valid legacy channel preferences exist
- **THEN** the system SHALL create provider-backed Journal, Debug, and Keyboard definitions with their prior configuration represented losslessly in the corresponding provider payloads
- **AND** it SHALL preserve the prior built-in IDs, built-in order, and valid active instance ID
- **AND** it SHALL persist the complete catalogue before publishing it

#### Scenario: Migration commit fails
- **WHEN** creating the initial provider-backed catalogue from legacy preferences fails to commit
- **THEN** the system SHALL retain the legacy preferences
- **AND** it SHALL retry migration on a subsequent start rather than marking migration complete
- **AND** it SHALL NOT publish a partially migrated catalogue

#### Scenario: Catalogue already exists
- **WHEN** a valid provider-backed catalogue document exists
- **THEN** the system SHALL load that catalogue
- **AND** it SHALL NOT merge or overwrite it from legacy preference keys

## ADDED Requirements

### Requirement: Provider configuration migration is lossless and atomic
When an available provider declares a configuration schema version newer than a stored instance payload, the system SHALL ask that provider to migrate the opaque payload forward in declared version steps and validate the result. The system SHALL preserve every payload field not intentionally transformed by the provider. It SHALL atomically commit the complete next catalogue before publishing definitions or constructing runtimes from migrated payloads; a migration or commit failure SHALL leave the previously persisted catalogue unchanged.

#### Scenario: Configuration payload migrates forward
- **WHEN** an instance stores an older supported configuration schema version and its provider successfully migrates and validates the payload
- **THEN** the system SHALL atomically persist the complete catalogue with that instance's migrated version and payload
- **AND** it SHALL preserve the instance ID, provider reference, display name, enabled state, catalogue position, active selection, and all untransformed payload fields
- **AND** it SHALL publish or instantiate the migrated definition only after the commit succeeds

#### Scenario: One payload migration fails
- **WHEN** any required provider migration step fails, returns an invalid payload, or cannot reach the provider's current schema version
- **THEN** the system SHALL NOT commit or publish a partially migrated catalogue
- **AND** every previously persisted definition, ordering position, active ID, schema version, and payload SHALL remain unchanged
- **AND** the affected instance SHALL be represented as unavailable with an actionable migration failure

#### Scenario: Provider is absent during forward migration
- **WHEN** an instance references an unavailable provider and its stored payload cannot be assessed or migrated
- **THEN** the system SHALL preserve that instance and its opaque payload without modification
- **AND** it SHALL represent the instance as unavailable rather than deleting it or synthesizing defaults
