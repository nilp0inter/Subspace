## ADDED Requirements

### Requirement: Audio route gates use observed OS state
The audio input subsystem SHALL treat route transition success as satisfied only by observed route/capture facts reported by Android APIs or subsystem-owned capture results. Elapsed time SHALL only bound waiting and SHALL NOT by itself prove that a route is ready, released, or safe for capture.

#### Scenario: Timeout fails a route transition
- **WHEN** an audio input session is waiting for a route acquisition, route release, or capture-readiness gate
- **AND** the configured timeout expires before the required observed facts are true
- **THEN** the audio input subsystem SHALL fail or cancel the pending session
- **AND** SHALL NOT deliver a channel input started event
- **AND** SHALL release any route resources owned by that session exactly once

#### Scenario: Observed route facts satisfy a transition
- **WHEN** an audio input session is waiting for a route transition
- **AND** the subsystem observes all required route facts for the selected input mode
- **THEN** the session may proceed to the next setup phase
- **AND** the selected channel remains unaware of the route facts used to make that decision

### Requirement: Channel start follows capture-readiness proof
The audio input subsystem SHALL deliver channel input start only after route readiness and capture startup have both succeeded for the active session. A route that is only partially ready SHALL result in cancellation or failure rather than a channel-visible capture session.

#### Scenario: Route readiness succeeds but capture startup fails
- **WHEN** the selected route reports readiness
- **AND** the capture source cannot be opened, is silenced by Android, or otherwise cannot be proven usable by the subsystem
- **THEN** the audio input subsystem SHALL report channel input failure or cancellation
- **AND** SHALL release the active session route exactly once
- **AND** SHALL NOT deliver `Started` to the selected channel

#### Scenario: Capture is ready before channel handoff
- **WHEN** the route gate succeeds
- **AND** the capture service returns a running capture session for the active session
- **THEN** the audio input subsystem SHALL deliver a channel input session to the channel selected at session start
- **AND** the channel input session SHALL expose only stream/sample-rate data allowed by the channel input contract
