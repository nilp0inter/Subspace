## ADDED Requirements

### Requirement: The proof embeds pinned source-only Lua 5.4 on the supported Android target
The system SHALL build one pinned stock Lua 5.4 patch release reproducibly for Android API 31+ and `arm64-v8a`, SHALL execute proof chunks from Lua source, and SHALL NOT expose plugin-provided bytecode, `package.loadlib`, C-module searchers, JNI, FFI, or plugin-provided shared-library loading.

#### Scenario: Android proof library loads
- **WHEN** the debug and release proof builds run on the supported Android architecture
- **THEN** the application SHALL load the pinned Lua runtime and execute a source chunk through the internal proof bridge
- **AND** the proof SHALL report the exact Lua and binding or shim versions used

#### Scenario: Binary or native module loading is requested
- **WHEN** a proof chunk attempts to load a binary chunk or dynamically load a native module
- **THEN** the bridge SHALL reject the operation without executing the supplied binary or shared library
- **AND** the Lua state and Android process SHALL remain usable

### Requirement: Lua states are independent and accessed through opaque ownership
The proof SHALL create independent Lua states whose globals, module caches, coroutines, allocator accounting, and lifecycle are not observably shared. Kotlin SHALL address native states, coroutines, and suspended operations only through opaque identifiers validated against their owning state generation.

#### Scenario: Two states mutate equal global names
- **WHEN** two proof states assign different values to the same Lua global and load package-local modules
- **THEN** each state SHALL observe only its own value and module cache
- **AND** closing either state SHALL NOT change the surviving state's values or usability

#### Scenario: Handle belongs to another state
- **WHEN** Kotlin submits a coroutine or operation handle to a state that does not own it
- **THEN** the bridge SHALL return a typed invalid-owner or stale outcome
- **AND** it SHALL NOT enter or mutate either Lua state

### Requirement: Every JVM-to-Lua execution path is protected
Source loading, entrypoint execution, callback invocation, coroutine resume, error normalization, and any close helper that executes Lua SHALL run through protected Lua execution or an equivalent binding guarantee. Expected syntax, runtime, cancellation, quota, invalid-handle, and lifecycle failures SHALL return normalized outcomes and SHALL NOT reach Lua's process-aborting panic path or a Rust process-aborting panic.

#### Scenario: Lua source has a syntax error
- **WHEN** the proof loads malformed Lua source
- **THEN** the bridge SHALL return a normalized syntax failure
- **AND** the state SHALL remain closable
- **AND** another state SHALL remain usable

#### Scenario: Lua callback raises an error
- **WHEN** an invoked Lua callback raises a string or non-string error object
- **THEN** the bridge SHALL return a bounded normalized runtime failure with diagnostic context
- **AND** the Android process SHALL remain alive
- **AND** no JNI or Rust panic SHALL escape the bridge

### Requirement: Coroutine suspension and resumption cross the bridge exactly once
The proof SHALL allow a protected Lua callback to yield a host-operation token, release the native execution thread, and later resume the same coroutine with a normalized success or failure value. Each admitted operation token SHALL accept at most one terminal resume, cancellation, or close outcome.

#### Scenario: Suspended operation completes
- **WHEN** a Lua callback yields for a host operation and Kotlin completes the owning token while the state is live
- **THEN** the bridge SHALL resume the owning coroutine exactly once with the supplied normalized result
- **AND** the callback SHALL complete without retaining an operating-system thread during suspension

#### Scenario: Completion is delivered twice
- **WHEN** Kotlin submits two terminal completions for the same operation token
- **THEN** at most one completion SHALL resume Lua
- **AND** the duplicate SHALL return an already-completed or stale outcome without another Lua effect

### Requirement: Cancellation and state closure suppress late continuations
The proof SHALL support cancellation of suspended operations and idempotent closure of a Lua state. Closing a state SHALL invalidate all owned coroutine and operation handles, SHALL prevent later continuation delivery, and SHALL release native state ownership deterministically from the bridge caller's perspective.

#### Scenario: Completion races cancellation
- **WHEN** cancellation and completion race for one suspended operation
- **THEN** exactly one terminal outcome SHALL win
- **AND** Lua SHALL be resumed at most once
- **AND** the losing request SHALL observe a cancelled, completed, or stale terminal result

#### Scenario: Completion arrives after state close
- **WHEN** a state closes while an operation is suspended and its completion arrives later
- **THEN** the bridge SHALL reject the completion as closed or stale
- **AND** it SHALL NOT access freed Lua state or coroutine memory
- **AND** repeated close requests SHALL remain idempotent

### Requirement: Uncooperative pure-Lua execution is interrupted within a measured finite bound
The proof SHALL install an instruction-count hook or equivalent protected check that interrupts pure-Lua execution which exceeds the configured proof budget. Interruption SHALL normalize the affected state as failed or closing, SHALL permit deterministic state teardown, and SHALL leave unrelated states usable. The proof SHALL NOT claim that this mechanism interrupts a blocking native host function.

