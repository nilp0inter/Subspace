## MODIFIED Requirements

### Requirement: Public values, errors, and cancellation are normalized Lua data values
All host-to-Lua and Lua-to-host data exchange SHALL use normalized Lua data types: `nil`, Boolean, finite number, valid-UTF-8 string, and plain table, except for private host-constructed opaque audio Recording, Synthesized audio, and storage Mount userdata explicitly defined by the public runtime APIs. A table SHALL be either a string-keyed map or contiguous 1..n integer-keyed array; mixed, sparse, non-string map keys, and non-integer array keys SHALL be rejected. Callback results SHALL follow that callback's exact shape. Host-operation failures SHALL use `(nil, error_table)` with stable string `error`; explicit live-generation cancellation SHALL use `E_CANCELLED`; generation close SHALL discard suspended coroutines without delivering a result.

Normalization SHALL be bounded by finite host-configured table-depth, entry-count, and string-byte limits. The host SHALL reject a whole normalized value containing a cycle, metatable, function, thread, platform object, non-finite number, invalid UTF-8, invalid table shape, or userdata outside the explicit host types. All serialization, logging, scalar configuration, callback terminal return, error formatting, and ordinary normalized-data paths SHALL reject audio and mount userdata with `E_INVALID_VALUE`; they SHALL not inspect or partially strip it. Lua references retained only inside the owning state—including callbacks, cached modules, functions supplied to `spawn`/`defer`, and mount upvalues—are not normalized host data. `spawn` and `defer` are the only public calls accepting function references; neither SHALL serialize the function to Kotlin or public host data.

#### Scenario: Callback returns a value allowed by its contract
- **WHEN** a protected callback returns a normalized value permitted by its declared result shape
- **THEN** the runtime SHALL interpret it according to that callback contract
- **AND** it SHALL not coerce it into another callback's shape

#### Scenario: Callback returns a declared application failure
- **WHEN** a protected callback returns the exact declared error table
- **THEN** the runtime SHALL classify that invocation as the declared application failure
- **AND** it SHALL not issue further callbacks for that operation

#### Scenario: Live operation cancellation returns E_CANCELLED
- **WHEN** a host operation is explicitly cancelled while its actor generation remains live
- **THEN** the coroutine SHALL resume exactly once with `(nil, {error = "E_CANCELLED"})`
- **AND** Lua SHALL distinguish cancellation from success, timeout, and application failure

#### Scenario: Platform object is returned to Lua
- **WHEN** a host operation attempts to deliver a Kotlin/Android object, platform URL/URI, JNI handle, actor identity, or storage-provider object
- **THEN** the boundary SHALL deny delivery and resume with `E_INVALID_VALUE`

#### Scenario: Value contains nonserializable userdata
- **WHEN** a callback returns audio or mount userdata, passes it to logging, or nests it in configuration/error data
- **THEN** normalization SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL not expose the underlying token or authority

#### Scenario: Value contains a cycle, metatable, function, or invalid userdata
- **WHEN** normalized output contains a cycle, metatable, function, thread, platform value, or unsupported userdata
- **THEN** the runtime SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL not partially traverse or strip fields

#### Scenario: Value contains a non-finite number
- **WHEN** a callback returns NaN or positive/negative infinity
- **THEN** the runtime SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL not coerce it

#### Scenario: Table has mixed, sparse, or invalid keys
- **WHEN** a callback returns a mixed/sparse table or invalid key type
- **THEN** the runtime SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL not partially traverse or strip keys

## ADDED Requirements

### Requirement: Revised v1 reserves the generic storage and audio-file modules
The runtime SHALL reserve and inject `subspace.fs` and `subspace.audio` alongside existing `subspace.runtime`, `subspace.channel`, `subspace.log`, `subspace.transcription`, `subspace.synthesis`, and `subspace.playback`. Package source SHALL not define or shadow any `subspace.*` module. Requiring a module SHALL not grant declared capability eligibility, resource binding, or effect context. The runtime API version SHALL remain exactly `subspace-lua-v1`; no alternate module table or legacy v1 preloading set SHALL be retained.

#### Scenario: Package requires generic modules
- **WHEN** a revised-v1 package requires `subspace.fs` or `subspace.audio`
- **THEN** it SHALL receive the host-injected module
- **AND** source-map resolution SHALL never supply a package-defined replacement

### Requirement: Runtime exposes stable instance identity without lifecycle tokens
`subspace.runtime.INSTANCE_ID` SHALL be an immutable valid-UTF-8 string equal to the host channel instance ID for that Lua state. It SHALL not expose mutable selection, provider credentials, runtime generation, capability-scope identity, actor pointer, or platform identity. Same-provider sibling instances SHALL observe different values; replacement generations for the same instance SHALL observe the same value.

#### Scenario: Journal attributes durable entry
- **WHEN** Lua reads `runtime.INSTANCE_ID`
- **THEN** it SHALL receive its stable channel instance ID
- **AND** it SHALL not receive a generation or platform object

### Requirement: Host effects use opaque typed operation requests
Every yielding public host call SHALL construct a bounded typed request in a state-owned registry and yield only an opaque request token. Operation labels/outcomes SHALL not encode paths, text bodies, JSON parameters, audio tokens, mount tokens, provider identifiers, or platform locations. The host dispatcher SHALL claim a request exactly once, validate its state/generation/execution/capability/resource ownership, execute the generic operation, and deliver one normalized completion. Large bounded payloads SHALL use explicit typed transport or host-managed streams rather than concatenated labels.

#### Scenario: Filesystem write yields
- **WHEN** `fs.write_text` admits a request
- **THEN** the yielded actor outcome SHALL identify only an opaque request
- **AND** the text, mount, and path SHALL remain in the bounded host request registry

#### Scenario: Opaque request is replayed
- **WHEN** the host or Lua attempts to claim a completed, foreign, stale, or unknown request token
- **THEN** the broker SHALL reject it without another effect or Lua resumption
