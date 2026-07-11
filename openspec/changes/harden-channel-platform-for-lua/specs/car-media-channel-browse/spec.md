## MODIFIED Requirements

### Requirement: Media browse tree lists each Subspace channel as a playable media item
The system SHALL expose every instance in the authoritative ordered channel catalogue as a distinct playable Media item in the Android Auto Media browse tree, including instances whose implementation provider is absent, incompatible, or failed to load. Each item SHALL retain the instance's stable ID as `mediaId`, use its display name as title, and present host-rendered runtime or availability status as subtitle. Selecting any existing item SHALL set that instance as the single active channel regardless of preparation state. Provider code, configuration payloads, and implementation exceptions SHALL NOT control Android UI rendering or selection eligibility. Runtime readiness SHALL be enforced only when PTT is attempted.

#### Scenario: Browse lists each catalogue instance
- **WHEN** an Android Auto Media browser subscribes to the Subspace root
- **THEN** the system SHALL return one playable Media item per catalogue instance in catalogue order, regardless of provider availability
- **AND** each item's mediaId SHALL be the instance's stable identifier
- **AND** each item's title SHALL be the instance's display name
- **AND** each available item's subtitle SHALL encode ACTIVE, READY, or STANDBY status
- **AND** each unavailable item's subtitle SHALL encode UNAVAILABLE and a host-owned phone recovery direction

#### Scenario: Multiple instances share a provider
- **WHEN** the catalogue contains multiple instances referencing the same implementation provider
- **THEN** Android Auto SHALL expose each as a separate playable Media item using its stable instance ID and display name

#### Scenario: Selecting any browse item sets the active channel
- **WHEN** the user selects any nonactive persisted channel Media item
- **THEN** the system SHALL select that instance through the shared active-channel path
- **AND** the previously active instance SHALL become inactive
- **AND** the now-playing card SHALL reflect the newly active instance and its current availability

#### Scenario: Provider is absent or incompatible
- **WHEN** a catalogue instance references an implementation provider that is absent or incompatible
- **THEN** Android Auto SHALL retain that instance's playable Media item at its catalogue position
- **AND** the item SHALL retain the instance's stable ID as `mediaId` and its display name as title
- **AND** host-rendered metadata SHALL identify the unavailable state and phone recovery action
- **AND** selecting or displaying the item SHALL NOT execute provider-controlled Android UI

#### Scenario: Provider fails to load
- **WHEN** a provider fails to load for an instance already present in the catalogue
- **THEN** the system SHALL notify subscribed browser clients that the browse projection changed
- **AND** the next load SHALL retain the instance at the same catalogue position with the same `mediaId`
- **AND** the host-rendered item SHALL identify the provider failure as unavailable rather than silently omitting the instance

#### Scenario: Selecting an unavailable browse item activates it
- **WHEN** a client selects an unavailable instance's Media item
- **THEN** the system SHALL set that persisted instance as active
- **AND** it SHALL preserve the item's actionable recovery metadata
- **AND** a subsequent PTT attempt SHALL follow host-owned unavailable problem feedback without starting capture

#### Scenario: Channel addition propagates
- **WHEN** a channel instance is added while a browser client is subscribed
- **THEN** the system SHALL call `notifyChildrenChanged`
- **AND** the next load SHALL include the new instance at its catalogue position

#### Scenario: Channel removal and active repair propagate together
- **WHEN** a channel instance is removed and the catalogue repairs active selection
- **THEN** the system SHALL call `notifyChildrenChanged`
- **AND** the next browse result SHALL omit the removed instance
- **AND** now-playing metadata SHALL reflect the repaired active instance

#### Scenario: Channel reorder propagates
- **WHEN** catalogue order changes while a browser client is subscribed
- **THEN** the system SHALL call `notifyChildrenChanged`
- **AND** the next browse result SHALL match the new catalogue order

#### Scenario: Channel rename or status change propagates
- **WHEN** an instance's display name, readiness, active state, execution status, or provider availability changes
- **THEN** the system SHALL call `notifyChildrenChanged`
- **AND** the next browse result SHALL reflect the updated host-owned projection without changing the instance's `mediaId` or catalogue position

#### Scenario: Channel with non-zero pending count
- **WHEN** a channel projection reports a pending unheard count greater than zero
- **THEN** the system SHALL include the count in that channel's browse subtitle in compact form
- **AND** the count SHALL NOT appear when zero

#### Scenario: Channel ordering and identity are stable across surfaces
- **WHEN** the same catalogue snapshot is rendered on the phone dashboard and Android Auto
- **THEN** ordering SHALL be identical on both surfaces, including unavailable instances
- **AND** the same stable instance ID SHALL identify each corresponding phone card and Android Auto `mediaId`
- **AND** ordering SHALL emanate solely from the persisted catalogue rather than provider resolution success

#### Scenario: Legacy single-row browse contract remains removed
- **WHEN** an Android Auto Media browser subscribes to the Subspace root
- **THEN** the system SHALL NOT return the legacy item with `mediaId = "subspace-car-ptt"`
- **AND** consumers SHALL enumerate catalogue channel items
