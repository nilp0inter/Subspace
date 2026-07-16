## 1. Candidate And Build Boundaries

- [x] 1.1 Inspect the existing Rust `cdylib`, Cargo NDK, Gradle native-library, release-profile, and JNI error-mapping paths and record the constraints the Lua proof bridge must preserve
- [x] 1.2 Pin the stock Lua 5.4 patch release and candidate maintained binding or C-shim dependencies with reproducible source provenance
- [x] 1.3 Define the two minimum bridge candidates behind one instrumentation-only semantic interface: JVM-owned lifecycle/scheduling and native-owned coroutine scheduling
- [x] 1.4 Define correctness-first candidate selection gates and the evidence fields required to compare topology, thread ownership, memory, latency, and maintainability
- [x] 1.5 Add an isolated native build boundary whose Rust panic strategy and Lua error handling do not silently change the existing Parakeet, Supertonic, or Ogg libraries
- [x] 1.6 Wire the pinned Lua proof library into the existing Nix, Cargo/NDK, Gradle, `arm64-v8a`, debug, and release build paths without loading it during ordinary application startup

## 2. Common Opaque Bridge Contract

- [x] 2.1 Add internal Kotlin proof types for opaque state, state-generation, coroutine, and operation identifiers without exposing native pointers or registry indexes
- [x] 2.2 Add normalized bridge outcomes for completion, yield, syntax failure, runtime failure, memory failure, interruption, cancellation, invalid ownership, stale completion, and closure
- [x] 2.3 Implement native ownership tables that validate every coroutine and operation against its owning state generation
- [x] 2.4 Implement independent state creation with per-state global environment, module cache, allocator accounting, and terminal ownership
- [x] 2.5 Implement source-only chunk loading and explicitly reject binary chunks, dynamic C-module searchers, `package.loadlib`, JNI, FFI, and plugin-provided shared libraries
- [x] 2.6 Implement protected source loading, entrypoint execution, callback invocation, coroutine resume, and error normalization so expected inputs cannot reach Lua panic or Rust panic
- [x] 2.7 Implement idempotent state close that atomically invalidates descendant coroutine and operation identifiers before native memory is released

## 3. Independent-State And Protected-Failure Experiments

- [x] 3.1 Add host tests proving two states do not share globals, module caches, coroutine identity, allocator accounting, or close effects
- [x] 3.2 Add malformed-source and invalid-entrypoint experiments that return normalized syntax or validation failures and leave states closable
- [x] 3.3 Add callback experiments for string and non-string Lua errors, nested protected calls, traceback normalization, and repeated invocation after ordinary failure
- [x] 3.4 Add foreign-state, unknown, stale-generation, and already-closed handle experiments that prove no Lua entry or mutation occurs
- [x] 3.5 Add Android instrumentation proving adversarial script and lifecycle inputs do not escape JNI, invoke Lua panic, invoke Rust panic, or terminate an unrelated proof state

## 4. Coroutine Continuation And Terminal Races

- [x] 4.1 Implement a proof host function that yields an opaque operation token without retaining an operating-system thread
- [x] 4.2 Implement success and failure resumption that re-enters only the owning live coroutine and returns its protected terminal outcome
- [x] 4.3 Implement exactly-once terminal admission for resume, cancel, and close requests targeting one operation token
- [x] 4.4 Add duplicate-completion and completion-after-return experiments proving Lua resumes at most once
- [x] 4.5 Add cancellation-versus-completion race experiments with deterministic accepted, cancelled, completed, and stale outcomes
- [x] 4.6 Add state-close-versus-completion race experiments proving late completion never accesses freed Lua or coroutine memory
- [x] 4.7 Stress repeated create, yield, resume, cancel, and close cycles and record any retained native handle or allocation

## 5. Pure-Lua Interruption And Memory Accounting

- [x] 5.1 Install a protected instruction-count interruption check whose proof budget is configurable without becoming a public plugin contract
- [x] 5.2 Add infinite-loop, recursive, metamethod, and representative compute experiments that measure finite interruption latency and close the affected state
- [x] 5.3 Prove an interrupted state cannot produce a later continuation or native effect and that an unrelated state remains executable
- [x] 5.4 Ensure proof host functions either complete in a deliberately small bounded native call or yield before external work, and add a negative test that detects a blocking candidate path
- [x] 5.5 Implement a per-state Lua allocator that records current, peak, denied, and terminal allocation accounting
- [x] 5.6 Add allocator-denial experiments during state creation, source loading, coroutine creation, table growth, error handling, and representative execution wherever Lua permits injection
- [x] 5.7 Prove recoverable allocation denial returns a normalized memory outcome, leaves the state closable, and does not change another state's accounting or usability
- [x] 5.8 Record allocations owned by the bridge or binding separately from Lua-state allocator totals so teardown evidence does not overclaim released memory

