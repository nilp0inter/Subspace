## ADDED Requirements

### Requirement: Persistent diagnostics attribute PTT cancellation provenance
The system SHALL write machine-readable persistent diagnostics for source-specific and global PTT cancellation requests and their terminal outcomes. Diagnostics SHALL identify the semantic caller, requested source, current session source and phase when present, disposition, terminal claim category, and non-sensitive reason. RSM serial attempt termination SHALL record whether the attempt ever connected and the reconnect disposition. Diagnostics SHALL NOT include Bluetooth hardware addresses, PCM or encoded audio payloads, transcript content, credentials, or channel message content.

#### Scenario: Source-scoped cancellation is accepted
- **WHEN** a source-specific cancellation matches the current session owner
- **THEN** persistent logs SHALL record the semantic caller, requested source, active source and phase, accepted disposition, and reason
- **AND** terminal diagnostics SHALL identify cancellation as the claimed terminal category

#### Scenario: Cross-source cancellation is rejected
- **WHEN** a source-specific cancellation does not match the current session owner
- **THEN** persistent logs SHALL record a rejected source-mismatch disposition with both semantic sources
- **AND** SHALL NOT expose hardware identity or captured content

#### Scenario: Cancellation finds no active session
- **WHEN** a source-specific cancellation is requested with no current session
- **THEN** persistent logs SHALL record a no-active-session disposition

#### Scenario: RSM reconnect attempt terminates
- **WHEN** an automatic or manual RSM SPP connection attempt ends
- **THEN** persistent logs SHALL record whether the attempt connected, whether monitoring remains requested, and whether reconnect was scheduled, blocked, or stopped
- **AND** SHALL correlate any RSM cancellation request using semantic source and session identifiers rather than Bluetooth address

#### Scenario: Audio session publishes terminal completion
- **WHEN** an audio input session finishes terminal processing
- **THEN** persistent logs SHALL record session identifier, source, terminal claim category, semantic reason, and cleanup failure categories if any
- **AND** SHALL omit audio and channel content
