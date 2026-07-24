## MODIFIED Requirements

### Requirement: Public values, errors, and cancellation are normalized Lua data values
All ordinary host-to-Lua and Lua-to-host data SHALL use `nil`, Boolean, finite number, valid-UTF-8 string, and plain map/contiguous-array tables, except for explicit state-local opaque Recording, Synthesized audio, Mount, JSON Null, SecretReference, Queue, Job, and work Effect userdata defined by public APIs. Mixed/sparse/invalid-key tables SHALL be rejected. Callback and method results SHALL follow exact contracts. Failures SHALL use `(nil, error_table)` with stable `error`; live cancellation SHALL use `E_CANCELLED`; generation/state close SHALL discard suspended coroutines without delivery.

Normalization SHALL enforce finite depth, entries, aggregate/string/key bytes, and supported-value rules. Cycles, non-approved metatables, functions, threads, platform objects, non-finite numbers, invalid UTF-8/shapes, and unsupported/foreign userdata SHALL reject the whole value. Ordinary serialization, logs, scalar configuration, callback terminals, errors, profile scalar fields, and durable work payload/effect results SHALL reject all opaque userdata unless that exact API explicitly consumes it. JSON Null is valid only in declared JSON paths. Lua references retained inside one state—including callbacks, module cache, `spawn`, `defer`, `job:effect`, and opaque-handle upvalues—are not normalized host data. Only `spawn`, `defer`, and `job:effect` SHALL accept function references; none serializes a function into public host data.

#### Scenario: Callback returns allowed value
- **WHEN** a protected callback returns a value permitted by its exact contract
- **THEN** the runtime SHALL interpret it without coercing it into another shape

#### Scenario: Callback returns declared failure
- **WHEN** a callback returns its exact application error
- **THEN** the runtime SHALL classify it as application failure and issue no further callback for that operation

#### Scenario: Live cancellation occurs
- **WHEN** an operation is cancelled while its owner remains resumable
- **THEN** it SHALL resume at most once with `E_CANCELLED`

#### Scenario: Platform object is returned
- **WHEN** host code attempts to deliver Kotlin/Android/URL/JNI/actor/platform state
- **THEN** normalization SHALL deny it with `E_INVALID_VALUE`

#### Scenario: Opaque value enters wrong path
- **WHEN** Lua logs, persists, configures, or returns audio, mount, secret reference, JSON null, queue, job, or effect outside an explicitly consuming API
- **THEN** the whole value SHALL be rejected with `E_INVALID_VALUE`
- **AND** no token, secret, or authority SHALL be inspected or stripped

#### Scenario: Invalid structural value is normalized
- **WHEN** output contains a cycle, invalid metatable, function, thread, unsupported userdata, non-finite number, or mixed/sparse table
- **THEN** the whole value SHALL be rejected with `E_INVALID_VALUE`
- **AND** no partial value SHALL cross the boundary

### Requirement: Revised v1 reserves generic storage, audio, network, data, profile, secret, and work modules
The runtime SHALL reserve and inject `subspace.runtime`, `subspace.channel`, `subspace.log`, `subspace.transcription`, `subspace.synthesis`, `subspace.playback`, `subspace.fs`, `subspace.audio`, `subspace.keyboard_output`, `subspace.http`, `subspace.json`, `subspace.profiles`, `subspace.secrets`, and `subspace.work` for every ordinary package state. Resolver states SHALL receive only the restricted subset defined by their contract. Package source SHALL not define or shadow any `subspace.*` module. Requiring a module SHALL not grant capability, resource, profile, secret, queue, or execution-context authority. API version SHALL remain exactly `subspace-lua-v1`; no legacy preload set or alias SHALL remain.

#### Scenario: Ordinary package requires generic modules
- **WHEN** package code requires one of the revised public modules
- **THEN** require SHALL return the host injection without consulting package source
- **AND** function calls SHALL remain independently authorized

#### Scenario: Resolver requires ineligible module
- **WHEN** a resolver state requires audio, keyboard, filesystem, or work
- **THEN** its operations SHALL be absent or return the exact restricted-context denial
- **AND** no ordinary channel authority SHALL be acquired

## ADDED Requirements

### Requirement: Work effect functions are protected yieldable state-local references
`Job:effect(key, function)` SHALL be the only new public function-accepting operation. The function SHALL execute immediately at most once for a new effect key under the current job's protected managed-task stack, MAY yield only through host operations authorized for the nested effect owner, and SHALL not escape, be stored in durable data, run concurrently, nest another work effect, or survive task/generation close. Its terminal normalized result SHALL be durably committed before return; a Lua throw, malformed return, raw yield, interruption, or close SHALL follow durable-work failure/indeterminate rules.

#### Scenario: Effect function yields through HTTP
- **WHEN** a job effect invokes authorized `subspace.http.request`
- **THEN** the actor SHALL suspend and resume the same protected effect function
- **AND** commit its valid normalized result before returning to outer Lua policy

#### Scenario: Effect function is nested
- **WHEN** an active effect function calls `job:effect` again
- **THEN** the nested call SHALL fail with `E_INVALID_CONTEXT` or `E_BUSY`
- **AND** it SHALL not create a second ledger entry

### Requirement: New opaque userdata remain state and owner confined
SecretReference, Queue, Job, Effect, and JSON Null SHALL use state-local unforgeable tokens and locked metatables. SecretReference SHALL additionally bind package/profile/field/revision grant; Queue instance/package/generation/declaration; Job queue/work/lease/task; Effect job/key/task; and Null only its owning state. Stringification, equality, serialization, logging, cross-state use, stale-generation use, and unsupported method calls SHALL reveal no underlying identifier or authority and SHALL return normalized invalid/stale/denied outcomes.

#### Scenario: Job object is captured by sibling task
- **WHEN** another managed task invokes a Job method
- **THEN** ownership validation SHALL reject it before ledger mutation

#### Scenario: Opaque object is stringified
- **WHEN** Lua calls `tostring` on a new opaque value
- **THEN** it SHALL receive only a non-sensitive stable type label or protected default
- **AND** no token, profile ID, secret alias, work ID, or native address SHALL appear
