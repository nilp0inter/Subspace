## ADDED Requirements

### Requirement: Telecom lifecycle termination is scoped to CarTelecom ownership
A Telecom route timeout, connection end, setup failure, abort, or forced car release SHALL terminate only a pending or active session owned by `PttSource.CarTelecom`. A late or stale Telecom callback SHALL NOT cancel Phone or RSM input. Telecom coordinator cleanup MAY still release its own reservation, connection, retry, and stability state when no CarTelecom audio session is owned.

#### Scenario: Route timeout owns the pending car session
- **WHEN** a CarTelecom session is pending and its exact car route does not become acceptable before the configured timeout
- **THEN** the timeout SHALL cancel that CarTelecom session exactly once
- **AND** SHALL disconnect and clean the associated Telecom connection
- **AND** SHALL provide problem feedback when possible

#### Scenario: Route timeout arrives during Phone input
- **WHEN** a late Telecom route timeout arrives while a Phone session is pending or active
- **THEN** the system SHALL preserve the Phone session, capture, target, route, and lease
- **AND** SHALL limit cleanup to stale Telecom-owned state

#### Scenario: Route timeout arrives during RSM input
- **WHEN** a late Telecom route timeout arrives while an RSM session is pending or active
- **THEN** the system SHALL preserve the RSM session, capture, target, route, and lease
- **AND** SHALL limit cleanup to stale Telecom-owned state

#### Scenario: Car setup failure races a replacement source
- **WHEN** asynchronous CarTelecom setup failure arrives after its pending session has terminated
- **AND** another source now owns the active audio input session
- **THEN** CarTelecom cleanup SHALL NOT terminate the replacement session
- **AND** SHALL release only the failed car operation's remaining reservation and connection state

#### Scenario: Active car connection ends
- **WHEN** the active Telecom connection ends while a CarTelecom capture owns the session
- **THEN** the existing car normal-release or cancellation semantics SHALL apply exactly once
- **AND** unrelated later callbacks SHALL NOT claim a second terminal owner
