## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel instance at any time, identified by a stable ID present in the persisted ordered channel catalogue. The active channel instance is the intended destination for PTT audio captures, subject to its runtime readiness evaluation. The migrated default catalogue SHALL include provider-backed Journal, Debug, and Keyboard instances, while the runtime SHALL support catalogue additions, removals, and multiple instances referencing the same implementation provider.

#### Scenario: One channel instance is active
- **WHEN** a channel instance is selected as active
- **THEN** PTT captures SHALL be evaluated against that instance's runtime readiness state
- **AND** every other catalogue instance SHALL be inactive

#### Scenario: Multiple instances share a provider
- **WHEN** the catalogue contains two instances referencing the same channel implementation provider
- **THEN** either instance SHALL be independently selectable by its stable instance ID
- **AND** selection SHALL NOT be inferred from provider identity or implementation type

### Requirement: Channel configuration persistence
The system SHALL persist the ordered provider-backed channel definitions across app restarts, including each definition's stable instance ID, stable provider reference, display name, enabled state, versioned opaque configuration payload, and list position. Configuration changes SHALL be addressed by stable instance ID, validated and migrated by the referenced provider, and take effect for subsequent PTT preparation without requiring a service restart. A configuration change SHALL NOT alter the target already committed to an active PTT session.

#### Scenario: App restarted after instance configuration
- **WHEN** the user configures a channel instance and the app is killed and restarted
- **THEN** that instance's ID, provider reference, name, position, enabled state, configuration schema version, and complete opaque payload SHALL be restored

#### Scenario: Provider configuration changed at runtime
- **WHEN** the user changes a channel instance's valid provider configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT preparation for that instance
- **AND** the current committed target, if any, SHALL retain its accepted configuration snapshot

#### Scenario: Configuration action targets one instance
- **WHEN** a provider-rendered editor changes a definition while another instance references the same provider
- **THEN** the action SHALL retain the editor's instance ID through navigation and persistence
- **AND** it SHALL NOT read or mutate a same-provider definition selected by list position, implementation identity, or a legacy singleton ID

#### Scenario: Unknown payload fields survive persistence
- **WHEN** a valid provider configuration payload contains fields the current host does not interpret
- **THEN** the host SHALL persist and restore those fields without loss
- **AND** the host SHALL NOT project the payload through a closed built-in configuration type

### Requirement: Channels consume audio input events
Channels SHALL consume PTT input through generic channel-level session events and audio supplied as high-level opaque handles by host-owned capabilities. Before the host plays the ready beep, a channel runtime SHALL expose whether it can accept the candidate input and, if accepted, return a committed target. Channels SHALL NOT receive `ResolvedAudioRoute`, `ScoRoute`, `PcmOutput`, `CaptureSource`, `InputMode`, Android audio route objects, Bluetooth HFP state, Telecom endpoint state, route-gate status, recorder diagnostic objects, or other platform transport objects.

#### Scenario: Active channel accepts input session
- **WHEN** PTT capture is being prepared for the active channel
- **AND** the runtime is configured, initialized, and able to process this input
- **THEN** the runtime SHALL provide an accepted committed channel input target to the host audio input subsystem
- **AND** the target SHALL consume generic session events and audio through opaque host-owned handles
- **AND** the channel SHALL NOT receive the route-readiness or platform facts used before commitment

#### Scenario: Active channel refuses input before ready beep
- **WHEN** PTT capture is being prepared for the active channel
- **AND** the runtime is missing required configuration, unavailable, uninitialized, or unable to create its input target
- **THEN** the runtime SHALL report a typed refusal to the host audio input subsystem
- **AND** the audio input subsystem SHALL NOT play the ready beep for that PTT
- **AND** route cleanup SHALL remain owned by the audio input subsystem

#### Scenario: Channel does not own input route cleanup
- **WHEN** PTT capture ends, fails, times out, or is cancelled
- **THEN** the committed channel target SHALL handle only its channel-domain terminal event and status
- **AND** the audio input subsystem SHALL release route, capture, and playback resources
- **AND** the channel SHALL NOT release SCO, Telecom, recorder, or local route resources directly

#### Scenario: Channel switch during active session
- **WHEN** the active channel selection changes while an audio input session is active
- **THEN** the session SHALL remain associated with the channel target accepted at session commitment until the session terminates
- **AND** changing channel selection SHALL NOT leak or release the active audio route outside the audio input subsystem

#### Scenario: Route or commitment failure is reported without route internals
- **WHEN** an input session fails because platform route state, recorder state, capability acquisition, or channel acceptance cannot prove a committed input path
- **THEN** the selected channel SHALL receive only a generic cancellation or failure event if a committed channel target exists
- **AND** the channel SHALL NOT receive Android route objects, transport state, route-gate internals, or detailed host diagnostics

## ADDED Requirements

### Requirement: Channel runtimes depend only on generic events and host capabilities
A channel implementation SHALL express behavior through provider-validated configuration, generic lifecycle and input events, and semantic host capability interfaces. Host capabilities SHALL expose opaque high-level handles or domain results and SHALL retain ownership of Android resources, hardware transports, connection and reconnection policy, concurrency, cleanup, and detailed diagnostics. Core channel dispatch SHALL NOT branch on built-in implementation identity to deliver events or capabilities.

#### Scenario: Runtime requests a semantic host effect
- **WHEN** a runtime needs text output, audio playback, or another host-owned effect
- **THEN** it SHALL request the operation through the corresponding semantic host capability
- **AND** it SHALL NOT receive or control the underlying Android, Bluetooth, Telecom, recorder, filesystem-transport, or connection object

#### Scenario: Generic event reaches different providers
- **WHEN** two runtimes from different providers accept the same generic channel input event
- **THEN** the host SHALL dispatch that event through the same provider-neutral runtime contract
- **AND** core dispatch SHALL NOT inspect provider identity or a built-in channel kind

#### Scenario: Host capability is unavailable
- **WHEN** a runtime requests a semantic capability that the host cannot currently provide
- **THEN** the capability SHALL return a generic typed unavailable or failed result
- **AND** the runtime SHALL NOT be given platform-specific state or permission to implement host connection or retry policy
