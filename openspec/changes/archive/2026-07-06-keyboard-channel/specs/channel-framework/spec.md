## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel at any time, identified by
a unique ID. The active channel is the intended destination for PTT audio
captures, subject to readiness evaluation. The set of channels SHALL include
`JournalChannel`, `DebugChannel`, and `KeyboardChannel`.

#### Scenario: One channel is active
- **WHEN** a channel is selected as active
- **THEN** PTT captures SHALL be evaluated against that channel's readiness
  state

#### Scenario: Keyboard channel selected as active
- **WHEN** the user activates the keyboard channel
- **THEN** it SHALL become the sole active channel
- **AND** PTT captures SHALL be routed to it, provided it is ready

### Requirement: Channel configuration persistence
The system SHALL persist channel configuration across app restarts for all
channels, including `KeyboardChannel`. Configuration changes SHALL take effect
immediately without requiring a service restart.

#### Scenario: App restarted after keyboard channel configuration
- **WHEN** the user configures the keyboard channel's host profile and the app
  is killed and restarted
- **THEN** the keyboard channel configuration SHALL be restored to the
  previously saved state

#### Scenario: Keyboard host profile changed at runtime
- **WHEN** the user changes the keyboard channel's host profile while the
  service is running
- **THEN** the new profile SHALL take effect for the next PTT capture without
  restarting the service

## ADDED Requirements

### Requirement: Keyboard channel ordering
The system SHALL assign `KeyboardChannel` a stable `orderIndex` of 2, after
`JournalChannel` (0) and `DebugChannel` (1), so that channel ordering is stable
across the phone dashboard and the Android Auto browse tree.

#### Scenario: Channels ordered on dashboard
- **WHEN** the dashboard renders the channel list
- **THEN** the keyboard channel SHALL appear after the journal and debug
  channels

### Requirement: Keyboard channel in channel repository
`ChannelRepository.loadChannels()` SHALL include the persisted
`KeyboardChannel` in its returned list, and SHALL provide `loadKeyboard()` and
`saveKeyboard(channel)` methods.

#### Scenario: Load all channels
- **WHEN** `loadChannels()` is called
- **THEN** the returned list SHALL contain the journal, debug, and keyboard
  channels, ordered by `orderIndex` ascending
