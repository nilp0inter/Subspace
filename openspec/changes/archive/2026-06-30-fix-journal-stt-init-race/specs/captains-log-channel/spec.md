## MODIFIED Requirements

### Requirement: STT transcription via on-device model
The system SHALL use the existing on-device Parakeet STT model for transcription. Transcription SHALL use finalized Journal capture files as input and SHALL NOT require network access. The Journal channel SHALL bind to the real on-device transcriber once the STT off-main initialization completes, regardless of whether STT initialization finishes before or after `onCreate`, so journal captures are transcribed instead of falling back to a no-op transcriber. If STT initialization fails, the Journal channel SHALL fall back to a no-op transcriber that marks transcription as failed in entry metadata while preserving the canonical capture file.

#### Scenario: Transcription succeeds
- **WHEN** a finalized Journal capture is transcribed and the STT off-main initialization has completed successfully
- **THEN** the transcription SHALL use the Parakeet model already loaded in the service
- **AND** the system SHALL NOT perform any network I/O
- **AND** the system SHALL store the transcript text in entry metadata

#### Scenario: Transcription fails
- **WHEN** the STT model fails to produce a transcript or the STT off-main initialization has failed
- **THEN** the system SHALL mark transcription as failed in entry metadata
- **AND** the system SHALL NOT discard the canonical capture file
- **AND** the generated markdown entry SHALL include an error placeholder if "Save in journal file" is enabled

#### Scenario: Journal transcriber bound after STT init completes regardless of startup ordering
- **WHEN** the app process starts and the Journal channel is initialized at `onCreate` while the STT off-main initialization coroutine is still running
- **THEN** the system SHALL await the completion of the STT off-main initialization before constructing the Journal channel's transcriber binding
- **AND** if STT initialization succeeds, the Journal channel SHALL use the real on-device Parakeet transcriber for all subsequent captures
- **AND** the Journal channel SHALL NOT permanently bind to a no-op transcriber due to the startup ordering

#### Scenario: Journal falls back to no-op transcriber on STT init failure
- **WHEN** the STT off-main initialization fails and `sttReady` resolves to `null`
- **THEN** the Journal channel SHALL construct its controller with a no-op transcriber that fails each transcription request
- **AND** the Journal channel SHALL still accept captures and persist WAV files and metadata
- **AND** each capture's transcription state SHALL be marked as failed in entry metadata
- **AND** the generated markdown SHALL include an error placeholder if "Save in journal file" is enabled

#### Scenario: Journal recovery runs after transcriber binding
- **WHEN** the Journal channel is initialized with a previously selected base directory and the STT off-main initialization has completed
- **THEN** the system SHALL run Journal recovery reconciliation after the transcriber binding is established
- **AND** recovery SHALL reconcile stale entries against the bound transcriber, not a no-op fallback