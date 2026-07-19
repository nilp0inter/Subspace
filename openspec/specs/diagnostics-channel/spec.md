## Purpose

Defines the Diagnostics Channel: an external source-only Lua v1 package used for testing and validating package installation, update, rollback, and removal flows.

## Requirements

### Requirement: Diagnostics Channel is an external source-only Lua v1 package
The Diagnostics Channel SHALL be published from the official diagnostics-channel GitHub repository as a canonical `subspace-channel.zip` release asset that satisfies package-format v1. Its manifest SHALL bind to the host-resolved durable repository database ID, declare the exact supported Lua and API versions, use one package-specific installed-provider identity, and contain only `manifest.json` plus canonical UTF-8 Lua source modules. The provider SHALL use schema-version-1 empty configuration and declare no host capabilities. The Subspace application SHALL NOT bundle, seed, special-case, or register the Diagnostics Channel as a built-in provider.

#### Scenario: Published diagnostics artifact is inspected
- **WHEN** the application statically inspects an exact published Diagnostics Channel release asset
- **THEN** the package SHALL pass the ordinary package-format, identity, compatibility, digest, and immutable-program-image validation
- **AND** inspection SHALL NOT execute Lua or use a diagnostics-specific validator path

#### Scenario: Diagnostics provider is installed
- **WHEN** the exact artifact commits through the installed-package repository
- **THEN** its descriptor SHALL appear through the ordinary installed-provider snapshot with empty configuration and no capabilities
- **AND** registration SHALL create no Lua actor or Lua state

#### Scenario: Application starts without the package installed
- **WHEN** no Diagnostics Channel revision exists in the installed-package index
- **THEN** the application SHALL NOT register a fallback or bundled diagnostics provider
- **AND** ordinary startup SHALL remain unchanged

### Requirement: Diagnostics Channel exposes deterministic lifecycle and readiness behavior
Each enabled Diagnostics Channel instance SHALL construct one independent Lua actor and SHALL implement the existing v1 callbacks `startup`, `handle_lifecycle`, `handle_readiness`, `handle_input`, and `handle_sos`. Startup SHALL emit one structured `startup` diagnostic and admit one generation-owned heartbeat coroutine. Lifecycle ready SHALL emit one structured `ready` diagnostic. Readiness SHALL return exactly `{ready = true}` while the generation is live. The package SHALL use no undeclared globals, restricted libraries, Android objects, native modules, filesystem, network, credentials, or hidden host interfaces.

#### Scenario: Instance becomes ready
- **WHEN** the installed provider constructs and activates a matching enabled channel instance
- **THEN** startup and lifecycle-ready SHALL complete through the ordinary Lua provider adapter
- **AND** the runtime SHALL publish ready only after both callbacks succeed

#### Scenario: Two diagnostics instances are enabled
- **WHEN** two catalogue definitions reference the same active Diagnostics Channel provider revision
- **THEN** each SHALL receive an independent actor, Lua state, heartbeat sequence, timers, logs, and runtime generation
- **AND** closing or replacing one SHALL NOT stop or mutate the other

#### Scenario: Startup cannot admit the heartbeat task
- **WHEN** generation task capacity is exhausted or the generation closes before startup task authorization
- **THEN** startup SHALL return a normalized application failure and the instance SHALL remain unavailable
- **AND** no unowned or late heartbeat work SHALL execute

### Requirement: Heartbeats prove unselected generation-owned background execution
The startup-admitted heartbeat coroutine SHALL emit a structured `heartbeat` record with a monotonically increasing generation-local sequence, cooperatively sleep for one fixed package-owned interval, and repeat while its runtime generation remains live. Heartbeat execution SHALL continue while the instance is enabled but unselected and while the Subspace foreground service remains alive. The coroutine, timer, sequence, and future logs SHALL terminate with generation replacement, instance disablement, package removal, service shutdown, or process death and SHALL NOT be restored into a later generation.

#### Scenario: Diagnostics instance is unselected
- **WHEN** an enabled Diagnostics Channel instance is not the active PTT destination and its heartbeat timer fires
- **THEN** its actor SHALL resume and publish the next heartbeat record
- **AND** channel selection SHALL remain unchanged

#### Scenario: Generation is replaced during sleep
- **WHEN** package update or rollback retires a Diagnostics Channel generation while its heartbeat is sleeping
- **THEN** the predecessor timer and coroutine SHALL be cancelled or suppressed before the successor becomes ready
- **AND** no predecessor heartbeat SHALL enter or be attributed to the successor generation

#### Scenario: Service restarts
- **WHEN** the process or foreground service restarts with the Diagnostics Channel package and definition still committed
- **THEN** the host SHALL construct a fresh runtime generation whose heartbeat sequence starts from its package-defined initial value
- **AND** SHALL NOT restore the prior Lua state, timer position, coroutine, or sequence

