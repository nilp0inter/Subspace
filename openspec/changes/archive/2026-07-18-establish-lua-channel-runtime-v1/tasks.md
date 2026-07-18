## 1. Prerequisite Change And Contract Baseline

- [x] 1.1 Sync the completed `integrate-lua-actor-runtime` delta specs into the main specs and archive that change before editing actor policy for this change
- [x] 1.2 Confirm the archived actor requirements contain the exact requirement modified by `specs/lua-actor-runtime/spec.md`
- [x] 1.3 Record `lsp references` for `ChannelRuntimeConstructionRequest`, `ChannelProviderError`, `ActorPolicy`, `ActorTaskScope`, `ActorRuntime.yieldOperation`, `LuaKernelBridge`, and `ChannelRuntime` before changing exported contracts
- [x] 1.4 Run the existing focused actor-runtime and channel-runtime-registry contract tests as the pre-change baseline

## 2. Provider-Neutral Generation Execution Context

- [x] 2.1 Define the sealed provider-neutral `GenerationExecutionContext`, typed `GenerationAdmission` outcomes with distinct `CLOSED` and `CAPACITY_EXHAUSTED` reasons, and idempotent timer-disposal contract without exposing scopes, gates, generations, actors, capability identities, or platform objects
- [x] 2.2 Add the required `generationContext` field to `ChannelRuntimeConstructionRequest` with no default value, secondary constructor, or compatibility shim
- [x] 2.3 Implement the host-owned context using the registry generation's stable instance ID, lifecycle scope, live-state authority, bounded timer ownership, and bounded task ownership
- [x] 2.4 Make context timer callbacks at-most-once, return typed admission outcomes, and suppress every accepted callback whose generation closes or is replaced before callback admission
- [x] 2.5 Make context task admission generation-bound and phase-aware: reserve and stage before readiness publication, authorize only after registry publication, remain non-preemptive relative to the current invocation slice, and reject after close
- [x] 2.6 Close and drain every context-owned timer and task, including staged reservations and function references, before the registry finishes retiring its generation
- [x] 2.7 Populate a fresh context for every pending generation in `ChannelRuntimeRegistry` before invoking its provider constructor
- [x] 2.8 Migrate every production and test construction-request callsite to supply the required context explicitly
- [x] 2.9 Add contract coverage for per-instance identity, independent same-provider contexts, distinct closed/capacity rejections, staged authorization/discard, late timer suppression, and idempotent disposal
- [x] 2.10 Verify existing Kotlin providers ignore the new context without behavioral change

## 3. Typed Compatibility And Construction Failures

- [x] 3.1 Add a provider-neutral `ChannelProviderError` case for runtime compatibility failure with requirement, required-version, and supported-version fields
- [x] 3.2 Map Lua language and API version mismatches to the typed compatibility case instead of a diagnostic-only `RuntimeConstructionFailed` detail
- [x] 3.3 Define deterministic internal program-image failures for invalid names, malformed source text, missing entry module, reserved names, and configured bounds
- [x] 3.4 Map pre-state program-image failures to typed construction outcomes; map module compilation or resolution failures after state creation to typed outcomes and deterministically close the partial state
- [x] 3.5 Add provider error tests covering exact compatibility fields and host projection through `ChannelPreparationReason.Provider`

## 4. Actor Task And Operation Policy Extensions

- [x] 4.1 Replace the actor task wall-time sentinel with an explicit optional whole-task deadline while preserving the current default for existing actor callers
- [x] 4.2 Update `ActorTaskScope` so a disabled whole-task deadline ends tasks only on return, failure, or generation close while retaining bounded concurrent admission
- [x] 4.3 Extend yielded-operation admission to accept an operation-specific deadline rather than always reading one generic actor timeout
- [x] 4.4 Preserve the existing generic operation deadline for every migrated non-Lua actor callsite
- [x] 4.5 Implement an overflow-safe `requested delay + bounded slack` deadline calculation for Lua sleep operations
- [x] 4.6 Race operation completion, deadline expiry, explicit live cancellation, and generation close through one exactly-once terminal admission
- [x] 4.7 Return `E_TIMEOUT` only when the operation deadline wins and reject a later timer completion as stale without a second Lua resume
- [x] 4.8 Return `E_CANCELLED` only for explicit cancellation while the generation remains live and deliver no result on generation close
- [x] 4.9 Route startup `spawn` through phase-aware generation-context admission so bounded capacity is reserved synchronously while tasks remain staged and non-runnable
- [x] 4.10 Authorize the complete staged set only after the activation result passes the live-generation gate and the registry publishes generation readiness; discard every staged task on startup failure, lifecycle failure, cancellation, replacement, close, or failed commit
- [x] 4.11 Latch synchronous callback contract violations in the current invocation outcome so Lua cannot hide a prohibited spawn or yield by ignoring a returned error, without creating an actor-fatal latch
- [x] 4.12 Add actor contract tests for lifecycle-long sleeping tasks, long valid sleeps, deadline-first timeout, completion-first success, live cancellation, close-without-resume, duplicate completion, and infinite active-loop interruption

