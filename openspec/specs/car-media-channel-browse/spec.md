## Purpose

Defines how the Android Auto Media session's `MediaBrowserService` exposes each Subspace channel as a distinct playable Media item in the browse tree, and how the now-playing card surfaces the active channel + live PTT state. Channel switching from a browse-item selection, channel-list update propagation to the head unit, and the state-tinted now-playing bitmap are part of this capability.

## Requirements

### Requirement: Media browse tree lists each Subspace channel as a playable media item
The system SHALL expose every instance in the authoritative ordered channel catalogue as a distinct playable Media item in the Android Auto Media browse tree. Each item SHALL use the instance's stable ID as `mediaId`, display name as title, and runtime status as subtitle. Selecting an item SHALL set that instance as the single active channel.

#### Scenario: Browse lists each catalogue instance
- **WHEN** an Android Auto Media browser subscribes to the Subspace root
- **THEN** the system SHALL return one Media item per catalogue instance in catalogue order
- **AND** each item's mediaId SHALL be the instance's stable identifier
- **AND** each item's title SHALL be the instance's display name
- **AND** each item's subtitle SHALL encode ACTIVE, READY, or STANDBY status

#### Scenario: Multiple instances share a kind
- **WHEN** the catalogue contains multiple instances of the same channel kind
- **THEN** Android Auto SHALL expose each as a separate Media item using its instance ID and display name

#### Scenario: Selecting a browse item sets the active channel
- **WHEN** the user selects a nonactive channel Media item
- **THEN** the system SHALL select that instance through the shared active-channel path
- **AND** the previously active instance SHALL become inactive
- **AND** the now-playing card SHALL reflect the newly active instance

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
- **WHEN** an instance's display name, readiness, active state, or execution status changes
- **THEN** the system SHALL call `notifyChildrenChanged`
- **AND** the next browse result SHALL reflect the updated projection

#### Scenario: Channel with non-zero pending count
- **WHEN** a channel projection reports a pending unheard count greater than zero
- **THEN** the system SHALL include the count in that channel's browse subtitle in compact form
- **AND** the count SHALL NOT appear when zero

#### Scenario: Channel ordering is stable across surfaces
- **WHEN** the same catalogue snapshot is rendered on the phone dashboard and Android Auto
- **THEN** ordering SHALL be identical on both surfaces
- **AND** ordering SHALL emanate solely from the persisted catalogue

#### Scenario: Legacy single-row browse contract remains removed
- **WHEN** an Android Auto Media browser subscribes to the Subspace root
- **THEN** the system SHALL NOT return the legacy item with `mediaId = "subspace-car-ptt"`
- **AND** consumers SHALL enumerate catalogue channel items

### Requirement: Now-playing card surfaces the active channel and live state
The system SHALL produce the MediaSession now-playing metadata for the Android Auto Media template from the currently active Subspace channel and the live PTT state.

#### Scenario: Now-playing title carries the active channel name
- **WHEN** the Android Auto now-playing card is rendered
- **THEN** the metadata title SHALL be the active channel's display name
- **AND** the metadata artist SHALL remain the Subspace app name

#### Scenario: Now-playing subtitle carries state pill and pending summary
- **WHEN** the active channel has zero pending unheard messages and the live state is Ready
- **THEN** the subtitle SHALL be a one-line state pill such as "ACTIVE" or "READY"
- **WHEN** the active channel has pending unheard messages greater than zero
- **THEN** the subtitle SHALL append a compact pending summary in the form "<count> pending"
- **WHEN** an inactive channel has pending unheard messages greater than zero
- **THEN** the subtitle MAY append a compact per-channel pending summary such as "<count> pending on <inactive channel>"
- **AND** the subtitle SHALL remain under 40 characters and SHALL truncate the pending portion first when space is tight

#### Scenario: Now-playing bitmap varies with live PTT state
- **WHEN** the live PTT state is NotReady, Ready, Recording, or Finalizing
- **THEN** the now-playing bitmap SHALL be one of four state-tinted bitmaps tinted with the Subspace visual identity palette
- **AND** the bitmap SHALL reflect recording color on Recording, playback color on Finalizing, dim surface color on Ready, and outline color on NotReady