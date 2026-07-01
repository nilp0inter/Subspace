## ADDED Requirements

### Requirement: Journal finalizes capture before route release
The Journal PTT pipeline SHALL finalize the accepted capture before releasing the audio route. On normal release, Journal SHALL cancel and join its frames collector, stop the centralized `CaptureSession`, finalize the WAV writer, and write terminal capture metadata before invoking `route.output.releaseRoute()`.

#### Scenario: Normal Journal release orders capture finalization before route release
- **WHEN** the user releases PTT on a ready Journal channel
- **THEN** the Journal pipeline SHALL cancel and join the frames collector
- **AND** the Journal pipeline SHALL stop the active `CaptureSession`
- **AND** the Journal pipeline SHALL finalize the WAV writer
- **AND** the Journal pipeline SHALL write terminal capture metadata
- **AND** only then SHALL the Journal pipeline release the audio route via `route.output.releaseRoute()`

#### Scenario: Journal no-response release does not use empty playback
- **WHEN** a Journal capture finalizes without response audio
- **THEN** the Journal pipeline SHALL NOT call `route.output.play()` with empty PCM to trigger route release
- **AND** the Journal pipeline SHALL call `route.output.releaseRoute()` for the no-response route switch

#### Scenario: Journal derived processing starts from finalized capture
- **WHEN** Journal starts encoding, transcription, or markdown regeneration after a PTT release
- **THEN** the capture file SHALL already be finalized
- **AND** the capture metadata SHALL already have a terminal capture state