## 5. Immutable Lua Program Images

- [x] 5.1 Define exact execution-requirement metadata using `luaVersion` and `apiVersion` strings and the host constants `LUA_VERSION`, `LUA_RELEASE`, and `API_VERSION`
- [x] 5.2 Define `ImmutableProgramImage` with one entry-module name and a host-supplied package-local source map
- [x] 5.3 Snapshot source-map keys and source strings once at image construction so callers cannot mutate a validated image
- [x] 5.4 Validate canonical module names with `[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*` and reject exact or prefixed `subspace` names
- [x] 5.5 Validate entry-module presence, well-formed source text, entry count, per-module byte length, and total source bytes before state creation
- [x] 5.6 Compare `luaVersion` and `apiVersion` by exact equality before constructing the kernel bridge or Lua state
- [x] 5.7 Keep program-image identity and source references out of persisted `ChannelDefinition` configuration
- [x] 5.8 Add image tests for defensive snapshotting, ignored non-execution metadata, malformed names, reserved names, missing entry module, malformed source text, every configured bound, and both version mismatches

## 6. Retained Kernel And Actor Language Boundary

- [x] 6.1 Extend the retained `LuaKernelBridge` and `LuaNativeKernelBridge` instead of creating a second JNI bridge or proof-only execution path
- [x] 6.2 Add protected creation of a Lua state with the restricted v1 standard-library environment and no `io`, `os`, `debug`, `package`, loaders, filesystem searchers, native loaders, JNI, FFI, or shared-library access
- [x] 6.3 Add generation-owned host-function registration for reserved `subspace.*` modules without exposing native handles or registry indexes
- [x] 6.4 Add protected source-map chunk compilation and execution in the owning state with typed syntax, module, memory, interruption, and bridge failures
- [x] 6.5 Add opaque generation-safe handles for loaded module values, callback functions, and managed coroutine function references
- [x] 6.6 Add raw plain-table inspection primitives needed to reject callback-table metatables and validate recognized keys without `__index`
- [x] 6.7 Define one bounded `LuaValue` normalization model for nil, booleans, finite numbers, valid-UTF-8 strings, contiguous arrays, and string-keyed maps
- [x] 6.8 Reject cycles, metatables, functions, userdata, threads, platform values, non-finite numbers, invalid UTF-8, mixed tables, sparse arrays, excessive depth, excessive entries, and excessive strings as whole values
- [x] 6.9 Keep callback functions, module return values, and the spawn function reference inside the owning Lua state; accept a function argument only for the public `spawn` call and never serialize an internal reference as host data
- [x] 6.10 Block every host-provided callable during entry-module and source-map module evaluation while still allowing pure Lua and nested `require`
- [x] 6.11 Add Rust/kernel conformance coverage for restricted globals, protected module loading, callback handles, normalized values, malformed values, interruption, allocator denial, and deterministic close
- [x] 6.12 Add JVM bridge tests for every new JNI outcome, explicit bytecode rejection with the actor still usable and closable, literal absence of the `package` global, disabled `string.dump`, and absence of candidate/proof bridges or duplicate native exports

## 7. Source-Map `require` And Runtime Environment

- [x] 7.1 Replace stock module search with a three-step resolver: reserved host module, immutable package-local source map, then typed not-found
- [x] 7.2 Reject invalid module names before lookup and prevent reserved-module fallback to plugin source
- [x] 7.3 Evaluate source-map modules synchronously in the actor's restricted environment and cache each result once per generation
- [x] 7.4 Cache and return `true` when a module returns nil or no value; otherwise cache the exact first Lua return value inside the owning state, including function-bearing library tables
- [x] 7.5 Detect recursive module loading with bounded per-generation state and raise `E_MODULE_CYCLE` through protected Lua error handling
- [x] 7.6 Remove all filesystem, `package.path`, `package.cpath`, bytecode, native module, download, patch, and default-module fallback behavior
- [x] 7.7 Keep module caches isolated across actors and generations while allowing immutable source strings to be shared without per-generation deep copy
- [x] 7.8 Add fixture coverage for host modules, nested package modules, nil-to-true caching, function-bearing module caching, cycles, invalid names, missing modules, reserved shadow attempts, independent caches, and effect-call-during-load rejection with no partial cache

