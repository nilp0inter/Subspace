## ADDED Requirements

### Requirement: Work route switches away require observed release
When switching from a warm or active Work/RSM SCO route to any non-Work input mode, the system SHALL require observed release of the target RSM audio path before the next route may proceed as ready. A timeout while waiting for release SHALL fail closed and SHALL NOT be treated as successful release.

#### Scenario: Warm Work route reused by Work
- **WHEN** the Work/RSM SCO route is warm from a previous Work session
- **AND** the next PTT source selects `Work`
- **THEN** the system SHALL reuse the warm Work route when the route is still owned by the target RSM
- **AND** SHALL NOT force a release/reacquire cycle solely because the route is warm

#### Scenario: Warm Work route blocks On-the-road until released
- **WHEN** the Work/RSM SCO route is warm from a previous Work session
- **AND** the next PTT source selects `OnTheRoad`
- **THEN** the system SHALL request immediate release of the target RSM audio path
- **AND** SHALL wait until Android reports the target RSM HFP audio is disconnected and the communication device is not the selected RSM SCO route
- **AND** SHALL NOT proceed to On-the-road capture setup until those facts are true

#### Scenario: Work release timeout fails closed
- **WHEN** the system is switching away from a warm or active Work/RSM route
- **AND** Android does not report the target RSM audio path released before the configured timeout
- **THEN** the system SHALL fail or cancel the next route setup
- **AND** SHALL NOT infer release from elapsed time
- **AND** SHALL leave no newly-started non-Work capture session behind

#### Scenario: Warm Work route does not block phone after observed release
- **WHEN** the Work/RSM SCO route is warm from a previous Work session
- **AND** the next PTT source selects `OnAPinch`
- **THEN** the system SHALL ensure the Work route is no longer the active communication route before local microphone capture is reported as started
- **AND** the phone channel input flow SHALL remain route-object-free