## 6. Bridge And Thread-Topology Experiments

- [x] 6.1 Implement the minimum JVM-owned lifecycle/scheduling candidate using the common opaque bridge and continuation semantics
- [x] 6.2 Implement the minimum native-owned coroutine-scheduling candidate using the same semantic operations and proof workloads
- [x] 6.3 Exercise concurrent JVM callers targeting one state and prove each candidate serializes or rejects entry without concurrent Lua execution
- [x] 6.4 Exercise multiple independent states under stable affinity and record thread count, state ownership, continuation behavior, interruption, cancellation, and close results
- [x] 6.5 Exercise affinity-lane scheduling and serialized worker migration only where the selected binding and exposed standard libraries provide a defensible safety basis
- [x] 6.6 Measure JNI transitions, source execution, yield, resume, cancel, and close latency for equivalent workloads in each viable candidate
- [x] 6.7 Reject any candidate that violates protected execution, exactly-once continuation, deterministic close, ownership isolation, nonblocking host-function, or allocator requirements before performance comparison

## 7. Android Device Evidence And Disposition

- [x] 7.1 Add an internal instrumentation entrypoint that runs the proof suite without registering a provider, adding navigation, or creating Lua states during ordinary startup
- [x] 7.2 Record device model, Android version, build type, Lua version, binding or shim version, native build settings, thread topology, experiment parameters, and run identifiers
- [x] 7.3 Measure state creation and close latency plus empty, source-loaded, coroutine-loaded, representative-allocation, peak, and terminal memory for one and several states
- [x] 7.4 Measure instruction-hook overhead and interruption latency across representative hook intervals without converting an observed interval into a public limit
- [x] 7.5 Run multi-state and cancellation/completion/close race stress experiments and record outcome counts, distributions, failures, and retained resources
- [x] 7.6 Compare viable candidates by correctness, cross-boundary state ownership, cancellation complexity, thread requirements, memory, and latency in that priority order
- [x] 7.7 Update `design.md` with the complete reproducible evidence, selected topology or negative substrate disposition, rejected alternatives, and recommended starting policy ranges for the production-runtime change
- [x] 7.8 Remove losing bridge candidates, unused dependencies, JNI exports, build tasks, instrumentation switches, and dormant feature paths while retaining the selected kernel and conformance coverage when the disposition is positive

## 8. Follow-Up Boundaries

- [x] 8.1 Record discovered requirements and a bounded follow-up scope for the production per-instance actor scheduler, lifecycle, operation/background task scopes, terminal admission, replacement draining, readiness, failure latch, and runtime-registry integration
- [x] 8.2 Record discovered requirements and a bounded follow-up scope for the versioned Lua runtime and Subspace-native API modules
- [x] 8.3 Record discovered requirements and bounded follow-up scopes for package identity/install/update/rollback, declarative configuration/credentials/state, durable output/RSM controls, discovery/trust UX, and official channel migrations
- [x] 8.4 Confirm the follow-up boundaries do not create or partially implement those changes and that failed proof gates are carried into the relevant future proposal rather than silently weakened

## 9. Verification

- [x] 9.1 Run the focused Rust/native bridge tests covering ownership, protected errors, continuation, cancellation, closure, interruption, allocator denial, and candidate state machines
- [x] 9.2 Run the focused JVM tests covering opaque identifiers, normalized outcomes, exactly-once terminal admission, stale completion, and instrumentation composition
- [x] 9.3 Run the complete Android substrate instrumentation suite on a supported physical Android device for debug and release-equivalent native builds and preserve the reproducible results in the design disposition
- [x] 9.4 Verify ordinary application startup and existing Kotlin channel composition create no Lua state and expose no proof UI, provider, package, or public API
- [x] 9.5 Build the affected Android variants through the repository devshell and verify the selected native library is packaged only for the supported ABI with expected symbol visibility
- [x] 9.6 Run `nix flake check --no-write-lock-file` after any required new flake-visible source is tracked, without staging or modifying unrelated user work
- [x] 9.7 Confirm rollback requires only removal of the internal bridge, pinned dependency, build wiring, and proof harness, with no persisted-data or user-visible migration