## 8. Public `subspace.*` Modules

- [x] 8.1 Implement `subspace.runtime` with exact constants `LUA_VERSION = "Lua 5.4"`, `LUA_RELEASE = "5.4.8"`, and `API_VERSION = "subspace-lua-v1"` and no version function
- [x] 8.2 Implement `subspace.runtime.spawn` argument validation, synchronous bounded admission, context restrictions, `(true, nil)` success, and `E_BUSY` or `E_INVALID_CONTEXT` failures
- [x] 8.3 Implement `subspace.runtime.sleep` argument validation, timer-slot bounds, cooperative suspension, operation-specific deadline, and stable success/error pairs
- [x] 8.4 Preserve pure-Lua child coroutine composition while rejecting raw actor-boundary yields with `E_INVALID_YIELD`
- [x] 8.5 Implement `subspace.channel` as constants-only v1 surface with `LIFECYCLE_READY`, `CAPTURE_COMPLETE`, and `SOS_TRIGGERED`
- [x] 8.6 Implement generation-scoped bounded structured logging with `debug`, `info`, `warn`, and `error` functions
- [x] 8.7 Enrich accepted log entries with instance ID, generation, timestamp, and level without exposing those as plugin-controlled fields
- [x] 8.8 Reject an invalid log payload atomically with `E_INVALID_VALUE`, silently accept rate-dropped entries, and allocate no growing drop buffer
- [x] 8.9 Add module tests for exact constants, absent callables, every argument error, every context error, task saturation, timer saturation, cancellation, timeout, logging bounds, and rate limiting

## 9. Lua Callback Table And Adapter Runtime

- [x] 9.1 Load the entry module during runtime construction and require one plain callback table with a function-valued `startup` key
- [x] 9.2 Treat nil optional callbacks as absent, reject present non-function recognized callbacks, and ignore unrecognized raw keys
- [x] 9.3 Validate exact application-failure tables and the distinct readiness and input result shapes before changing host state
- [x] 9.4 Implement `LuaAdapterRuntime` snapshots with one monotonic atomic lifecycle, snapshot liveness checks inside each update transform, strict synchronous actor invocation, and idempotent close
- [x] 9.5 Invoke `startup()` with no arguments during authorized activation and prevent all staged task execution until registry readiness publication
- [x] 9.6 Invoke `handle_lifecycle({event = "ready"})` after startup, recheck context/lifecycle liveness, and atomically claim the adapter's Ready state before returning Ready to the registry
- [x] 9.7 Fail activation and discard every staged task when startup or lifecycle handling throws, returns failure, violates context, yields, cancels, closes, loses the lifecycle CAS, or fails the registry live-generation commit
- [x] 9.8 Keep input readiness initially false, invoke the first `refreshReadiness()` immediately after generation readiness publication and then periodically, enforce strict `{ready = boolean}` validation, and locally contain failures as cached not-ready
- [x] 9.9 Implement `prepareInput()` entirely from cached readiness plus `handle_input` presence without entering Lua
- [x] 9.10 Implement a per-capture `ChannelInputTarget` that projects RECORDING and PROCESSING and sends only event identity, session ID, duration, sample rate, and channel count
- [x] 9.11 Strictly map exactly `{ok = true}` to SUCCESS and exactly `{error = {code, detail}}` to FAILED while mapping malformed or ambiguous results to typed invalid outcome
- [x] 9.12 Keep raw PCM and every audio handle host-owned and return `ChannelInputResult.None` for all v1 input terminal paths
- [x] 9.13 Map host capture cancellation to IDLE and host capture failure to FAILED without invoking `handle_input`
- [x] 9.14 Implement fire-and-forget `handle_sos` with neutral absence, strict failure detection, local diagnostics, no snapshot mutation, and no generation failure
- [x] 9.15 Close actor-owned callback handles, module cache, timers, tasks, and Lua state idempotently; leave capability revocation and execution-context retirement exclusively to `ChannelRuntimeRegistry`
- [x] 9.16 Add adapter tests for callback-table validation, activation/close and snapshot/close linearization races, publication-before-task execution, first-readiness-refresh ordering, task staging/discard, readiness caching, proactive-only behavior, input state transitions, strict outcomes, SOS containment, and repeated close

