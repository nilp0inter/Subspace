## Purpose

TBD. Defines PCM-to-OGG/Vorbis encoding behavior for channel recording outputs.

## Requirements

### Requirement: PCM to OGG/Vorbis encoding
The system SHALL encode raw PCM audio (16-bit, 16kHz, mono) to OGG/Vorbis format using a bundled Rust native encoder that does not depend on the target device exposing a platform Vorbis `MediaCodec` encoder.

#### Scenario: Successful encoding
- **WHEN** a PCM audio buffer is submitted for encoding
- **THEN** the system SHALL produce a valid OGG/Vorbis file at the specified output path

#### Scenario: Encoding runs post-capture
- **WHEN** PTT is released and a complete PCM buffer is available
- **THEN** the system SHALL encode the entire buffer to OGG after capture completes, not during capture

### Requirement: Encoding does not block PTT readiness
The system SHALL perform OGG encoding on a background coroutine dispatcher. The user SHALL be able to start a new PTT capture while a previous capture is being encoded.

#### Scenario: Rapid sequential PTT captures
- **WHEN** the user releases PTT and immediately presses PTT again while encoding is in progress
- **THEN** the system SHALL accept the new capture without waiting for the previous encoding to finish

### Requirement: Encoding failure fallback
The system SHALL handle encoding failures gracefully. If the native OGG encoder fails to encode a capture, the system SHALL log the error and not crash.

#### Scenario: Native OGG encoding fails
- **WHEN** the OGG encoder encounters an error during encoding
- **THEN** the system SHALL log the failure and discard the partial output file
- **AND** the markdown log entry (if enabled) SHALL still be written with a note that the recording failed
