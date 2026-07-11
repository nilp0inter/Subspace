## ADDED Requirements

### Requirement: Debug synthesized modes return host-routed playback
The Debug Channel SHALL implement `TTS` and `STT↔TTS` through semantic synthesis and audio-operation capabilities. A successful synthesized result SHALL be returned as an opaque playback operation to the host audio-input terminal lifecycle. The Debug runtime SHALL NOT select or invoke an Android audio output directly, and the host SHALL play the resolved operation through the active session output before releasing that session's route.

#### Scenario: TTS produces audible playback
- **WHEN** a committed Debug instance in `TTS` mode receives terminal input
- **AND** synthesis and playback-operation creation succeed
- **THEN** the runtime SHALL return the created opaque playback operation
- **AND** the host SHALL play its PCM exactly once through the active session's resolved output
- **AND** the runtime SHALL report success only after host playback completion

#### Scenario: STT to TTS produces audible playback
- **WHEN** a committed Debug instance in `STT↔TTS` mode receives terminal audio
- **AND** transcription, synthesis, and playback-operation creation succeed
- **THEN** the synthesized speech SHALL correspond to the successful transcript
- **AND** the runtime SHALL return the created opaque playback operation
- **AND** the host SHALL play its PCM exactly once through the active session's resolved output

#### Scenario: Synthesized playback uses endpoint ordering
- **WHEN** a Debug synthesized playback operation is returned on Work, On-a-pinch, or On-the-road
- **THEN** the host SHALL use that session's resolved output rather than an ambient local output
- **AND** Work playback SHALL remain on its acquired communication route
- **AND** On-a-pinch playback SHALL use the normal local media route
- **AND** On-the-road SHALL release the Telecom capture route before response playback
