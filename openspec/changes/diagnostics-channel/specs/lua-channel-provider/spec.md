## MODIFIED Requirements

### Requirement: `subspace.log` provides bounded structured logging with typed validation
The `subspace.log` module SHALL be a host-injected, reserved module. It SHALL expose four named log functions: `debug`, `info`, `warn`, and `error`. Each function SHALL accept a single structured payload table (not a level-plus-payload pair). Log calls SHALL be non-blocking, non-yielding, and SHALL be rate-limited such that an excessive number of log entries from one actor does not exhaust host memory. The log entry SHALL include the channel instance identifier, runtime generation, a timestamp, the level, and the provided payload. Log payloads SHALL be normalized Lua tables without executable code, file handles, userdata, or coroutine references. Log functions are usable from the `startup` callback and all subsequent event callbacks and spawned background tasks, but SHALL NOT be invoked during any source-map module top-level evaluation. A log call during module top-level evaluation SHALL produce a typed construction failure for the entry module or fail the module load for a lazily required module.

Each log function SHALL return `(true, nil)` when the entry is recorded or silently rate-dropped within the bounded actor window, and `(nil, {error = "E_INVALID_VALUE"})` when the whole payload is rejected as invalid. The host SHALL NOT recursively log an invalid payload, SHALL NOT strip or partially write fields, and SHALL NOT suspend, fail, or crash the calling coroutine when a payload is rejected.

Every record actually accepted into the actor's bounded log buffer SHALL be offered exactly once to a bounded host observability sink. The sink SHALL map the Lua level to the corresponding host log level, use a host-owned plugin tag, and serialize only the host-attributed instance ID, runtime generation, timestamp, and normalized payload. It SHALL write no throwable and SHALL NOT accept a plugin-supplied tag, timestamp, instance identity, generation, or log level. A record silently dropped by the actor rate limit, rejected by value validation, or received after its generation has closed SHALL NOT enter host observability. Host-sink saturation or persistence failure SHALL remain bounded, SHALL NOT block or re-enter Lua, and SHALL NOT change the result already returned to the plugin.

#### Scenario: Program writes a structured log entry
- **WHEN** a callback executes `subspace.log.info({message = "timer fired", duration_ms = 1520})`
- **THEN** the runtime SHALL record a structured log entry associated with the calling channel instance
- **AND** the entry SHALL include the instance identifier, runtime generation, timestamp, level `info`, and the supplied payload fields
- **AND** the function SHALL return `(true, nil)`
- **AND** the accepted record SHALL be offered exactly once to the bounded host observability sink

#### Scenario: Log payload contains disallowed types
- **WHEN** a callback calls `subspace.log.warn({handle = some_userdata})`
- **THEN** the runtime SHALL reject the entire log call with a typed validation result
- **AND** the function SHALL return `(nil, {error = "E_INVALID_VALUE"})`
- **AND** it SHALL NOT write a partial or incomplete actor or host log entry
- **AND** it SHALL NOT recursively log the invalid payload
- **AND** it SHALL NOT suspend, fail, or crash the calling coroutine

#### Scenario: Log rate limit is exceeded
- **WHEN** a callback issues log entries faster than the bounded actor rate limit allows
- **THEN** the runtime SHALL silently drop subsequent log entries within that actor's current window
- **AND** the function SHALL return `(true, nil)` for dropped entries
- **AND** it SHALL NOT forward dropped entries to host observability
- **AND** it SHALL NOT block the caller or allocate growing buffers for dropped entries

#### Scenario: Accepted record is mapped to host observability
- **WHEN** an accepted Lua log record reaches the host sink while its runtime generation remains live
- **THEN** the sink SHALL preserve its semantic level and host timestamp and SHALL encode its normalized payload under a host-owned plugin tag
- **AND** the persistent and reactive log surfaces SHALL receive at most one corresponding entry

#### Scenario: Generation closes before a pending record is published
- **WHEN** a runtime generation closes or is replaced before its pending accepted record can enter the host sink
- **THEN** the sink SHALL suppress the stale record or retain its original predecessor attribution according to the accepted-publication boundary
- **AND** SHALL never attribute that record to the successor generation

#### Scenario: Host observability sink is saturated
- **WHEN** the bounded host sink cannot accept another plugin record immediately
- **THEN** the host SHALL drop or coalesce the projection under bounded policy and record only bounded host-owned loss diagnostics
- **AND** SHALL NOT block Lua, grow an overflow queue, retry without bound, or report a second result to the plugin
