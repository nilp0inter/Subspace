## ADDED Requirements

### Requirement: Normal car-call release finalizes accepted Journal captures
An accepted Journal capture that terminates because its active On-the-road Telecom call is hung up SHALL follow Journal's normal terminal-recording path. It SHALL not remain in `recording` state after the audio input subsystem has a terminal recording.

#### Scenario: Journal car capture ends on hang-up
- **WHEN** an accepted Journal target receives terminal PCM after an active car call is hung up
- **THEN** it SHALL finalize the WAV writer after joining the frames collector
- **AND** write `endedAt`, final capture state, duration, sample rate, channel count, encoding, and capture path to metadata
- **AND** run configured OGG encoding and transcription processing
- **AND** regenerate the affected daily Markdown journal from updated metadata

#### Scenario: Car capture is cancelled before terminal recording
- **WHEN** a car session is cancelled before terminal PCM is available
- **THEN** Journal SHALL finalize any partial writer without marking the entry finished
- **AND** startup recovery remains responsible for reconciling the interrupted metadata
