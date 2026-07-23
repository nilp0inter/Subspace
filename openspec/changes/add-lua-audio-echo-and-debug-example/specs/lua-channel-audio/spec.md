## ADDED Requirements

### Requirement: The captured-audio handle is the input session token

The opaque handle for the audio captured in one input session SHALL be that session's existing `session` string token, delivered to Lua in the `handle_input` event table. The host SHALL NOT deliver raw audio samples, PCM buffers, file paths, Android audio objects, or any native handle to Lua. The handle SHALL be valid only for the duration of that session's `handle_input` callback; the host SHALL retain the session's recorded audio host-side, keyed by the session token, only until the input completes. A plugin SHALL NOT be able to persist, replay, or reference the handle after the session completes.

#### Scenario: Capture event carries the opaque session token
- **WHEN** the host invokes `handle_input` for a completed capture
- **THEN** the event table SHALL contain the `session` string token identifying that input session
- **AND** the event table SHALL NOT contain raw audio samples, PCM buffers, or any native/audio object

#### Scenario: Session token is the only captured-audio handle
- **WHEN** a plugin requests playback of captured audio
- **THEN** it SHALL do so by returning the session token it received in the same `handle_input` callback
- **AND** it SHALL NOT construct, mint, or obtain any other captured-audio handle

#### Scenario: Handle is not valid beyond its input session
- **WHEN** a plugin retains the session token and references it after the input session completes
- **THEN** the host SHALL treat the token as invalid for any later playback request
- **AND** the host SHALL NOT retain the session's recorded audio for that plugin beyond session completion

### Requirement: `handle_input` may request host playback of the captured audio

In addition to the established `{ ok = true }` and `{ error = { code, detail } }` outcome shapes, `handle_input` SHALL accept the playback-request directive `{ ok = true, play = <session> }`, where `<session>` is the current input session token. Returning `{ ok = true }` SHALL continue to signal success with no playback. Returning `{ error = { code, detail } }` SHALL continue to signal failure with no playback. The directive SHALL be expressed only as the synchronous return value of `handle_input`; spawned tasks and other callbacks SHALL NOT request playback.

#### Scenario: Plugin requests echo playback
- **WHEN** `handle_input` returns `{ ok = true, play = <current session token> }`
- **THEN** the host SHALL treat this as a successful outcome carrying a playback request for that session's captured audio

#### Scenario: Plugin succeeds without playback
- **WHEN** `handle_input` returns `{ ok = true }`
- **THEN** the host SHALL record a successful outcome and SHALL NOT schedule playback

#### Scenario: Plugin reports failure
- **WHEN** `handle_input` returns `{ error = { code = <non-empty string>, detail = <non-empty string> } }`
- **THEN** the host SHALL record a failed outcome and SHALL NOT schedule playback

#### Scenario: Playback requested outside handle_input
- **WHEN** a spawned task or a callback other than `handle_input` attempts to cause playback of a captured session
- **THEN** the host SHALL NOT schedule playback for that attempt

### Requirement: The host builds and schedules the playback operation from host-retained audio

On a validated playback request, the host SHALL translate the directive into a `ChannelInputResult.PlaybackOperation`. The host SHALL build the operation from the session's host-retained recorded audio through the adapter's `AudioOperation` capability, and SHALL route it through the existing input/playback pipeline, which SHALL own half-duplex admission, routing, and playback-failure recording. The host SHALL make the final playback decision. Lua SHALL NOT receive the recorded audio and SHALL NOT schedule or admit playback itself.

#### Scenario: Valid request produces a host-scheduled playback operation
- **WHEN** `handle_input` returns a playback request for the current session and the `AudioOperation` capability is available
- **THEN** the host SHALL build a playback operation from that session's retained audio
- **AND** the host SHALL return `ChannelInputResult.PlaybackOperation` to the input/playback pipeline
- **AND** the pipeline SHALL schedule playback of the captured audio

#### Scenario: Host retains the final playback decision
- **WHEN** the host has received a validated playback request
- **THEN** admission, routing, and any playback-failure handling SHALL remain host-owned
- **AND** Lua SHALL NOT acquire a playback-scheduling capability or invoke a scheduling operation

### Requirement: Playback requests are validated fail-closed

The host SHALL validate that a playback-request token equals the current input session token before building any operation. An unknown, stale, or cross-session token SHALL yield a `FAILED` execution status, a structured `subspace.log` entry, and no playback. If the `AudioOperation` capability is unavailable or the retained audio is empty or absent, the host SHALL produce no playback and SHALL record the failure through the existing pipeline. No failure in playback-request handling SHALL crash the actor, the runtime, or unrelated channel instances.

#### Scenario: Wrong or forged session token
- **WHEN** `handle_input` returns `{ ok = true, play = <token that is not the current session> }`
- **THEN** the host SHALL record a `FAILED` execution status
- **AND** the host SHALL emit a structured log entry for the rejection
- **AND** the host SHALL NOT schedule playback

#### Scenario: Stale session token
- **WHEN** `handle_input` returns a playback request referencing a token from a prior, completed input session
- **THEN** the host SHALL record a `FAILED` execution status and SHALL NOT schedule playback

#### Scenario: Audio operation capability unavailable
- **WHEN** `handle_input` returns a valid playback request for the current session but the `AudioOperation` capability is unavailable
- **THEN** the host SHALL produce no playback and SHALL record the failure through the existing pipeline

### Requirement: A fixed-mode Lua echo channel is bundled and registered at startup

The application SHALL bundle one fixed-mode Lua echo channel as an immutable program image supplied as an application asset, and SHALL register a Lua channel implementation provider for it at startup alongside the existing Kotlin providers. Registering the provider SHALL NOT create a Lua state or actor; a Lua runtime SHALL be created only when a channel definition referencing the provider constructs a runtime. The bundled channel's implementation descriptor SHALL declare the `AudioOperation` capability so the adapter can build the playback operation. The existing Kotlin Debug channel and all other Kotlin providers SHALL remain registered and behaviorally unchanged.

#### Scenario: Startup registers the Lua echo provider without creating a Lua runtime
- **WHEN** the application starts
- **THEN** a Lua channel implementation provider for the bundled echo channel SHALL be registered alongside the Kotlin providers
- **AND** no Lua state or actor SHALL be created at registration time

#### Scenario: Bundled channel declares the audio-operation capability
- **WHEN** the bundled echo channel's implementation descriptor is resolved
- **THEN** it SHALL declare the `AudioOperation` capability
- **AND** it SHALL require no declarative configuration

#### Scenario: Echo behavior on device
- **WHEN** the bundled echo channel is selected, its runtime is constructed, and a PTT capture completes
- **THEN** the channel SHALL return a playback request for the captured session
- **AND** the host SHALL play the captured audio back through the active audio route
