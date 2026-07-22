## MODIFIED Requirements

### Requirement: Channel catalogue is the authoritative ordered source
The system SHALL maintain one persisted, nonempty, ordered catalogue of channel definitions and exactly one active channel instance whose ID exists in that catalogue. Each definition SHALL contain a stable opaque instance ID, display name, enabled state, a stable channel implementation provider reference, a provider configuration schema version, and a losslessly preserved opaque configuration payload. Instance IDs SHALL NOT change when a channel is renamed, reordered, migrated, or temporarily unavailable and SHALL NOT be derived from display name, provider reference, or list position. Persisted legacy definitions referencing the removed built-in Debug provider (`builtin:debug`) SHALL remain as exact, unavailable records with no automatic rebinding to external providers, configuration copying, or active selection.

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

#### Scenario: Persisted legacy Debug definitions are preserved but unavailable
- **WHEN** the catalogue contains a definition referencing the legacy `builtin:debug` provider reference
- **THEN** the system SHALL preserve that definition's instance ID, display name, enabled state, provider reference (`builtin:debug`), schema version, and opaque configuration payload without deletion or mutation
- **AND** the instance SHALL be represented as unavailable through the missing-provider path
- **AND** the system SHALL NOT rebind or map it to an installed external provider identity
- **AND** it SHALL NOT copy or substitute its configuration or select it for active PTT execution

#### Scenario: Installing external Debug package does not migrate legacy instances
- **WHEN** the external Debug package is installed and registered with its repository-derived provider reference
- **THEN** existing catalogue definitions referencing the legacy `builtin:debug` provider reference SHALL remain unavailable
- **AND** the host SHALL NOT automatically migrate, update, copy configuration from, or associate those legacy definitions with the newly installed provider
- **AND** the user SHALL be required to manually create a new instance of the installed provider

### Requirement: Legacy channel settings migrate once
When no catalogue document exists, the system SHALL construct and atomically persist an initial provider-backed catalogue from existing Journal, Keyboard, and active-channel preferences, and SHALL NOT seed a Debug Channel instance. The initial order SHALL preserve the current built-in order of the non-Debug providers, the seeded definitions SHALL retain the existing built-in instance IDs, each non-Debug built-in SHALL map to its stable registered provider reference and versioned configuration payload, and existing non-Debug configuration and active selection SHALL be preserved. If the legacy active-channel preference is set to Debug, the initial active selection SHALL fall back to a valid active instance among the remaining seeded providers. After a catalogue is committed, it SHALL be the only channel-definition source of truth.

#### Scenario: First start with legacy settings
- **WHEN** no catalogue exists and valid legacy channel preferences exist
- **THEN** the system SHALL create provider-backed Journal and Keyboard definitions with their prior configuration represented losslessly in the corresponding provider payloads
- **AND** it SHALL NOT create a Debug definition
- **AND** it SHALL preserve the prior built-in IDs, built-in order (excluding Debug), and valid active instance ID if it is not Debug
- **AND** it SHALL persist the complete catalogue before publishing it

#### Scenario: First start with legacy active channel set to Debug
- **WHEN** no catalogue exists, valid legacy channel preferences exist, and the legacy active-channel preference is Debug
- **THEN** the system SHALL seed Journal and Keyboard definitions
- **AND** it SHALL NOT seed a Debug definition
- **AND** it SHALL select one of the seeded non-Debug channel instances as the active channel
- **AND** the selected fallback channel SHALL exist in the catalogue and be enabled
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
