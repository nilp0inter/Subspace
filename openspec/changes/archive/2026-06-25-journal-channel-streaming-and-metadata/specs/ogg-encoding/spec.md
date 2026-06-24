## MODIFIED Requirements

### Requirement: PCM to OGG/Vorbis encoding
The system SHALL encode finalized PCM/WAV capture files containing raw PCM audio (16-bit, 16kHz, mono) to OGG/Vorbis format using a bundled Rust native encoder that does not depend on the target device exposing a platform Vorbis `MediaCodec` encoder.

#### Scenario: Successful encoding
- **WHEN** a finalized PCM/WAV capture file is submitted for encoding
- **THEN** the system SHALL produce a valid OGG/Vorbis file at the specified output path

#### Scenario: Encoding runs post-capture
- **WHEN** PTT is released and a complete capture file is finalized
- **THEN** the system SHALL encode the finalized capture file to OGG after capture completes, not during capture

### Requirement: Encoding failure fallback
The system SHALL handle encoding failures gracefully. If the native OGG encoder fails to encode a capture, the system SHALL log the error, discard the partial OGG output file, preserve the finalized capture file, and persist the encoding failure in entry metadata when metadata is available.

#### Scenario: Native OGG encoding fails
- **WHEN** the OGG encoder encounters an error during encoding
- **THEN** the system SHALL log the failure
- **AND** the system SHALL discard the partial OGG output file
- **AND** the system SHALL preserve the finalized capture file
- **AND** the system SHALL mark encoding as failed in entry metadata when the encoding request is associated with a Journal entry
