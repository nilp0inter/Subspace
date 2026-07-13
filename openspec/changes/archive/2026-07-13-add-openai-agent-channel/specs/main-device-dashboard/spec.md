## ADDED Requirements

### Requirement: Dashboard projects asynchronous channel execution and responses
The dashboard SHALL render each channel's host-owned asynchronous projection alongside its existing catalogue identity, readiness, active state, and execution status. The projection SHALL expose queued user-turn count, processing state, pending unheard response count, and playback-pending state when a response is waiting for channel selection or audio admission. The dashboard SHALL update these values from host projections without importing provider SDK types, Android media objects, audio-route objects, or transport objects.

#### Scenario: Channel turn is processing
- **WHEN** a channel has a released user turn in queued, transcribing, running, waiting-for-tool, or synthesizing work
- **THEN** its dashboard card SHALL present a host-owned processing state
- **AND** the card SHALL expose the queued user-turn count when one or more turns are waiting
- **AND** the card SHALL remain selectable as the active channel
- **AND** the card SHALL NOT expose provider or SDK implementation details

#### Scenario: Channel has pending responses
- **WHEN** a channel projection reports one or more pending unheard assistant responses
- **THEN** the dashboard card SHALL present the pending response indication and count
- **AND** the count SHALL remain associated with that channel's stable instance ID
- **AND** the card SHALL remain visible when another channel is active

#### Scenario: Pending response is heard
- **WHEN** pending response playback completes or the user explicitly skips the response
- **THEN** the dashboard SHALL refresh the channel projection
- **AND** the card SHALL reduce or remove the pending unheard indication according to the updated count

#### Scenario: Processing or playback projection changes
- **WHEN** a channel transitions between idle, processing, pending, playback, completed, or error state
- **THEN** the dashboard SHALL render the latest host-owned projection without changing catalogue order or stable identity

### Requirement: Dashboard manages global OpenAI-compatible connection profiles
The dashboard SHALL provide host-rendered management for global OpenAI-compatible connection profiles independently of channel instances. Each profile SHALL have a stable profile ID, display name, base URL, host-owned bearer credential, model-discovery availability, and typed availability or error state. A global profile SHALL NOT contain a channel-specific model selection. The dashboard SHALL never render a stored credential in clear text, SHALL NOT expose credentials to channel implementations, and SHALL NOT require per-call authorization controls.

#### Scenario: Create a connection profile
- **WHEN** the user submits a valid display name, base URL, and bearer credential in the global profile form
- **THEN** the host SHALL persist a new profile with a stable profile ID
- **AND** the profile SHALL appear in the global profile list with its host-owned availability state
- **AND** the dashboard SHALL not display the stored credential value after submission

#### Scenario: Edit a connection profile
- **WHEN** the user edits a persisted profile's display name, base URL, or credential and submits valid values
- **THEN** the host SHALL update that profile by stable profile ID
- **AND** channel configurations referring to that profile SHALL continue to refer to the same profile ID
- **AND** the dashboard SHALL refresh the profile's discovery and availability state

#### Scenario: Model discovery is unavailable
- **WHEN** a profile's models endpoint cannot be reached or returns an error
- **THEN** the dashboard SHALL retain the profile in the global list
- **AND** it SHALL show a typed unavailable or error state and recovery action
- **AND** it SHALL NOT fabricate model choices

#### Scenario: Remove a profile referenced by a channel
- **WHEN** the user removes a global profile that is referenced by one or more channel instances
- **THEN** the host SHALL preserve each channel instance and its stable configuration
- **AND** each affected channel SHALL report a missing-profile unavailable state
- **AND** no affected channel card SHALL be silently removed from the dashboard

### Requirement: Dashboard configures an OpenAI Agent channel from host-owned profile and model choices
The dashboard SHALL provide a host-rendered native configuration form for an OpenAI Agent channel instance. The form SHALL select one global connection profile by stable profile ID, select one model ID discovered from that selected profile's models endpoint, accept a multiline system prompt, and optionally enable configured Keyboard tools with a selected host keyboard profile or layout. The channel SHALL store the model selection by channel instance rather than in the global profile. Tool calls enabled by this configuration SHALL execute automatically without a per-call authorization prompt.

#### Scenario: Configure an agent channel with discovered model
- **WHEN** the user opens an OpenAI Agent channel configuration form
- **AND** at least one global profile has available discovered models
- **THEN** the form SHALL offer the available profiles and models as host-owned choices
- **AND** submitting valid values SHALL persist the selected profile ID, model ID, multiline system prompt, and Keyboard-tool settings on that channel instance
- **AND** the form SHALL NOT request or display a channel-specific credential

#### Scenario: Model choices are scoped to the selected profile
- **WHEN** the user changes the selected global profile
- **THEN** the form SHALL refresh model choices from that profile's discovery result
- **AND** it SHALL NOT offer models discovered only from another profile as if they were available
- **AND** an unavailable prior model selection SHALL be reported as invalid or unavailable until replaced

#### Scenario: Keyboard tools are enabled
- **WHEN** the user enables Keyboard tools for an agent channel
- **THEN** the form SHALL require a selected host keyboard profile or layout
- **AND** the enabled tool set SHALL include `type_text` and `press_enter` actions
- **AND** the dashboard SHALL persist only the host-owned keyboard selection and tool configuration, not Android input objects

#### Scenario: Profile or model is unavailable during configuration
- **WHEN** the selected profile is missing, unavailable, or has no discoverable selected model
- **THEN** the form SHALL preserve the channel instance and its non-secret configuration
- **AND** it SHALL identify the missing profile or model with a host-owned recovery path
- **AND** it SHALL prevent the channel from being presented as ready solely because an old model ID remains stored

### Requirement: Dashboard keeps agent channels visible when their profile is missing
The dashboard SHALL keep an OpenAI Agent channel card visible at its persisted catalogue position when its configured global profile is absent, deleted, incompatible, or unavailable. Such a card SHALL retain its stable instance ID and display name, SHALL show a host-owned unavailable reason, and SHALL offer profile management or reconfiguration access. Selecting the card SHALL still use the shared active-channel path, while a PTT attempt SHALL follow host-owned unavailable feedback without starting capture.

#### Scenario: Configured profile is missing
- **WHEN** an agent channel references a profile ID that is not present in global profile storage
- **THEN** the dashboard SHALL render the channel card in an unavailable state at its existing position
- **AND** the card SHALL identify that the connection profile is missing
- **AND** the card SHALL offer an action to add or select a profile

#### Scenario: Missing-profile card is selected
- **WHEN** the user taps a missing-profile agent channel card
- **THEN** the system SHALL set that channel's stable instance ID as active
- **AND** the card SHALL retain its unavailable diagnostic
- **AND** the dashboard SHALL NOT start capture or execute provider-controlled UI

#### Scenario: Profile becomes available again
- **WHEN** a profile with the configured stable profile ID is restored or becomes available
- **THEN** the host SHALL re-evaluate the affected channel's profile and model readiness
- **AND** the dashboard SHALL refresh the card without replacing its instance ID or silently changing its model selection
