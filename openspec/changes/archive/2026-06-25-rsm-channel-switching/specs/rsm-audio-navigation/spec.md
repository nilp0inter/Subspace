## ADDED Requirements

### Requirement: RSM Control Mode Navigation
The system SHALL map the `B02PTT-FF01` physical Group button, Volume Up button, Volume Down button, and PTT button to drive a system-level navigation menu.

#### Scenario: Entering Control Mode
- **WHEN** the user presses the Group button while in Active Mode
- **THEN** the hardware SHALL enter Control Mode

#### Scenario: Navigating to next channel
- **WHEN** the user clicks the Volume Up button while in Control Mode
- **THEN** the system SHALL calculate the next available channel
- **AND** the system SHALL set that channel as the globally active channel

#### Scenario: Navigating to previous channel
- **WHEN** the user clicks the Volume Down button while in Control Mode
- **THEN** the system SHALL calculate the previous available channel
- **AND** the system SHALL set that channel as the globally active channel

#### Scenario: Confirming channel selection
- **WHEN** the user presses the PTT button while in Control Mode
- **THEN** the system SHALL return the hardware to Active Mode without transmitting audio

### Requirement: Headless Audio Feedback
The system SHALL play zero-latency audio announcements over the headset to confirm hardware-driven navigation actions, using a memoized TTS announcer that interrupts running playbacks.

#### Scenario: Menu entry announcement
- **WHEN** the hardware enters Control Mode
- **THEN** the system SHALL play a pre-computed "Channels" announcement over the SCO headset route

#### Scenario: Channel selection announcement
- **WHEN** the user switches channels via Volume Up or Volume Down
- **THEN** the system SHALL cancel any currently playing announcement
- **AND** play the pre-computed name of the new active channel (e.g., "Journal Channel") over the SCO headset route

#### Scenario: Confirmation announcement
- **WHEN** the user presses PTT to confirm selection and exit Control Mode
- **THEN** the system SHALL cancel any currently playing announcement
- **AND** play a pre-computed confirmation phrase (e.g., "Journal Channel Selected") over the SCO headset route

#### Scenario: Fallback audio
- **WHEN** an announcement is triggered but the pre-computed TTS cache is missing or the model is not ready
- **THEN** the system SHALL fall back to playing a distinct ready beep over the SCO headset route

### Requirement: Synchronized UI Highlighting
The system SHALL automatically synchronize the visual state of the Main Dashboard with the hardware-driven channel selection.

#### Scenario: Hardware scrolls channel list
- **WHEN** the user switches channels via the RSM buttons
- **THEN** the Main Dashboard channel cards SHALL instantly visually reflect the new active channel selection
