## MODIFIED Requirements

### Requirement: Channel catalogue is the authoritative ordered source
The system SHALL maintain one persisted, nonempty, ordered catalogue of channel definitions and exactly one active channel instance whose ID exists in that catalogue. Each definition SHALL contain a stable opaque instance ID, display name, enabled state, a stable channel implementation provider reference, a provider configuration schema version, and a losslessly preserved opaque configuration payload. Instance IDs SHALL NOT change when a channel is renamed, reordered, migrated, or temporarily unavailable and SHALL NOT be derived from display name, provider reference, or list position. Persisted legacy definitions referencing removed built-in providers (`builtin:debug` or `builtin:keyboard`) SHALL remain exact, unavailable records with no automatic rebinding to external providers, configuration copying, or active selection.

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

#### Scenario: Persisted legacy built-in definitions are preserved but unavailable
- **WHEN** the catalogue contains a definition referencing `builtin:debug` or `builtin:keyboard`
- **THEN** the system SHALL preserve that definition's instance ID, display name, enabled state, exact provider reference, schema version, and opaque configuration payload without deletion or mutation
- **AND** the instance SHALL be represented as unavailable through the missing-provider path
- **AND** the system SHALL NOT rebind it to an installed external provider identity, copy or substitute its configuration, or select it for active PTT execution

#### Scenario: Installing an external package does not migrate a legacy built-in instance
- **WHEN** an external package implementing equivalent user-visible behavior is installed and registered with its repository-derived provider reference
- **THEN** existing catalogue definitions referencing a removed built-in provider SHALL remain unavailable
- **AND** the host SHALL NOT automatically migrate, update, copy configuration from, or associate those legacy definitions with the installed provider
- **AND** the user SHALL explicitly create a new instance of the installed provider

### Requirement: Legacy channel settings migrate once
When no catalogue document exists, the system SHALL construct and atomically persist an initial provider-backed catalogue using only providers still registered by the current app. It SHALL NOT seed Debug or Keyboard instances. Seeded definitions SHALL retain their existing stable built-in instance IDs and map to registered provider references with versioned configuration payloads. A legacy active-channel preference that identifies Debug, Keyboard, or any absent provider SHALL fall back to a valid enabled instance in the seeded catalogue. After a catalogue is committed, it SHALL be the only channel-definition source of truth.

#### Scenario: First start with legacy settings
- **WHEN** no catalogue exists and valid legacy channel preferences exist
- **THEN** the system SHALL create definitions only for currently registered built-in seed providers
- **AND** it SHALL NOT create Debug or Keyboard definitions
- **AND** it SHALL preserve supported legacy configuration in the corresponding provider payloads
- **AND** it SHALL persist the complete catalogue before publishing it

#### Scenario: Legacy active channel references a removed provider
- **WHEN** no catalogue exists and the legacy active-channel preference identifies Debug or Keyboard
- **THEN** the system SHALL select a seeded, enabled instance whose provider is registered
- **AND** the selected fallback channel SHALL exist in the catalogue
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
