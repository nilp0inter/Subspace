## ADDED Requirements

### Requirement: No-playback route release does not use playback
When a PTT channel has no response audio to play, the controller SHALL release the selected audio route by calling `route.output.releaseRoute()` directly. The controller MUST NOT use `route.output.play()` with an empty `RecordedPcm` as a route-release trigger.

#### Scenario: No-response controller releases route directly
- **WHEN** a PTT cycle completes and the active channel has no response audio
- **THEN** the controller SHALL call `route.output.releaseRoute()`
- **AND** the controller SHALL NOT call `route.output.play()` solely to trigger route release

#### Scenario: Telecom release without playback does not invoke media playback
- **WHEN** `TelecomCapturePcmOutput.releaseRoute()` is called
- **THEN** the system SHALL release the Telecom/SCO capture route
- **AND** the system SHALL await Telecom disconnect
- **AND** the system SHALL NOT call `MediaResponsePlayer.play()`