## 10. Lua Channel Provider Through Existing Registries

- [x] 10.1 Implement a Lua provider descriptor with stable implementation ID, presentation metadata, an empty/test configuration schema, and no production registration
- [x] 10.2 Construct the Lua provider with its immutable host-supplied program image rather than decoding source or image identity from channel configuration
- [x] 10.3 Implement `constructRuntime` through `ActorRuntimeFactory`, the supplied generation context, the supplied capability scope, and the retained kernel bridge
- [x] 10.4 Run compatibility and image validation before actor/state creation and close partially created resources on every later construction failure
- [x] 10.5 Resolve explicit test catalogue definitions through the ordinary `ChannelImplementationProviderRegistry` and `ChannelRuntimeRegistry` paths without Lua branches
- [x] 10.6 Verify two instances sharing one Lua provider receive independent actors, states, caches, tasks, timers, capability scopes, and generation contexts
- [x] 10.7 Verify replacement drains and closes generation G before generation H activation, readiness publication, first readiness refresh, or background task execution; separately close or cancel one live instance while proving a sibling instance's operation continues
- [x] 10.8 Verify a late generation-G completion cannot enter generation H or mutate its snapshots, logs, module cache, or Lua globals
- [x] 10.9 Verify an unregistered Lua provider projects ordinary typed missing-provider unavailability and never constructs a fallback runtime

## 11. Black-Box JVM Contract Verification

- [x] 11.1 Build immutable Lua fixture images for valid callbacks, proactive timers, package modules, entry/lazy-module effect attempts, invalid images, malformed callbacks, and deterministic race control
- [x] 11.2 Exercise every fixture through the real provider registry, runtime registry, generation context, capability scope, actor kernel, adapter, replacement, and shutdown path
- [x] 11.3 Verify ordinary startup contains no Lua provider registration and creates no Lua actor or state
- [x] 11.4 Verify package-local `require`, restricted globals, bytecode rejection with continued actor usability, literal `package` absence, disabled `string.dump`, module cache isolation, and absence of filesystem/native fallback
- [x] 11.5 Verify proactive startup-spawned polling continues while the channel is unselected and beyond the former generic task deadline
- [x] 11.6 Verify sleep completion-first, deadline-first, cancellation-first, close-first, duplicate, and stale terminal races with exactly-once Lua entry
- [x] 11.7 Verify entry and lazy-module evaluation reject spawn, sleep, and logging with typed effect-call-during-load failures and no partial cache; verify callbacks reject yield or spawn outside their specified contexts
- [x] 11.8 Verify strict callback success/failure tables, malformed tables, local containment, and host snapshot transitions
- [x] 11.9 Verify normalized-value bounds and atomic rejection for deep, large, cyclic, metatable-bearing, function-bearing, sparse, mixed, non-finite, and invalid-UTF-8 values
- [x] 11.10 Verify log recording, enrichment, invalid-payload rejection, per-generation isolation, bounded storage, and silent rate-drop semantics
- [x] 11.11 Verify exact compatibility failure occurs before state creation and preserves the catalogue definition's stored configuration
- [x] 11.12 Run only the focused JVM test classes added or modified by this change and resolve every failure before Android verification

## 12. Android Device Conformance And End-To-End Verification

- [x] 12.1 Extend instrumentation fixtures to exercise the production JNI kernel through the complete Lua provider-to-actor path on the supported arm64 device
- [x] 12.2 Verify restricted source loading, nested `require`, callback-table validation, startup, lifecycle-ready, readiness, input, SOS, spawn, and sleep on device
- [x] 12.3 Verify instruction-hook interruption, allocator denial, malformed source, compatibility rejection, and deterministic state closure on device
- [x] 12.4 Verify replacement and service shutdown suppress stale timer and coroutine effects without a native crash or post-close Lua entry
- [x] 12.5 Record immutable device evidence with APK/build provenance, per-case outcomes, and observed latency or memory distributions without declaring public numeric guarantees
- [x] 12.6 Re-run the focused instrumentation class and confirm every required scenario passes on the supported physical device
- [x] 12.7 Run the focused existing foreground-service and built-in-provider regression tests affected by construction-request migration
- [x] 12.8 Build the debug APK and release-equivalent APK, inspect packaged native libraries, and verify no proof harness, duplicate bridge, bytecode loader, or production Lua provider registration ships
- [x] 12.9 Execute the ordinary application startup smoke path and confirm existing Kotlin channels remain behaviorally unchanged and no Lua state is created
