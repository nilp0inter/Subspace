## ADDED Requirements

### Requirement: Plugin structured logs enter unified observability under bounded host policy
The logging subsystem SHALL accept host-attributed structured records emitted by live Lua channel generations through a dedicated bounded plugin sink. Each projected record SHALL map `debug`, `info`, `warn`, and `error` to the corresponding host severity; use the fixed host-owned tag `LuaChannel`; preserve the host timestamp; and encode a canonical bounded message containing the channel instance ID, runtime generation, and normalized plugin payload. Plugin code SHALL NOT control the host tag, severity mapping, timestamp, instance ID, generation, throwable, persistence destination, or log-level policy. Successfully projected plugin records SHALL be mirrored to Android Logcat, persisted in the existing rotating log store, published through the existing reactive stream, and displayed by the existing Log Analysis surface.

The plugin sink SHALL have finite admission and message-size bounds and SHALL never use an unbounded overflow queue. Records rejected or rate-dropped by `subspace.log` SHALL not reach it. When sink capacity, canonical serialization, disk persistence, or shutdown prevents projection, the host SHALL drop the affected projection without blocking or re-entering Lua, SHALL retain at most bounded host-owned loss diagnostics, and SHALL not recursively include the rejected plugin payload.

#### Scenario: Accepted plugin info record is projected
- **WHEN** a live Lua generation emits an accepted `info` record with a valid normalized payload
- **THEN** the logging subsystem SHALL emit at most one `Info` entry tagged `LuaChannel` with host-attributed instance and generation fields
- **AND** SHALL mirror, persist, stream, and display it through the existing observability surfaces

#### Scenario: Plugin record is filtered by host level
- **WHEN** the configured global or `LuaChannel` tag threshold excludes the mapped plugin severity
- **THEN** the record SHALL be omitted from Logcat, persistence, and the reactive log stream under the ordinary host filtering policy
- **AND** SHALL NOT alter the plugin's already completed `subspace.log` result

#### Scenario: Plugin sink reaches capacity
- **WHEN** accepted plugin records arrive faster than the bounded observability sink can process them
- **THEN** the sink SHALL reject excess projections immediately and retain at most bounded loss accounting
- **AND** SHALL NOT suspend Lua, allocate an unbounded waiter or record queue, or displace the runtime generation's lifecycle work

#### Scenario: Plugin record survives application restart
- **WHEN** a projected plugin record was committed to the rotating persistent log before application shutdown
- **THEN** the Log Analysis screen SHALL load it after restart with its original timestamp, level, tag, instance, generation, and canonical payload text
- **AND** SHALL NOT imply that the originating Lua generation was restored

### Requirement: Plugin observability excludes executable and sensitive host data
Plugin log projection SHALL contain only the normalized payload accepted by `subspace.log` and host-generated attribution. The host SHALL NOT enrich a plugin record with Lua source, package archive bytes or paths, full artifact digests, repository credentials, channel configuration, host credentials, captured or encoded audio, transcripts, message content not explicitly supplied in the accepted payload, Bluetooth addresses, Android objects, native handles, stack traces, or capability objects. Canonical serialization SHALL be deterministic and bounded and SHALL reject rather than truncate, coerce, or partially persist a payload that cannot fit the sink contract.

#### Scenario: Plugin attempts to log a disallowed runtime value
- **WHEN** a payload contains userdata, a function, a coroutine, a native handle, a cycle, an invalid string, or another value rejected by Lua normalization
- **THEN** no corresponding persistent or reactive host log SHALL be created
- **AND** observability SHALL NOT stringify the value, inspect the platform object, or log a partial payload

#### Scenario: Host attribution is attached
- **WHEN** a valid plugin payload is projected
- **THEN** the host SHALL attach only the semantic channel instance ID, runtime generation, timestamp, and mapped level required by the log contract
- **AND** SHALL NOT attach package-store internals, capability identities, or device identifiers

### Requirement: Log Analysis can isolate Diagnostics Channel activity
The Log Analysis surface SHALL allow plugin records to be isolated through its existing level, tag, and text-search controls. A user SHALL be able to filter by the fixed `LuaChannel` tag and search canonical message text for a diagnostics instance identifier, generation, event name, or release marker without a diagnostics-specific screen or parser. Clearing logs and changing tag thresholds SHALL use the existing observability actions and SHALL NOT mutate the installed package, catalogue definition, or Lua runtime.

#### Scenario: User filters plugin records
- **WHEN** the user selects the `LuaChannel` tag and searches for a Diagnostics Channel event name
- **THEN** the view SHALL show matching projected plugin records in timestamp order
- **AND** SHALL omit unrelated core and plugin records that do not satisfy the active filters

#### Scenario: User clears logs
- **WHEN** the user clears the Log Analysis store while a Diagnostics Channel generation remains live
- **THEN** existing persisted and in-memory entries SHALL be removed according to the ordinary clear contract
- **AND** subsequent accepted Diagnostics Channel records SHALL continue to appear without restarting the package runtime
