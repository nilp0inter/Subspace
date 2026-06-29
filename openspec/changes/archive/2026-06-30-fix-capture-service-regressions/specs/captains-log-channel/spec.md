## MODIFIED Requirements

### Requirement: Journal entry metadata source of truth

The system SHALL create a JSON metadata sidecar for every accepted Journal
capture and SHALL treat that metadata as the canonical source of truth for
entry identity, capture state, derived task state, transcript text, artifact
paths, and deletion state. The metadata SHALL record the sample rate
negotiated by the capture source for the session, not a hardcoded value.

#### Scenario: Metadata initialized before recording
- **WHEN** the Journal channel accepts a PTT press for recording
- **THEN** the system SHALL create entry metadata with `capture.state` set to `recording` before audio capture bytes are streamed

#### Scenario: Metadata updated after capture finalization
- **WHEN** the user releases PTT and the capture file is finalized successfully
- **THEN** the system SHALL update metadata with `capture.state` set to `finished`
- **AND** the system SHALL record capture duration, sample rate (read from the capture session), channel count, encoding, and relative capture path

#### Scenario: 8 kHz capture records 8000 sample rate
- **WHEN** the capture source negotiates an 8 kHz sample rate and the Journal capture finalizes
- **THEN** the metadata `capture.sampleRate` SHALL be 8000
- **AND** the WAV header in the capture file SHALL be written with sample rate 8000

#### Scenario: 16 kHz capture records 16000 sample rate
- **WHEN** the capture source negotiates a 16 kHz sample rate and the Journal capture finalizes
- **THEN** the metadata `capture.sampleRate` SHALL be 16000
- **AND** the WAV header in the capture file SHALL be written with sample rate 16000

#### Scenario: Derived task state recorded
- **WHEN** OGG encoding or transcription is pending, running, finished, failed, or skipped
- **THEN** the system SHALL persist that task state in the entry metadata

## ADDED Requirements

### Requirement: Journal WAV writer thread safety

The journal-side WAV writer SHALL be internally thread-safe so a
`writeChunk` call that is in flight when `finalize` is called from another
coroutine does not throw `IOException` or corrupt the file. `finalize` SHALL
be idempotent. A `writeChunk` call that arrives after `finalize` SHALL be a
no-op.

#### Scenario: Finalize during an in-flight write
- **WHEN** the frames collector is inside `writeChunk` and `finalize` is called from another coroutine
- **THEN** the writer SHALL serialize the two operations
- **AND** neither call SHALL throw `IOException`
- **AND** the resulting WAV file SHALL be well-formed

#### Scenario: Late writeChunk after finalize is a no-op
- **WHEN** `finalize` has completed and a `writeChunk` call arrives from a collector that has not yet observed cancellation
- **THEN** the writer SHALL return without writing
- **AND** the writer SHALL NOT throw

### Requirement: Journal frames collector joined before finalization

The Journal pipeline SHALL join the frames collector coroutine before
finalizing the WAV writer, so the collector has fully unwound before the file
is closed. This establishes a happens-before edge between the split
lifecycles introduced by the capture-service refactor (the service's read
loop and the journal's frames collector).

#### Scenario: Frames collector unwound before finalize
- **WHEN** the user releases PTT and the Journal pipeline finalizes the capture
- **THEN** the system SHALL `cancelAndJoin` the frames collector before calling `writer.finalize()`
- **AND** no collector coroutine SHALL be running when `finalize` executes

#### Scenario: Cancel-and-release joins the collector before finalizing
- **WHEN** the Journal pipeline is cancelled mid-capture (mode switch, service teardown)
- **THEN** the system SHALL join the frames collector before finalizing the writer
- **AND** the writer's `finalize` SHALL not race an in-flight `writeChunk`