### Requirement: PTT input produces metadata-only diagnostics without channel output
While ready and selected, the Diagnostics Channel SHALL accept ordinary PTT input through the existing Lua v1 input contract. `handle_input` SHALL validate the capture-complete event, emit one structured `input` record containing only bounded non-sensitive capture metadata such as duration, sample rate, and channel count, and return exactly `{ok = true}`. It SHALL NOT log the session identifier, audio samples, encoded audio, speech content, transcript content, device identity, credentials, or other captured payload, and SHALL return no channel text or audio result.

#### Scenario: PTT capture completes
- **WHEN** the selected Diagnostics Channel receives a valid v1 capture-complete event
- **THEN** it SHALL emit one metadata-only input diagnostic and return successful terminal handling
- **AND** the host SHALL finish the PTT session without synthesis, playback, text output, or durable message publication

#### Scenario: Capture is cancelled or fails
- **WHEN** host capture is cancelled or fails before a valid capture-complete callback
- **THEN** the host SHALL apply the existing neutral cancellation or failure path without invoking `handle_input`
- **AND** the package SHALL emit no fabricated input-success record

#### Scenario: Input callback receives malformed metadata
- **WHEN** `handle_input` receives a malformed or unsupported event shape
- **THEN** it SHALL return a normalized application failure without logging partial capture data
- **AND** SHALL NOT throw an unprotected error or attempt a host effect

### Requirement: SOS produces one bounded structured diagnostic and no other effect
`handle_sos` SHALL emit one structured record identifying the semantic event as `sos` and SHALL complete successfully without changing readiness, resetting the heartbeat sequence, publishing content, invoking another capability, or affecting a sibling instance.

#### Scenario: SOS is invoked
- **WHEN** the host delivers an SOS event to a live Diagnostics Channel generation
- **THEN** the package SHALL emit one attributed `sos` record
- **AND** SHALL leave the generation ready and its heartbeat coroutine live

#### Scenario: SOS races generation close
- **WHEN** generation close wins before the SOS callback or its log can be admitted
- **THEN** the host SHALL suppress the stale callback or record under existing generation rules
- **AND** SHALL NOT attribute it to a replacement generation

### Requirement: Diagnostics records are structured, bounded, and release-distinguishable
Every Diagnostics Channel record SHALL use `subspace.log` with a normalized plain-table payload containing a bounded event name and only the fields defined for that event. The host SHALL add instance ID, runtime generation, timestamp, and level. Each published Diagnostics Channel release used for update and rollback acceptance SHALL include a bounded release marker in its startup and heartbeat payloads so device evidence can distinguish predecessor and successor behavior without exposing artifact paths or relying on Lua access to package-store metadata.

#### Scenario: Diagnostics record reaches Log Analysis
- **WHEN** the package emits an accepted startup, ready, heartbeat, input, or SOS record
- **THEN** the host SHALL persist and stream it through the unified observability pipeline with its instance and generation attribution
- **AND** the Log Analysis view SHALL support level, tag, and text filtering for that record

#### Scenario: Package exceeds the plugin log rate
- **WHEN** a defective or modified Diagnostics Channel release emits records faster than the host actor limit
- **THEN** excess records SHALL be silently rate-dropped under the existing `subspace.log` contract
- **AND** the package SHALL NOT exhaust host memory or block its Lua coroutine

#### Scenario: Updated revision starts
- **WHEN** an explicitly selected Diagnostics Channel update commits and its successor becomes ready
- **THEN** subsequent startup and heartbeat records SHALL carry the successor release marker and successor generation
- **AND** no predecessor release marker SHALL appear from a late old-generation effect

### Requirement: Published diagnostics releases prove update, rollback, removal, and recovery
The official repository SHALL publish at least two compatible stable Diagnostics Channel releases with distinct exact artifacts and release markers. End-to-end acceptance SHALL install the earlier release, create and enable an instance, update explicitly to the later release, roll back explicitly, remove the package, reinstall it, and restart the application. Every transition SHALL use generic GitHub installation, installed-package, provider-registry, catalogue, and runtime-generation paths; no test-only provider registration, loose source loader, direct index mutation, or diagnostics-specific runtime branch SHALL satisfy acceptance.

#### Scenario: Diagnostics Channel updates
- **WHEN** the later compatible release is explicitly installed over the earlier active release
- **THEN** the former release SHALL become the retained rollback revision and every enabled diagnostics instance SHALL receive a fresh successor generation
- **AND** predecessor close SHALL complete before successor ready and heartbeat publication

#### Scenario: Diagnostics Channel rolls back
- **WHEN** the user explicitly rolls back after the update
- **THEN** the retained earlier exact artifact SHALL become active without another download
- **AND** enabled instances SHALL restart from fresh Lua state with the earlier release marker

#### Scenario: Diagnostics package is removed
- **WHEN** the user removes the installed Diagnostics Channel provider
- **THEN** its runtimes SHALL close and the package SHALL disappear from provider resolution
- **AND** existing diagnostics channel definitions SHALL remain persisted and unavailable

#### Scenario: Diagnostics package is reinstalled
- **WHEN** the same durable repository is installed again with a compatible exact release
- **THEN** preserved definitions SHALL resolve the restored provider and receive fresh generations
- **AND** no prior heartbeat sequence, log buffer, coroutine, or authorization SHALL be restored