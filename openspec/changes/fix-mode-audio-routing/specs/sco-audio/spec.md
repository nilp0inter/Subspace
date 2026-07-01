## MODIFIED Requirements

### Requirement: SCO route is acquired on PTT press
The system SHALL acquire the Bluetooth SCO communication route for the endpoint required by the active audio mode when the user presses PTT while any SCO-backed audio-capturing mode is enabled.

#### Scenario: SCO is inactive on PTT press
- **WHEN** SCO is inactive, an audio-capturing mode is enabled, and the user presses PTT
- **THEN** the system sets the audio manager mode to `MODE_IN_COMMUNICATION`
- **AND** the system sets the mode-required SCO device as the communication device
- **AND** the system polls for the communication device to become the selected `TYPE_BLUETOOTH_SCO` endpoint
- **AND** the system reports SCO state transitions: `Inactive -> Starting -> Active`

#### Scenario: Target SCO is already active on PTT press
- **WHEN** the mode-required SCO endpoint is already active and warm from a previous session
- **AND** the user presses PTT
- **THEN** the system returns immediately without re-acquisition
- **AND** the system reports SCO state `Active`

#### Scenario: Different SCO endpoint is already active on PTT press
- **WHEN** a Bluetooth SCO endpoint is active for a different physical device than the endpoint required by the active mode
- **AND** the user presses PTT
- **THEN** the system SHALL NOT treat the existing active SCO route as a valid acquisition
- **AND** the system SHALL acquire or fail against the endpoint required by the active mode

#### Scenario: SCO acquisition times out
- **WHEN** the system polls for 5 seconds and the selected SCO route does not become active
- **THEN** the system reports SCO state `Failed("Timed out waiting for SCO route")`
- **AND** the system returns acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: SCO device is not found
- **WHEN** no Bluetooth SCO device matching the endpoint required by the active mode is available during acquisition
- **THEN** the system reports SCO state `Failed("Bluetooth SCO headset not available")`
- **AND** the system returns acquisition failure

### Requirement: AudioTrack routes through SCO device
The system SHALL explicitly route all SCO-backed `PcmOutput` AudioTrack instances through the selected SCO communication device for the active mode to guarantee the audio reaches the intended Bluetooth endpoint.

#### Scenario: AudioTrack is created with SCO device preference
- **WHEN** the system creates an AudioTrack for beep playback or audio playback on a SCO-backed route
- **THEN** the system SHALL set the AudioTrack's preferred device to the selected SCO communication device for that route
- **AND** the preferred device SHALL match the endpoint required by the active mode

#### Scenario: Work playback targets RSM SCO
- **WHEN** the system plays captured or synthesized PCM while in `Work` mode
- **THEN** the AudioTrack SHALL prefer the B02PTT-FF01 RSM communication device
- **AND** the PCM SHALL NOT rely on Android default routing policy to choose between car and RSM outputs

#### Scenario: No matching SCO device is active
- **WHEN** a SCO-backed output attempts to create an AudioTrack and no matching selected SCO communication device is active
- **THEN** the output SHALL fail the route or report an error rather than intentionally routing through another mode's endpoint