#### Scenario: Lua executes an infinite loop
- **WHEN** a proof callback enters an infinite pure-Lua loop
- **THEN** the bridge SHALL interrupt it within a recorded finite instruction and elapsed-time bound
- **AND** the affected state SHALL close without relying on cooperative Lua return
- **AND** another proof state SHALL continue executing

#### Scenario: Host operation would block
- **WHEN** a proof host operation represents work that cannot complete in a deliberately small bounded native call
- **THEN** the bridge SHALL yield a host-operation token before that work executes
- **AND** it SHALL NOT block the Lua execution thread waiting for external completion

### Requirement: Per-state allocation is accounted and denial is exercised
Each proof state SHALL use an allocator that records current and peak Lua-managed native bytes and can deny allocation at a configured proof limit. Allocation denial under protected execution SHALL return a normalized memory failure where Lua permits recovery, SHALL leave the state closable, and SHALL NOT alter another state's accounting or usability.

#### Scenario: State exceeds its proof allocation limit
- **WHEN** a proof chunk requests allocation beyond its configured state limit
- **THEN** the bridge SHALL report a normalized memory failure without granting the denied allocation
- **AND** the state SHALL remain deterministically closable
- **AND** a separate state SHALL continue executing within its own allocation accounting

#### Scenario: State closes after representative allocation
- **WHEN** a state loads source, creates coroutines and tables, records peak allocation, and then closes
- **THEN** the proof SHALL record the terminal allocator accounting
- **AND** it SHALL identify any retained native allocation owned outside the Lua state rather than reporting it as released Lua memory

### Requirement: Lua state entry is serialized and thread topology is evidenced
The bridge SHALL ensure that one Lua state is never entered concurrently. The proof SHALL exercise stable affinity and any candidate serialized-migration topology with multiple independent states, coroutine resumptions, interruption, cancellation, and closure before selecting the production-facing substrate topology.

#### Scenario: Concurrent callers target one state
- **WHEN** multiple JVM callers concurrently request entry to the same proof state
- **THEN** the bridge SHALL serialize, reject, or queue those requests according to one recorded ownership rule
- **AND** no two callers SHALL execute Lua instructions in that state concurrently

#### Scenario: Independent states execute under the candidate topology
- **WHEN** several proof states repeatedly execute, yield, resume, and close under the candidate thread topology
- **THEN** each state SHALL preserve its ownership and continuation invariants
- **AND** the proof SHALL record the topology, thread count, latency, and failures used for selection

### Requirement: The proof records reproducible correctness and device evidence
The change SHALL record the pinned source versions, build type, target device, experiment parameters, correctness outcomes, state memory, startup and close latency, continuation latency, interruption latency and overhead, allocator-denial behavior, multi-state behavior, and cancellation/close race results. Numeric observations SHALL be evidence for later policy and SHALL NOT become a public plugin compatibility promise in this change.

#### Scenario: Candidate completes the experiment suite
- **WHEN** a bridge candidate completes host and Android instrumentation experiments
- **THEN** the recorded evidence SHALL identify every passed and failed correctness gate
- **AND** it SHALL contain enough version and parameter information to rerun the experiments

#### Scenario: Numeric measurement varies across runs
- **WHEN** device measurements differ between executions
- **THEN** the evidence SHALL preserve the observed range or distribution and test conditions
- **AND** the change SHALL NOT silently convert one observed value into a normative plugin limit

### Requirement: One substrate disposition is selected and experimental alternatives are removed
The completed change SHALL select the viable bridge and thread topology with the smallest cross-boundary ownership and cancellation surface after correctness gates pass. If no candidate passes, the completed evidence SHALL record stock in-process Lua 5.4 as not yet viable and SHALL identify the engine, bridge, or process-topology question for a follow-up. Losing experimental code, dependencies, JNI exports, build tasks, and feature flags SHALL NOT remain active.

#### Scenario: One or more candidates pass every correctness gate
- **WHEN** viable candidates have complete evidence
- **THEN** the design SHALL record one selected topology and the reasons alternatives were rejected
- **AND** the repository SHALL retain only the selected internal kernel and its conformance coverage

#### Scenario: No candidate passes every correctness gate
- **WHEN** every candidate violates at least one required correctness or containment property
- **THEN** the change SHALL record a negative substrate disposition with the failing gates
- **AND** it SHALL NOT introduce a production Lua runtime or silently weaken the requirements

### Requirement: The substrate proof does not alter current channel behavior
The proof bridge and harness SHALL remain internal and non-user-visible. This change SHALL NOT register a Lua provider, load third-party packages, expose a public Lua ABI, alter persisted channel definitions, or change existing channel routing, PTT, runtime-registry, capability, audio, UI, foreground-service, or release behavior.

#### Scenario: Application runs without invoking proof instrumentation
- **WHEN** the application starts and uses existing Kotlin channels normally
- **THEN** no Lua state SHALL be created by ordinary production channel startup
- **AND** existing channel and service behavior SHALL remain unchanged

#### Scenario: Proof change is rolled back
- **WHEN** the internal bridge, dependency, build wiring, and proof harness are removed
- **THEN** no persisted-data migration or user-visible recovery action SHALL be required
