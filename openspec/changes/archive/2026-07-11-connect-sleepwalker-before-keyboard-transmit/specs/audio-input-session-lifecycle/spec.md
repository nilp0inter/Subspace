## ADDED Requirements

### Requirement: Recoverable channel preparation remains pending and uncommitted
The audio input subsystem SHALL include recoverable channel preparation within the single active PTT session lifecycle. While preparation is pending, the session SHALL reserve input ownership but SHALL NOT have a committed channel target, play the ready beep, or deliver channel input start. Preparation success SHALL permit normal setup to continue only while the same session remains active and PTT remains held; preparation failure or timeout SHALL produce problem feedback when possible and SHALL release session-owned route resources exactly once.

#### Scenario: Keyboard connection recovery reserves the session
- **WHEN** a Keyboard PTT session is waiting for the Sleepwalker bridge to connect
- **AND** another PTT source requests capture
- **THEN** the system SHALL reject or ignore the competing request
- **AND** the pending Keyboard session SHALL remain the sole audio input session owner

#### Scenario: Recovery succeeds while PTT remains held
- **WHEN** recoverable channel preparation succeeds
- **AND** the same PTT session is still active and held
- **THEN** the system SHALL commit the accepted channel target
- **AND** SHALL continue through capture preflight and the ready beep exactly once

#### Scenario: Recovery fails before commitment
- **WHEN** recoverable channel preparation fails or times out before a channel target is committed
- **THEN** the system SHALL NOT start capture or play the ready beep
- **AND** SHALL play the problem beep on the resolved route when safe
- **AND** SHALL release the route exactly once
- **AND** SHALL clear the active session after cleanup is requested

#### Scenario: PTT is released during recovery
- **WHEN** the owning PTT source releases while recoverable channel preparation is pending
- **THEN** normal release SHALL claim the session terminal state
- **AND** the system SHALL NOT play the ready beep or deliver channel input start
- **AND** a later preparation result SHALL NOT revive the session
- **AND** session-owned route resources SHALL be released exactly once

#### Scenario: Session is cancelled during recovery
- **WHEN** the pending recoverable session is force-cancelled, its source is lost, or the service is torn down
- **THEN** cancellation SHALL claim the session terminal state
- **AND** the system SHALL NOT play the ready beep or deliver channel input start
- **AND** a later preparation result SHALL NOT revive the session
- **AND** session-owned route resources SHALL be released exactly once

#### Scenario: Repeated press does not duplicate recovery
- **WHEN** the owning PTT source reports another press while its recoverable preparation is pending
- **THEN** the system SHALL NOT create another audio input session
- **AND** SHALL NOT request a second channel recovery operation
