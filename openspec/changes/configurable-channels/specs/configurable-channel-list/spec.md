## ADDED Requirements

### Requirement: User-configurable ordered channel instances
The system SHALL maintain a persisted ordered list of user-configurable channel instances. Each channel instance SHALL have a stable instance ID, a channel type ID, a display name, and a list position.

#### Scenario: App starts with configured channel instances
- **WHEN** the app loads channel configuration
- **THEN** the system SHALL restore the configured channel instances ordered by their list positions
- **AND** each restored instance SHALL retain its stable instance ID, channel type ID, display name, and type-specific configuration

#### Scenario: Multiple instances share one channel type
- **WHEN** the user creates multiple channel instances with the same channel type
- **THEN** the system SHALL keep each instance as a distinct selectable channel
- **AND** each instance SHALL retain independent display name, position, and type-specific configuration

#### Scenario: Channel positions conflict in storage
- **WHEN** the persisted channel list contains duplicate list positions
- **THEN** the system SHALL produce a deterministic order using list position first and stable instance ID as a tie-breaker
- **AND** the next persisted write SHALL normalize positions into a contiguous ordered sequence

### Requirement: Channel instance naming
The system SHALL allow the user to configure the display name of each channel instance. The configured display name SHALL be the name shown on visual surfaces and spoken through RSM channel-selection announcements.

#### Scenario: User renames a channel instance
- **WHEN** the user changes a channel instance display name
- **THEN** the dashboard SHALL show the new display name for that instance
- **AND** RSM channel-selection announcements SHALL speak the new display name when that instance is selected

#### Scenario: Duplicate display names exist
- **WHEN** two configured channel instances have the same display name
- **THEN** the system SHALL keep both channel instances selectable as separate entries
- **AND** routing SHALL continue to use their stable instance IDs rather than display names

### Requirement: Channel instance creation from channel types
The system SHALL allow a new channel instance to be created from an available built-in channel type. The created instance SHALL receive a unique stable instance ID, a user-visible display name, a list position, and default type-specific configuration.

#### Scenario: User creates a Debug channel instance
- **WHEN** the user adds a channel instance of the Debug channel type
- **THEN** the system SHALL create a new channel instance with a unique stable instance ID
- **AND** the instance SHALL contain Debug channel configuration independent from every other Debug channel instance

#### Scenario: Created channel is inserted at a requested position
- **WHEN** the user creates a channel instance at a specific list position
- **THEN** the system SHALL place the new instance at that position
- **AND** adjust other instance positions so the list remains ordered and contiguous

### Requirement: Default seeding and migration
The system SHALL seed or migrate existing fixed channel configuration into the configurable channel instance list without requiring user action.

#### Scenario: Existing install upgrades to configurable channels
- **WHEN** the app starts and no configurable channel-list schema exists
- **THEN** the system SHALL create default channel instances equivalent to the previous fixed Journal and Debug channels
- **AND** the system SHALL copy existing Journal and Debug configuration into those default instances
- **AND** the system SHALL preserve the closest equivalent active channel selection

#### Scenario: Fresh install starts with defaults
- **WHEN** the app starts on a fresh install
- **THEN** the system SHALL create a default ordered channel list containing one Journal instance and one Debug instance
- **AND** the first default instance SHALL be selected as the active channel

### Requirement: Invalid active channel recovery
The system SHALL recover deterministically when the saved active channel instance ID is not present in the configured channel list.

#### Scenario: Saved active channel is missing
- **WHEN** channel configuration loads and the saved active channel instance ID is absent
- **THEN** the system SHALL set the active channel to the first configured channel instance when one exists
- **AND** persist the recovered active channel selection

#### Scenario: Channel list is empty
- **WHEN** channel configuration loads and no channel instances are configured
- **THEN** the system SHALL expose an empty channel list
- **AND** PTT routing SHALL not dispatch to any channel instance
