## MODIFIED Requirements

### Requirement: Diagnostics Channel is an external source-only Lua v1 package
The Diagnostics Channel SHALL be published from the official diagnostics-channel GitHub repository as a canonical `subspace-channel.zip` release asset that satisfies package-format v1. Its manifest SHALL bind to the host-resolved durable repository database ID, declare the exact supported Lua and API versions, use one package-specific installed-provider identity, and contain only `manifest.json` plus canonical UTF-8 Lua source modules. The Diagnostics Channel release v1.2.0 manifest SHALL explicitly declare schema-version-1 empty configuration (containing empty `data.fields` and `ui.fields` arrays) and explicitly declare no host capabilities (containing an empty `capabilities` array). The evolved host SHALL reject the old immutable v1.0.0 and v1.1.0 releases that omit these declarations, and SHALL NOT use a legacy parser or compatibility fallback. The Subspace application SHALL NOT bundle, seed, special-case, or register the Diagnostics Channel as a built-in provider.

#### Scenario: Published diagnostics artifact is inspected
- **WHEN** the application statically inspects an exact published Diagnostics Channel release asset
- **THEN** the package SHALL pass the ordinary package-format, identity, compatibility, digest, and immutable-program-image validation
- **AND** inspection SHALL NOT execute Lua or use a diagnostics-specific validator path

#### Scenario: Diagnostics provider is installed
- **WHEN** the exact artifact commits through the installed-package repository
- **THEN** its descriptor SHALL appear through the ordinary installed-provider snapshot with explicit empty configuration and empty capabilities
- **AND** registration SHALL create no Lua actor or Lua state

#### Scenario: Application starts without the package installed
- **WHEN** no Diagnostics Channel revision exists in the installed-package index
- **THEN** the application SHALL NOT register a fallback or bundled diagnostics provider
- **AND** ordinary startup SHALL remain unchanged

#### Scenario: Old legacy release is rejected by evolved host
- **WHEN** the evolved host attempts to validate or register an old Diagnostics Channel release (v1.0.0 or v1.1.0) that omits the newly required configuration or capabilities manifest declarations
- **THEN** validation SHALL fail closed
- **AND** the host SHALL NOT invoke a legacy parser, infer default empty declarations, or register the provider

#### Scenario: Legacy instance updated to release v1.2.0
- **WHEN** the user explicitly updates a rejected legacy Diagnostics Channel installation (v1.0.0 or v1.1.0) to release v1.2.0 on the evolved host
- **THEN** the host SHALL successfully validate, install, and execute the v1.2.0 release
- **AND** the host SHALL NOT automatically perform configuration migrations or preserve legacy parser support

#### Scenario: Pre-cutover installed index remains explicitly updateable
- **WHEN** the evolved host opens a pre-cutover installed-package index whose otherwise-valid cached Diagnostics manifest omits exactly both newly required declarations
- **THEN** the host SHALL retain its repository identity, source record, digest, and exact stored bytes as an explicitly untrusted legacy revision
- **AND** the old revision SHALL remain unavailable and SHALL NOT be registered, materialized, or executed
- **AND** an explicit v1.2.0 update and an unrelated valid package installation SHALL remain possible without deleting the installed-package index
- **AND** a partial omission, unknown key, malformed identity, source mismatch, digest mismatch, or any other legacy shape SHALL still fail closed

### Requirement: Diagnostics Channel exposes deterministic lifecycle and readiness behavior
Each enabled Diagnostics Channel instance SHALL construct one independent Lua actor and SHALL implement the existing v1 callbacks `startup`, `handle_lifecycle`, `handle_readiness`, `handle_input`, and `handle_sos`. The `startup` callback SHALL accept exactly one `configuration` argument representing a detached empty configuration snapshot. The callback SHALL validate that this snapshot contains `schema_version = 1` and empty `values`, emit one structured `startup` diagnostic, and admit one generation-owned heartbeat coroutine. Lifecycle ready SHALL emit one structured `ready` diagnostic. Readiness SHALL return exactly `{ready = true}` while the generation is live. The package SHALL use no undeclared globals, restricted libraries, Android objects, native modules, filesystem, network, credentials, or hidden host interfaces.

#### Scenario: Instance becomes ready
- **WHEN** the installed provider constructs and activates a matching enabled channel instance
- **THEN** startup and lifecycle-ready SHALL complete through the ordinary Lua provider adapter, passing the detached empty configuration snapshot to startup
- **AND** the runtime SHALL publish ready only after both callbacks succeed

#### Scenario: Two diagnostics instances are enabled
- **WHEN** two catalogue definitions reference the same active Diagnostics Channel provider revision
- **THEN** each SHALL receive an independent actor, Lua state, heartbeat sequence, timers, logs, and runtime generation
- **AND** closing or replacing one SHALL NOT stop or mutate the other

#### Scenario: Startup cannot admit the heartbeat task
- **WHEN** generation task capacity is exhausted or the generation closes before startup task authorization
- **THEN** startup SHALL return a normalized application failure and the instance SHALL remain unavailable
- **AND** no unowned or late heartbeat work SHALL execute

#### Scenario: Startup receives and validates empty configuration snapshot
- **WHEN** the host constructs a runtime generation for the Diagnostics Channel and calls `startup(configuration)`
- **THEN** the `configuration` argument SHALL be a detached Lua table containing `schema_version = 1` and an empty `values` table
- **AND** the callback SHALL successfully validate the empty snapshot and emit a structured `startup` diagnostic without throwing an error

### Requirement: PTT input produces metadata-only diagnostics without channel output
While ready and selected, the Diagnostics Channel SHALL accept ordinary PTT input through the evolved Lua v1 input contract. The `handle_input` callback SHALL receive a capture event containing both bounded metadata and an opaque audio userdata value representing the captured audio. The callback SHALL validate the event, emit one structured `input` record containing only bounded non-sensitive capture metadata such as duration, sample rate, and channel count, and return exactly `{ok = true}`. The callback SHALL NOT inspect, retain, serialize, log, or play the opaque audio value. It SHALL NOT log the session identifier, audio samples, encoded audio, speech content, transcript content, device identity, credentials, or other captured payload, and SHALL return no channel text or audio result.

#### Scenario: PTT capture completes
- **WHEN** the selected Diagnostics Channel receives a valid v1 capture-complete event containing the opaque audio userdata and metadata
- **THEN** it SHALL emit one metadata-only input diagnostic and return successful terminal handling
- **AND** the host SHALL finish the PTT session and dispose of the opaque audio without synthesis, playback, text output, or durable message publication

#### Scenario: Capture is cancelled or fails
- **WHEN** host capture is cancelled or fails before a valid capture-complete callback
- **THEN** the host SHALL apply the existing neutral cancellation or failure path without invoking `handle_input`
- **AND** the package SHALL emit no fabricated input-success record

#### Scenario: Input callback receives malformed metadata
- **WHEN** `handle_input` receives a malformed or unsupported event shape
- **THEN** it SHALL return a normalized application failure without logging partial capture data
- **AND** SHALL NOT throw an unprotected error or attempt a host effect

#### Scenario: Opaque audio userdata is ignored and disposed
- **WHEN** `handle_input` is invoked with the capture event containing the opaque audio userdata
- **THEN** the callback SHALL ignore the audio userdata without executing any library call, serialization, or property access on it
- **AND** the callback SHALL NOT retain a reference to the audio userdata or attempt to play it
- **AND** the host SHALL reclaim the underlying audio recording immediately upon callback completion
