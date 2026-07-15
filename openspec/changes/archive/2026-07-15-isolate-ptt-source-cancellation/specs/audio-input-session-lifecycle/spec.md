## ADDED Requirements

### Requirement: Non-global audio input cancellation is source-scoped
Every cancellation signal caused by a source-specific lifecycle SHALL identify its expected `PttSource`. The audio input subsystem SHALL claim cancellation only when the current pending or active session is owned by that source. A source mismatch or absent session SHALL be a no-op for audio-session state, capture, committed channel target, route, and capture lease. Exactly one explicitly global teardown operation MAY cancel a session regardless of source and SHALL be reserved for whole-service shutdown or equivalent process-wide invalidation.

#### Scenario: Matching source cancels its pending session
- **WHEN** a source-specific lifecycle requests cancellation for a pending session owned by the same source
- **THEN** cancellation SHALL claim terminal ownership exactly once
- **AND** the pending session, route, target lease, and capture admission SHALL be cleaned according to the existing terminal sequence

#### Scenario: Matching source cancels its active capture
- **WHEN** a source-specific lifecycle requests cancellation for an active capture owned by the same source
- **THEN** cancellation SHALL claim terminal ownership exactly once
- **AND** the committed target SHALL receive the existing cancellation terminal event
- **AND** route and lease cleanup SHALL remain exactly once

#### Scenario: Mismatched source cannot cancel current session
- **WHEN** a source-specific lifecycle requests cancellation for one source
- **AND** the current pending or active session belongs to a different source
- **THEN** the subsystem SHALL reject the cancellation request
- **AND** SHALL leave the current session, capture, target, route, and lease unchanged

#### Scenario: Source-specific cancellation arrives with no active session
- **WHEN** a source-specific lifecycle requests cancellation while no pending or active session exists
- **THEN** the subsystem SHALL perform no terminal effects

#### Scenario: Whole-service shutdown remains global
- **WHEN** whole-service teardown invalidates all application-owned audio work
- **THEN** the subsystem MAY cancel the current session regardless of source
- **AND** SHALL preserve the existing atomic terminal ownership and exactly-once cleanup guarantees
