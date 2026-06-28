## ADDED Requirements

### Requirement: Media browse tree lists each Subspace channel as a playable media item
The system SHALL expose each Subspace channel as a distinct playable Media item in the Android Auto Media browse tree, with the channel's display name as the item title and a per-channel status subtitle. Selecting the item from the head unit SHALL set that channel as the single active Subspace channel.

#### Scenario: Browse lists each configured channel
- **WHEN** an Android Auto Media browser client subscribes to the Subspace root
- **AND** the channel list contains Captain's Log configured and Debug Channel configured
- **THEN** the system SHALL return a Media item per configured channel
- **AND** each item's mediaId SHALL be the channel's stable identifier
- **AND** each item's title SHALL be the channel's display name
- **AND** each item's subtitle SHALL encode the per-channel status (ACTIVE, READY, or STANDBY)

#### Scenario: Selecting a browse item sets the active channel
- **WHEN** the user selects a channel media item from the head unit while that channel is not active
- **THEN** the system SHALL set that channel as the single active Subspace channel via the existing active-channel selection path
- **AND** the previously active channel SHALL become inactive
- **AND** the now-playing card SHALL reflect the newly active channel

#### Scenario: Channel list updates propagate to the head unit
- **WHEN** the channel list changes (a channel becomes configured, ready, or standby)
- **THEN** the system SHALL call `notifyChildrenChanged` on the open Media browse subscription
- **AND** the next `onLoadChildren` SHALL return the updated list

#### Scenario: Channel with non-zero pending count
- **WHEN** the channel list returns a channel whose pending unheard count is greater than zero
- **THEN** the system SHALL include the count in that channel's browse subtitle in a compact form
- **AND** the count SHALL NOT appear in the subtitle when zero

#### Scenario: Channel ordering is stable across surfaces
- **WHEN** the same set of channels is rendered on both the phone dashboard and the Android Auto Media browse tree
- **THEN** the ordering SHALL be identical on both surfaces
- **AND** the ordering SHALL emanate solely from the channel repository

#### Scenario: Browse list when no channels are ready
- **WHEN** no channels are configured or ready
- **THEN** the system SHALL return an empty Media browse list (or an explicit not-ready marker when the platform requires non-empty results)
- **AND** the now-playing card SHALL reflect NotReady state

#### Scenario: Legacy single-row browse contract removed
- **WHEN** an Android Auto Media browser client subscribes to the Subspace root
- **THEN** the system SHALL NOT return the legacy single item with `mediaId = "subspace-car-ptt"`
- **AND** external consumers (tests included) SHALL enumerate channel items instead

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