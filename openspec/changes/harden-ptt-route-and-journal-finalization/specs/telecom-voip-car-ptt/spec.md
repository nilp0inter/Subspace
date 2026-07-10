## ADDED Requirements

### Requirement: Recorded Telecom hang finalizes the committed channel
The system SHALL treat a Telecom disconnect, abort, reject, or call-loss callback after car capture has started as a normal terminal release of the committed input target. The connection-ended callback SHALL not override that release with cancellation before terminal channel delivery completes.

#### Scenario: Driver hangs an active Journal car call
- **WHEN** an On-the-road Journal capture has started
- **AND** the driver ends the self-managed Telecom call
- **THEN** the system SHALL stop the capture once
- **AND** deliver its terminal recording to the committed Journal target once
- **AND** release the Telecom route exactly once after terminal channel handling

#### Scenario: Car connection ends before capture handoff
- **WHEN** an On-the-road Telecom connection ends before capture starts
- **THEN** the system SHALL cancel the pending audio input session
- **AND** SHALL NOT deliver a terminal recording to a channel
- **AND** SHALL release the resolved Telecom output route exactly once

### Requirement: Failed car HFP priming is cleaned before setup failure
When car HFP voice recognition was requested for On-the-road setup and its observed-audio readiness wait fails, the system SHALL stop voice recognition for that same car device before returning setup failure.

#### Scenario: Car HFP prime times out after start request
- **WHEN** `startVoiceRecognition(car)` succeeds
- **AND** car HFP audio does not become observed as connected before the configured timeout
- **THEN** the system SHALL call `stopVoiceRecognition(car)` before abandoning setup
- **AND** SHALL not place or retain a Telecom PTT route for that attempt
