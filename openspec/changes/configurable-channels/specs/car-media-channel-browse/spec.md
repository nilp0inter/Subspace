## MODIFIED Requirements

### Requirement: Media browse tree lists each Subspace channel as a playable media item
The system SHALL expose each configured Subspace channel instance as a distinct playable Media item in the Android Auto Media browse tree, with the channel instance's configured display name as the item title and a per-channel status subtitle. Selecting the item from the head unit SHALL set that channel instance as the single active Subspace channel.

#### Scenario: Browse lists each configured channel
- **WHEN** the Android Auto Media browser requests root children
- **AND** the channel list contains multiple configured channel instances, including multiple instances of the same channel type
- **THEN** the system SHALL return a Media item per configured channel instance
- **AND** each item's mediaId SHALL be the channel instance's stable identifier
- **AND** each item's title SHALL be the channel instance's configured display name
- **AND** each item's subtitle SHALL encode the per-channel status (ACTIVE, READY, or STANDBY)

#### Scenario: Selecting a browse item sets the active channel
- **WHEN** the user selects a channel media item from the head unit while that channel instance is not active
- **THEN** the system SHALL set that channel instance as the single active Subspace channel via the existing active-channel selection path
- **AND** the previously active channel instance SHALL become inactive
- **AND** the now-playing card SHALL reflect the newly active channel instance

#### Scenario: Channel list updates propagate to the head unit
- **WHEN** the configured channel instance list changes, or a channel instance becomes configured, ready, or standby
- **THEN** the system SHALL notify Android Auto that browse children changed
- **AND** subsequent browse requests SHALL return the updated configured channel instance list

#### Scenario: Channel with non-zero pending count
- **WHEN** the channel list returns a channel instance whose pending unheard count is greater than zero
- **THEN** the system SHALL include the count in that channel instance's browse subtitle in a compact form

#### Scenario: Channel ordering is stable across surfaces
- **WHEN** the same set of channel instances is rendered on both the phone dashboard and the Android Auto Media browse tree
- **THEN** both surfaces SHALL present channel instances in the configured list order
- **AND** the ordering SHALL emanate solely from the channel repository

#### Scenario: Browse list when no channels are ready
- **WHEN** no channel instances are configured or ready
- **THEN** the system SHALL return an empty playable channel list or not-ready placeholder consistent with Android Auto Media API requirements
- **AND** selecting a not-ready placeholder SHALL NOT start PTT or select an invalid active channel

#### Scenario: Legacy single row is not exposed
- **WHEN** the Android Auto Media browser requests root children
- **THEN** the system SHALL NOT expose the legacy single `subspace-car-ptt` row
- **AND** external consumers (tests included) SHALL enumerate channel instance items instead

### Requirement: Now-playing card surfaces the active channel and live state
The system SHALL produce the MediaSession now-playing metadata for the Android Auto Media template from the currently active Subspace channel instance and the live PTT state.

#### Scenario: Now-playing title carries the active channel name
- **WHEN** a channel instance is active
- **THEN** the metadata title SHALL be the active channel instance's configured display name

#### Scenario: Now-playing subtitle carries state and pending summary
- **WHEN** the active channel instance has zero pending unheard messages and the live state is Ready
- **THEN** the metadata subtitle SHALL include the Ready state without a pending count
- **WHEN** the active channel instance has pending unheard messages greater than zero
- **THEN** the subtitle SHALL include a compact pending count for that active channel instance
- **WHEN** an inactive channel instance has pending unheard messages greater than zero
- **THEN** the subtitle MAY append a compact per-channel pending summary such as "<count> pending on <inactive channel>"
