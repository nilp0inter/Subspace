## ADDED Requirements

### Requirement: Android Auto projects processing and pending response state
The Android Auto browse projection SHALL derive each channel item's status metadata from the host-owned channel projection. For an agent channel, the projection SHALL identify processing state and pending unheard assistant response count without exposing provider SDK, Android, audio-route, or transport objects. A non-zero pending count SHALL remain visible while another channel is active, and status changes SHALL invalidate subscribed browse clients through the existing browse-change notification path.

#### Scenario: Agent run is processing
- **WHEN** an agent channel has queued work or an asynchronous run is transcribing, running, waiting for a tool, synthesizing, or in an error state
- **THEN** the channel's Media item subtitle or equivalent host-owned metadata SHALL identify that processing state
- **AND** the item SHALL retain its stable channel instance ID as media identity
- **AND** Android Auto SHALL NOT need to execute provider code to render the state
#### Scenario: Queued user turns are visible
- **WHEN** one or more user turns are queued for an agent channel
- **THEN** the channel's Media item metadata SHALL expose the queued user-turn count
- **AND** the count SHALL remain tied to the stable channel instance ID
- **AND** Android Auto SHALL NOT need to execute provider code to render the count

#### Scenario: Pending response count is non-zero
- **WHEN** the channel projection reports one or more pending unheard assistant responses
- **THEN** the channel's Media item SHALL include a compact pending count in host-rendered metadata
- **AND** the count SHALL remain visible even when the channel is not active

#### Scenario: Pending response becomes heard
- **WHEN** pending response playback completes or the user explicitly skips the response
- **THEN** the system SHALL refresh the affected Media item metadata
- **AND** the pending count SHALL decrease or disappear according to the updated projection
- **AND** the system SHALL notify subscribed browser clients that the browse projection changed

#### Scenario: Processing or pending state changes
- **WHEN** a channel's processing state, pending count, playback state, active state, or availability changes
- **THEN** the system SHALL call `notifyChildrenChanged`
- **AND** the next browse load SHALL reflect the latest host-owned projection without changing media identity or catalogue order

### Requirement: Android Auto selection admits pending playback only for the selected channel
The Android Auto channel selection path SHALL use the shared active-channel mutation. When a response arrives for the selected channel and playback is admitted, the host SHALL play it independently of an Android Auto PTT callback. When a response arrives for an unselected channel, or output is contended, the host SHALL retain it pending and unheard. Selecting a channel with pending responses SHALL make those responses eligible for FIFO playback when the audio subsystem admits output; Android Auto SHALL NOT mark a response heard merely because its Media item was displayed or selected.

#### Scenario: Response arrives while its channel is selected
- **WHEN** an assistant response arrives for the active Android Auto channel
- **AND** host audio playback is admitted
- **THEN** the host SHALL begin response playback without requiring a new Android Auto PTT action
- **AND** the response SHALL remain pending until playback completes or the user explicitly skips it

#### Scenario: Response arrives while another channel is selected
- **WHEN** an assistant response arrives for a channel that is not active in Android Auto
- **THEN** the host SHALL leave the response pending and unheard
- **AND** the channel's browse item SHALL expose the pending count
- **AND** no playback SHALL begin for that inactive channel

#### Scenario: User selects a channel with pending responses
- **WHEN** an Android Auto client selects a channel Media item with pending unheard responses
- **THEN** the system SHALL set that channel as the single active channel through the shared selection path
- **AND** the host SHALL schedule its pending responses in durable arrival order when playback is admitted
- **AND** selecting the item alone SHALL NOT mark any response heard

#### Scenario: Output is contended on return
- **WHEN** an Android Auto client selects a channel with pending responses
- **AND** another host-owned output operation occupies audio
- **THEN** the host SHALL retain the responses pending and unheard
- **AND** it SHALL attempt playback after the operation releases audio without dropping or reordering responses

### Requirement: Android Auto preserves visibility and recovery for a missing agent profile
Android Auto SHALL retain a persisted OpenAI Agent channel as a playable Media item when its configured global connection profile is missing, deleted, incompatible, or unavailable. The item SHALL retain its stable instance ID as `mediaId`, display name, catalogue position, and host-rendered unavailable reason with a phone recovery direction. Selecting the item SHALL activate it through the shared selection path, but an unavailable profile SHALL prevent PTT capture and asynchronous submission until host-owned recovery succeeds.

#### Scenario: Missing profile appears in the browse tree
- **WHEN** a persisted agent channel references a profile ID that is absent from global profile storage
- **THEN** Android Auto SHALL include one Media item for that channel at its catalogue position
- **AND** the item's `mediaId` SHALL remain the stable channel instance ID
- **AND** its metadata SHALL identify the missing profile and direct recovery to the phone

#### Scenario: User selects a missing-profile item
- **WHEN** an Android Auto client selects the missing-profile Media item
- **THEN** the system SHALL set that channel instance as active
- **AND** the now-playing metadata SHALL retain the unavailable reason and phone recovery direction
- **AND** a subsequent Android Auto PTT attempt SHALL follow host-owned problem feedback without starting capture

#### Scenario: Profile recovery changes availability
- **WHEN** the configured profile is restored or becomes available again
- **THEN** the system SHALL notify subscribed Android Auto clients that the browse projection changed
- **AND** metadata SHALL reflect the current profile and model availability
- **AND** metadata SHALL preserve the channel's selected model ID until the user changes configuration
