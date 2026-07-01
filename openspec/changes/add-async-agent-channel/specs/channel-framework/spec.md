## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain at most one active channel at any time. The active channel is the destination for new PTT audio captures. Pending asynchronous work owned by an inactive channel SHALL NOT make that channel active.

#### Scenario: One channel is active
- **WHEN** a channel is configured and enabled
- **THEN** PTT captures SHALL be dispatched to that channel

#### Scenario: No channel is active
- **WHEN** no channel is configured or all channels are disabled
- **THEN** PTT captures SHALL NOT be dispatched to any channel

#### Scenario: Agent job completes while another channel is active
- **WHEN** an Agent channel job completes while another channel is active
- **THEN** the system SHALL keep the currently active channel unchanged
- **AND** the Agent response SHALL become pending

### Requirement: Channel activation mutual exclusion
The system SHALL ensure that activating a channel immediately deactivates all other channels for new PTT routing. Deactivation SHALL NOT cancel asynchronous Agent channel jobs that were already submitted after capture finalization.

#### Scenario: Channel activated while another is active
- **WHEN** Channel A is active and the user activates Channel B
- **THEN** the system SHALL deactivate Channel A and set Channel B as the only active channel
- **AND** PTT captures SHALL be routed to Channel B

#### Scenario: Agent channel deactivated while job is thinking
- **WHEN** the Agent channel has a submitted job waiting for an LLM response
- **AND** the user activates another channel
- **THEN** new PTT captures SHALL route to the newly active channel
- **AND** the Agent job SHALL continue independently unless explicitly cancelled by service teardown

### Requirement: Channel configuration persistence
The system SHALL persist channel configuration across app restarts. Runtime-only pending Agent channel responses are not channel configuration and SHALL NOT be required to persist in V1.

#### Scenario: App restarted after channel configuration
- **WHEN** the app restarts after a channel has been configured
- **THEN** the channel SHALL restore its previous configuration

#### Scenario: Configuration changed at runtime
- **WHEN** a user changes channel configuration
- **THEN** the active channel state SHALL reflect the new configuration immediately

#### Scenario: App restarted with pending agent responses
- **WHEN** the service process stops while Agent channel responses are pending
- **THEN** the system SHALL NOT be required to restore those pending responses after restart
