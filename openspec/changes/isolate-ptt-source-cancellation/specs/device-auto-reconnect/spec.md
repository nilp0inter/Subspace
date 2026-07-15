## ADDED Requirements

### Requirement: RSM serial lifecycle preserves unrelated PTT sessions
Automatic reconnect, failed connection, stream loss, and explicit disconnect of the RSM SPP control channel SHALL affect only RSM-owned PTT input. These events SHALL NOT cancel, release, abort, or change the presentation of a pending or active Phone or CarTelecom session. Eligible automatic reconnect scheduling SHALL continue under the existing serialized retry policy while unrelated PTT input is pending or active.

#### Scenario: Failed automatic reconnect during Phone capture
- **WHEN** an automatic RSM SPP reconnect attempt fails while a Phone session is pending or capturing
- **THEN** the Phone session SHALL remain owned and active
- **AND** reconnect policy SHALL schedule later eligible attempts without terminating Phone input

#### Scenario: Failed automatic reconnect during pending car route acquisition
- **WHEN** an automatic RSM SPP reconnect attempt fails while a CarTelecom session is waiting for its exact car route
- **THEN** the CarTelecom session SHALL continue waiting until its own route, timeout, user, or Telecom lifecycle terminates it
- **AND** the failed RSM attempt SHALL NOT abort the Telecom connection

#### Scenario: Failed automatic reconnect during active car capture
- **WHEN** an automatic RSM SPP reconnect attempt fails while a CarTelecom capture is active
- **THEN** the car capture and Telecom connection SHALL remain active
- **AND** reconnect policy SHALL remain eligible to retry later

#### Scenario: Active RSM serial session ends
- **WHEN** an RSM-owned PTT session is pending or active
- **AND** its owning SPP serial event stream ends
- **THEN** the system SHALL cancel that RSM-owned session through source-scoped cancellation
- **AND** SHALL retain existing automatic reconnect scheduling behavior

#### Scenario: Explicit serial disconnect during unrelated capture
- **WHEN** the user explicitly disconnects RSM serial monitoring while a Phone or CarTelecom session is pending or active
- **THEN** the system SHALL stop RSM monitoring and reconnect work according to the existing disconnect contract
- **AND** SHALL preserve the unrelated PTT session

#### Scenario: Explicit serial disconnect during RSM capture
- **WHEN** the user explicitly disconnects RSM serial monitoring while an RSM-owned capture is active
- **THEN** the system SHALL cancel the RSM-owned capture exactly once
- **AND** SHALL stop monitoring and reconnect work according to the existing disconnect contract
