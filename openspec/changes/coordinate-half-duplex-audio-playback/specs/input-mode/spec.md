## ADDED Requirements

### Requirement: Selected mode is authoritative for channel-content output
The selected `InputMode` SHALL determine the semantic output endpoint for every newly admitted channel-content playback operation: `Work` SHALL target the owned RSM HFP/SCO endpoint, `OnTheRoad` SHALL target the car output, and `OnAPinch` SHALL target the phone output. The host SHALL snapshot the mode after playback admission and immediately before route acquisition, and failure to acquire that mode's endpoint SHALL NOT cause fallback to another mode.

#### Scenario: Work response playback is admitted
- **WHEN** channel-content playback is admitted while `Work` is selected
- **THEN** the host SHALL acquire and use the target RSM-owned playback route
- **AND** it SHALL NOT play through the car or phone if Work route acquisition fails

#### Scenario: On-the-road response playback is admitted
- **WHEN** channel-content playback is admitted while `OnTheRoad` is selected
- **THEN** the host SHALL acquire or validate the car playback route
- **AND** it SHALL NOT play through the RSM or phone if the car route is unavailable

#### Scenario: On-a-pinch response playback is admitted
- **WHEN** channel-content playback is admitted while `OnAPinch` is selected
- **THEN** the host SHALL acquire or validate the phone playback route
- **AND** it SHALL NOT play through the RSM or car if the phone route is unavailable

### Requirement: Active playback is not redirected by mode changes
A mode change after playback route acquisition SHALL affect later admission only and SHALL NOT redirect an already active playback operation. After that operation completes, is skipped, is interrupted, or fails and releases its route, every later operation SHALL resolve the then-current mode.

#### Scenario: Mode changes during active playback
- **WHEN** the user changes mode while a response is already playing
- **THEN** the response SHALL continue on its acquired endpoint without redirection
- **AND** the next admitted response SHALL resolve the newly selected mode

### Requirement: Rejected PTT during playback does not apply actuator home-mode transition
Actuator auto-transition SHALL occur only after the half-duplex host accepts PTT admission. A PTT press rejected because response playback is active SHALL leave mode and selection provenance unchanged.

#### Scenario: Phone PTT is rejected during Work playback
- **WHEN** Work is selected, response playback is active, and phone PTT is pressed
- **THEN** the host SHALL reject PTT without transitioning to `OnAPinch`
- **AND** Work SHALL remain the selected mode
