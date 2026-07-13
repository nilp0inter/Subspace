## ADDED Requirements

### Requirement: Host owns unified half-duplex audio admission
The host SHALL expose channel audio only through semantic capabilities and SHALL atomically serialize capture and channel-content playback before any physical route acquisition. Provider and runtime code SHALL NOT receive admission locks, `InputMode`, Android audio objects, routes, endpoints, PCM outputs, active playback handles, or device identities.

#### Scenario: Runtime schedules playback while capture owns audio
- **WHEN** a runtime schedules semantic playback while capture owns or reserves host audio
- **THEN** the capability SHALL return or retain a typed pending outcome
- **AND** no playback route object or admission primitive SHALL cross into the runtime

#### Scenario: Host admits delayed playback
- **WHEN** a pending semantic playback request is selected and host audio is free
- **THEN** the host SHALL own current-channel validation, current-mode resolution, route acquisition, PCM playback, interruption, cleanup, and typed outcome publication

### Requirement: Active playback is host-controllable
The host playback capability SHALL represent admitted playback as a lifecycle-bound host operation capable of exact completion, ducked rejection-tone overlay, explicit skip, route-failure interruption, and cleanup. Control operations SHALL affect the active output stream without exposing or reacquiring its route through a channel runtime.

#### Scenario: Rejection tone overlays active speech
- **WHEN** host PTT policy rejects a press during active playback
- **THEN** the active playback operation SHALL temporarily duck speech and mix one rejection tone into its existing output stream
- **AND** it SHALL restore speech and continue the same message without changing its durable lifecycle

#### Scenario: Host explicitly skips playback
- **WHEN** host control policy skips the active operation
- **THEN** the capability SHALL stop the output, complete cleanup, and report an explicit skipped outcome distinct from interruption or route failure

#### Scenario: Route failure interrupts playback
- **WHEN** the active route fails before playback completes
- **THEN** the capability SHALL stop and clean up the operation
- **AND** it SHALL report interruption or failure without reporting successful hearing or explicit skip

### Requirement: Input subsystem integration is boundary-only
The host audio capability SHALL treat the existing audio-input subsystem as an authoritative capture owner. It MAY request or observe a capture admission reservation and SHALL observe existing terminal-completion publication, but it SHALL NOT take over recorder setup, route gates, ready-beep ordering, committed target handling, terminal ownership, or exact-once input cleanup.

#### Scenario: Capture is admitted through host arbitration
- **WHEN** PTT is not blocked by playback and obtains host capture admission
- **THEN** the existing input subsystem SHALL execute its unchanged internal sequence
- **AND** the host arbitration layer SHALL release capture ownership only from the existing terminal-completion boundary
