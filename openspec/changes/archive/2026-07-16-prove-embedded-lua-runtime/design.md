## Context

`PLUGIN_SYSTEM_VISION.md` assumes independently configured Lua programs can eventually run inside the Android foreground-service lifetime, but the repository currently has only coarse JNI bridges to process-local Rust engines. Those bridges return completed results; they do not maintain many independent native states, suspend and resume Lua coroutines across JNI, race cancellation against completion, interrupt uncooperative Lua, or close states with outstanding continuations.

The current Android build targets API 35 with minimum API 31 and packages only `arm64-v8a`. Native libraries are Rust `cdylib` crates built through Cargo NDK. The shared Rust release profile uses `panic = "abort"`, while stock Lua calls `abort` after an unprotected panic. A production plugin runtime cannot rely on either failure path for ordinary script, quota, bridge, or lifecycle errors.

The desired product semantics are already bounded: plugins are trusted and run in process; pure-Lua errors, infinite loops, quotas, task failures, and stale effects must be isolated per instance, but the system does not claim to survive a Lua-engine defect, JNI/Rust defect, unprotected panic, unrecoverable process OOM, or Android process death. This change proves the native substrate and selects a bridge topology before later changes modify the channel runtime contract.

### Existing native constraints observed

- `rust/Cargo.toml` is a Cargo workspace whose existing JNI engines are independent `cdylib`
  crates. Its shared release profile uses thin LTO, one codegen unit, symbol stripping, and
  `panic = "abort"`; the proof must not alter that profile or the behavior of those crates.
- `app/build.gradle.kts` cross-compiles each native crate with Cargo NDK for
  `aarch64-linux-android`, sets `CARGO_NDK_TARGET_API=31`, copies each release `.so` into the
  generated `arm64-v8a` JNI directory, and makes `preBuild` depend on those isolated tasks.
- Android packages only `arm64-v8a`, uses NDK `29.0.14206865`, and loads each existing JNI
  library lazily from its owning Kotlin bridge. The proof must follow that pattern without a
  startup reference.
- Existing JNI bridges map operational failures to explicit return values at the JNI boundary.
  The Lua bridge requires a stronger typed outcome mapping because syntax, runtime, memory,
  interruption, ownership, cancellation, and closure are expected inputs rather than panics.
- `flake.nix` supplies Rust, Cargo NDK, Android API 31/35, NDK, JDK 17, and Gradle through the
  repository devshell. Native compilation remains Gradle/Cargo-driven; the flake currently has
  no native package or check output whose behavior needs to change.

## Goals / Non-Goals

**Goals:**

- Build pinned stock Lua 5.4 source for the supported Android target without allowing plugin bytecode or native modules.
- Prove independent state creation, protected execution, coroutine suspension/resumption, cancellation, bounded pure-Lua interruption, memory accounting and denial, deterministic closure, and stale-completion rejection.
- Compare the smallest credible JVM/native ownership boundaries using equivalent probes and select one from correctness and measured evidence.
- Establish the in-process containment ceiling with adversarial instrumentation rather than an undocumented assumption.
- Record device measurements and follow-up change boundaries needed for the production actor runtime and ecosystem.

**Non-Goals:**

- No public Lua ABI, compatibility promise, package format, GitHub integration, discovery, installation, update, signing, credential, HTTP, filesystem, configuration-schema, durable-message, or RSM-control contract.
- No external package execution, mutable-branch execution, plugin-supplied bytecode, JNI, FFI, C modules, or shared-object loading.
- No registration of a Lua channel provider, no migration of a Kotlin channel, and no current channel, PTT, runtime-registry, capability, audio, persistence, UI, or foreground-service behavior change.
- No execution guarantee after the service or Android process stops.
- No claim of containment for native defects or process-wide resource failure.
- No permanent second native bridge, test-only user surface, feature flag, or dormant alternative implementation after topology selection.

## Decisions

### D1. Treat this as a decision-grade substrate proof, not the production plugin runtime

The deliverable is an internal executable kernel and evidence that later runtime work can depend on. It does not add lifecycle callbacks, actor mailboxes, task APIs, channel routing, or public Subspace modules. This keeps engine and JNI risk separate from changes to user-visible channel behavior.

**Alternative considered:** introduce the full actor scheduler and a first Lua channel immediately. Rejected because the repository has no proven coroutine/JNI continuation pattern, and a failure would conflate engine, scheduler, channel, and Android lifecycle defects.

**Alternative considered:** write only a design comparison. Rejected because thread affinity, interruption, allocator behavior, JNI continuation cost, and teardown races are empirical on the supported device.

### D2. Prove stock Lua 5.4 with source-only execution

The proof uses one pinned Lua 5.4 patch release and compiles the upstream C VM for `arm64-v8a`. All proof chunks are loaded as source. The bridge does not expose binary chunk loading, `package.loadlib`, C-module searchers, JNI, FFI, or plugin-provided shared libraries.

The proof may use a maintained binding or a minimal C shim where it prevents Lua `longjmp` from crossing unsafe Rust frames, but the selected dependency and source provenance must be pinned and reproducible through the repository devshell. A hand-written binding is not preferred merely to avoid one maintained dependency.

**Alternative considered:** LuaJIT. Rejected for this proof because it establishes Lua 5.1 rather than the intended modern Lua contract and adds JIT/FFI surfaces that the initial ecosystem does not need.

**Alternative considered:** Luaj. Rejected for this proof because it establishes Lua 5.2-era JVM semantics and does not answer the native Lua 5.4 containment and allocator questions.

### D3. Keep the proof in process and state the containment ceiling explicitly

Every Lua state is owned by one proof instance. Script errors, instruction-budget violations, allocator denial, invalid handles, cancellation, duplicate completion, and close races must return normalized outcomes and leave unrelated states usable. The proof does not claim that an engine defect, bridge defect, unprotected Lua panic, native memory corruption, unrecoverable process OOM, or Android process death is instance-contained.

A candidate that reaches Lua's panic/abort path or a Rust panic for an expected adversarial input fails the proof. JNI exports and Lua-callable native callbacks must return failures without unwinding across C or JNI boundaries.

**Alternative considered:** a separate Android plugin process. Rejected for the initial trusted-plugin runtime because it would force IPC contracts for audio handles, capabilities, lifecycle, storage, and scheduling before the in-process substrate is evaluated.

### D4. Compare ownership boundaries with one common bridge state machine

The proof evaluates at least these credible shapes at the minimum depth needed to expose lifecycle and continuation costs:

1. **JVM-owned lifecycle and scheduling:** Kotlin owns instance/generation state, operation tokens, cancellation, and completion admission; native code owns Lua states, protected calls, and coroutine references.
2. **Native-owned coroutine scheduling:** Kotlin submits lifecycle commands and host completions; native code owns the Lua-ready and suspended-coroutine queues while Kotlin remains authoritative for Android lifetime.

Both candidates use the same semantic operations:

```text
create state
load source
start protected callback
return completed | yielded(operation token) | failed
resume operation token with value or failure
cancel operation token
interrupt active Lua execution
close state
```

The selected topology must satisfy every correctness experiment. Among viable candidates, prefer fewer cross-boundary state owners and callbacks, then lower lifecycle complexity, then measured memory/latency. Losing paths, dependencies, JNI exports, and build tasks are removed before completion.

The design is updated with the selected topology and evidence before the change is considered complete. If no candidate satisfies the correctness gates, the recorded decision is that stock in-process Lua 5.4 is not yet viable; production runtime integration does not proceed, and a follow-up evaluates a different bridge, engine, or process topology.

The instrumentation-only comparison uses one opaque JSON outcome contract for both candidates.
Every record contains candidate topology, run identifier, operation kind, normalized outcome,
elapsed nanoseconds, owning state generation, thread identity/count, current/peak/denied Lua
allocation, separately sampled bridge allocation, and any retained handles. Aggregate evidence adds
device/build/version metadata, workload parameters, outcome counts and latency distributions, close
results, and implementation-maintenance observations.

Candidate admission is correctness-first. A candidate is rejected before latency comparison if any
probe observes an unprotected Lua or Rust panic, concurrent entry to one state, cross-state mutation,
more than one terminal continuation, continuation after cancellation/close, a blocking external host
operation while Lua is entered, unbounded pure-Lua execution, unrecoverable protected allocator
denial, nondeterministic close, or retained ownership after teardown. Among candidates passing every
gate, selection compares cross-boundary owners and callbacks, cancellation/close state-machine size,
thread topology, separately attributed memory, measured latency, and maintenance surface, in that
order.

### D5. Model native ownership with opaque generation-safe handles

Kotlin never stores raw `lua_State*`, coroutine pointers, registry indexes, or native allocation addresses. Native state, coroutine, and operation references are represented by opaque identifiers validated against their owning state generation. Closing a state invalidates all descendants atomically from the bridge caller's perspective.

A completion is accepted at most once. Duplicate, cancelled, closed, foreign-state, and stale-generation completions return typed terminal outcomes without resuming Lua or producing another native effect. Close is idempotent and wins races against later completion.

### D6. Protect every JVM-to-Lua execution path

Source loading, entrypoint execution, callback invocation, coroutine resume, error formatting, interruption delivery, and shutdown/close helpers that execute Lua are entered through protected Lua APIs or an equivalent binding guarantee. No expected proof input may reach the Lua panic function.

The Rust/JNI layer uses explicit `Result`-style outcome mapping. It must not use `unwrap`, `expect`, indexing assumptions, or process-aborting assertions on data or lifecycle state influenced by Lua or Kotlin. If unwind-based Rust containment is selected, unwind is caught before JNI return and never crosses Lua C frames; if the existing `panic = "abort"` profile cannot satisfy that requirement, the proof bridge receives an isolated build/profile boundary rather than silently changing unrelated native libraries.

### D7. Enforce only pure-Lua execution with the instruction hook

The proof installs a count hook or equivalent protected interruption check for active Lua execution. An infinite pure-Lua loop and equivalent metamethod/recursive workloads must be interrupted within a measured finite bound, mapped to an instance failure, and followed by deterministic state closure.

The hook is not claimed to preempt a blocking C or JNI function. Proof host functions therefore either complete in a deliberately tiny bounded operation or yield an operation token before external work. A candidate that requires blocking network, filesystem, Android, or model work while the VM is entered is rejected.

### D8. Use a per-state allocator for accounting and denial experiments

Each Lua state uses an allocator that records current and peak native bytes and can deny a request at a configured proof limit. The experiment must show that allocation denial becomes a normalized memory failure under protected execution, the state can be closed, allocator accounting returns to the expected terminal condition, and another state remains usable.

The experiment does not set the future public memory limit. It records empty-state, loaded-state, coroutine, representative allocation, peak, and teardown measurements so a later runtime-policy change can choose limits from evidence.

### D9. Determine thread topology from safety first, then measurements

No Lua state is entered concurrently. The proof compares a stable-affinity topology with serialized worker migration only if the selected libraries and binding permit it. Tests include several independent states, repeated yield/resume, interruption, and close under the candidate topology.

If thread migration is not clearly safe or does not materially improve measured cost, the selected bridge uses stable affinity lanes rather than a dedicated thread per instance or unrestricted worker migration. The proof does not expose locale-mutating or other process-global standard-library operations merely to make migration appear viable.

### D10. Keep evidence reproducible and non-normative where numeric

Automated host tests cover bridge state transitions and malformed/stale handle use. Android instrumentation on the supported hardware records:

- state creation and close latency;
- empty and loaded state memory;
- coroutine suspension/resumption latency;
- instruction-hook interruption latency and overhead;
- allocator-denial behavior;
- one, several, and stress-count independent states;
- cancellation/completion/close races;
- selected thread topology behavior;
- debug and release native loading.

Correctness outcomes are normative. Measured numeric values are evidence, not public plugin compatibility promises. The design records the device, build type, Lua and binding versions, experiment parameters, results, selected topology, rejected alternatives, and recommended starting policy ranges for the next change.

### Evidence recorded before physical-device execution

Host and build evidence on 2026-07-16 establishes the executable harness but does not select a
topology:

- Substrate provenance is stock Lua 5.4.8 through `mlua = 0.12.0`, `mlua-sys = 0.11.0`, and
  `lua-src = 550.1.1`. The nested Cargo lock records crates.io checksums
  `ad72ffa037cf5970c9860674f32f703fda25d86cf217475fe7a79c5f9961bcaa`,
  `92136787b906d4e55cfe96cd6c62e010bb1a56889d0d6cf83eb016dbad07576b`, and
  `75c110c2fa33f34e0de05448e1f3eb2e0631e7a69e2d8ae1586cffc9fc9f9949`,
  respectively.
- `nix develop . -c cargo test --manifest-path rust/subspace-lua-proof/Cargo.toml`
  passes 18 conformance tests for both `jvm_owned` and `native_owned`. Covered gates include
  state/global/module/coroutine/allocation isolation; protected syntax, validation, string,
  non-string, and nested errors; generation-safe ownership; exactly-once completion/cancel/close;
  lifecycle races; pure-Lua interruption; bounded/rejected host calls; phase-specific recoverable
  allocator denial; terminal accounting; concurrent-entry serialization; and stable native-worker
  identity in host execution.
- `nix develop . -c gradle :app:testDebugUnitTest --tests
  'dev.nilp0inter.subspace.lua.*'` passes the opaque-handle, strict outcome-decoding, evidence-field,
  malformed-native-result, and explicit lazy-composition contracts.
- `nix develop . -c gradle :app:assembleDebug :app:assembleRelease` succeeds. Both APKs contain
  `lib/arm64-v8a/libsubspace_lua_proof.so` and no other ABI copy. The release-mode proof library is
  963,296 bytes in both packages; its dynamic table defines only the eight expected
  `LuaProofNative` JNI entrypoints (`create`, `load`, `start`, `resume`, `cancel`, `interrupt`,
  `snapshot`, and `close`).
- `nix develop . -c gradle :app:assembleDebugAndroidTest` succeeds, so the single internal
  `LuaProofInstrumentationTest` entrypoint and release-mode Cargo NDK library package together.
  `nix flake check --no-write-lock-file` also passes.
- `adb devices` returned no attached device. Therefore no Android correctness result, device
  metadata, JNI/thread/memory/latency distribution, debug/release-equivalent device comparison,
  candidate selection, policy range, losing-path removal, or final substrate disposition is
  recorded yet. Host observations are not substituted for those required physical-device gates.

### Final physical-device evidence and positive disposition

The complete proof ran on 2026-07-16 with:

```text
nix develop --command adb shell am instrument -w -r \
  -e class dev.nilp0inter.subspace.lua.LuaProofInstrumentationTest \
  dev.nilp0inter.subspace.test/androidx.test.runner.AndroidJUnitRunner
```

The runner returned `OK (1 test)` and emitted one `LUA_PROOF_EVIDENCE` JSON record. Its
`subspace.lua.proof.v1` payload has SHA-256
`2b99e401f69eee46ad98c58db62011d2f1415ea50dc39ae9a5dd751eedc4f388` and these reproduction
identifiers:

| Field | Recorded value |
|---|---|
| Run ID | `513d241a-7ff0-4b9c-8c31-787d17b113ee` |
| Device | Google Pixel 6a, Android 16, API 36 |
| Device ABI report | `arm64-v8a,armeabi-v7a,armeabi` |
| Packaged ABI | `arm64-v8a` only |
| APK/native build | debuggable APK; Cargo NDK release profile; minimum API 31 |
| Substrate | stock Lua 5.4.8; proof binding 0.1.0; `mlua` 0.12.0 |
| Default proof configuration | 8,388,608-byte state limit; hook interval 100; budget 1,000,000 |
| Java thread count | 6 before and 6 after the complete run |

The record contains 258 operation observations across 24 probes. All 24 probes passed: each of
the 12 workloads passed under both candidates before candidate removal.

| Probe | `jvm_owned` elapsed (µs) | `native_owned` elapsed (µs) |
|---|---:|---:|
| Source-only rejection | 5,116 | 2,644 |
| State/module/coroutine isolation | 2,624 | 3,674 |
| Protected error containment | 4,859 | 3,003 |
| Memory phase snapshots | 4,498 | 6,832 |
| Yield/resume/cancel race | 9,271 | 7,214 |
| Adversarial stale handles | 2,537 | 2,229 |
| Close/resume race | 2,836 | 2,344 |
| Interruption containment | 3,436 | 5,091 |
| Allocator-denial containment | 4,650 | 8,861 |
| Same-state concurrent start | 3,309 | 2,231 |
| Multi-state concurrency and snapshots | 8,077 | 6,487 |
| Hook-interval overhead | 3,385 | 6,692 |

These probe totals include setup and assertions and are evidence, not microbenchmarks. Across the
119 finite per-operation timings for each candidate, `jvm_owned` recorded median/p95/max
214,722/377,278/1,900,920 ns versus 277,710/668,498/6,385,376 ns for `native_owned`. Focused
medians were:

| Operation | `jvm_owned` | `native_owned` |
|---|---:|---:|
| Create (25 samples) | 234,335 ns | 227,376 ns |
| Load (26 samples) | 205,404 ns | 231,689 ns |
| Close (25 samples) | 177,735 ns | 334,148 ns |

The normalized outcome distribution was: 50 `created`, 90 `completed`, 14 `yielded`, 56
`closed`, 24 `snapshot`, 8 `cancelled`, 4 `interrupted`, 2 each of `syntax_failure`,
`runtime_failure`, `memory_failure`, `validation_failure`, `invalid_ownership`, and `stale`.
Duplicate completion echoed the admitted `result-42` without a second Lua effect. Completion/cancel
and completion/close races each admitted one terminal kind; stale, foreign, and post-close handles
did not enter Lua. Thread count returned to baseline and every created state was closed.

Selected `jvm_owned` memory observations were identical to the losing candidate's Lua allocator
observations because both used the same kernel and workload:

| Phase | Current bytes | Peak bytes | Bridge bytes | Denials |
|---|---:|---:|---:|---:|
| Empty state | 20,787 | 20,787 | 0 | 0 |
| Source loaded | 21,943 | 21,943 | 97 | 0 |
| Coroutine yielded | 23,302 | 23,302 | 97 | 0 |
| Coroutine terminal | 23,335 | 23,335 | 97 | 0 |
| Representative 10,000-element allocation | 285,533 | 285,533 | 98 | 0 |
| After contained allocator denial | 524,258 | 524,258 | 93 | 1 |
| Four independent terminal states | 22,848 each | 22,848 each | 42 each | 0 |

Terminal close records reported zero current Lua bytes while preserving peak, denial, and separate
bridge-byte evidence. The 10- and 1,000-instruction hook probes returned the same workload value
`50005000`; observed start latency was 788,615 ns and 373,820 ns respectively. With a 10,000
instruction budget, infinite loops were interrupted in 524,699 ns at interval 10 and 256,144 ns at
interval 1,000. Both interrupted states closed and an unrelated state completed. These isolated
observations do not define a public compatibility limit.

#### Candidate decision

Both candidates satisfy every correctness gate. `jvm_owned` is selected in the priority order from
D4 and D9:

1. **Correctness:** tied; all 12 probes pass for each candidate.
2. **Ownership and cancellation:** `jvm_owned` keeps Android lifecycle/scheduling authoritative and
   enters the native state under one per-state mutex. `native_owned` adds a command channel,
   shutdown protocol, join handle, and worker lifetime without changing semantics.
3. **Threads:** `jvm_owned` requires no dedicated native worker per Lua state. Independent Android
   actor lanes can provide stable affinity; unrestricted worker migration is neither required nor
   justified by this substrate proof.
4. **Memory:** Lua allocator totals are equal. The losing candidate additionally requires native
   worker/channel resources outside those totals, so equal Lua numbers are not evidence of equal
   process cost.
5. **Latency:** the selected candidate has lower aggregate median and p95 operation latency and
   materially lower close latency on this run. Individual probe wins do not override the ownership
   and cancellation priority.
6. **Maintainability:** one direct dispatch path is smaller than retaining semantically identical
   direct and worker-backed engines.

The disposition is positive: stock in-process Lua 5.4 is viable as the internal substrate on the
supported Android boundary. The `native_owned` candidate, worker/channel implementation, topology
switches, and candidate-only tests are removed. The selected kernel, opaque JNI bridge, normalized
outcomes, and conformance coverage remain.

#### Recommended starting policy for the production-runtime change

These values are starting ranges for a later internal policy, not public plugin contracts:

- one serialized Android-owned actor/affinity lane per state; no concurrent entry;
- 4–8 MiB allocator limit per state, starting at 8 MiB until production workload traces justify a
  lower ceiling;
- instruction hook interval 100–1,000, starting at 100 where cancellation responsiveness matters;
- operation-specific instruction budgets, with 1,000,000 as the proof's representative starting
  budget and 10,000 retained only as an interruption-test setting;
- synchronous native host functions only for deliberately bounded fixed-size work; all filesystem,
  network, model, Android, or other external work yields an opaque operation before execution;
- generation-safe close/cancel remains terminal, and late completions echo or reject the recorded
  terminal result without entering Lua.

The production-runtime change must remeasure these ranges with its real module/API and workload
corpus. It must not promote this single device run into a stable public timing or memory guarantee.

#### Verification after losing-path removal

The clean-cutover implementation was rebuilt and rerun on the same device. Run
`9d6c714a-7f3d-40e1-8399-a578660ad643` emitted a selected-only evidence payload with SHA-256
`1ba6d2e082c705ecd98e9f9e34e201fdeebdc68bb57503637bffe24a44d33871`: all 12 `jvm_owned`
probes passed, all 129 operation records were normalized, and Java thread count returned from 6
to 6. The runner returned `OK (1 test)`. Focused Rust conformance passed 17/17 after removal of the
candidate-only native-worker test; focused JVM proof contracts passed. Debug, unsigned release,
and debug-androidTest APKs built successfully. Both application APKs contain the selected
900,984-byte proof library only under `lib/arm64-v8a/`; its only defined dynamic symbols are the
eight retained semantic JNI entrypoints.


### D11. Make follow-up boundaries explicit

A successful substrate proof produces separate follow-up change candidates rather than expanding this change during implementation:

1. production per-instance actor scheduler, lifecycle, task scopes, protected terminal admission, replacement draining, readiness, failure latch, and runtime-registry integration;
2. versioned Lua runtime and Subspace-native API modules;
3. immutable package format, provider identity, installation, update, rollback, and provenance;
4. declarative configuration, credentials, and instance package/data/cache/tmp state;
5. generic durable channel output and host-rendered controls, including the RSM custom menu;
6. repository discovery, App Links, trust warnings, and version selection;
7. external official-channel migrations and plugin-author tooling.

Only boundaries and discovered requirements are recorded here. Those changes are not created or implemented automatically.

Host conformance narrows those candidates as follows without implementing them here:

- **Per-instance actor and registry integration:** one actor owns one generation-safe runtime
  instance; operation and background-task scopes admit one terminal result; replacement first
  closes admission, cancels/drains descendants, and only then publishes the replacement ready.
  Readiness and the failure latch remain host-owned, and runtime-registry integration must not
  expose native handles or make bridge-worker lifetime authoritative for Android service lifetime.
- **Versioned runtime and native modules:** define a versioned source-only Lua contract and
  deliberately bounded Subspace modules over opaque host operations. Decide standard libraries,
  instruction and memory policy, traceback shape, cancellation values, and module-cache semantics
  from the final substrate disposition; do not expose proof hook intervals, allocator thresholds,
  JSON/JNI shapes, bridge topology, or instrumentation functions as compatibility promises.
- **Packages and instance state:** separately specify immutable package identity and provenance,
  install/update/rollback transactions, declarative configuration and credential references, and
  isolated package/data/cache/tmp state. No proof source, native registry entry, or operation token
  is a package identifier or persisted state.
- **Output, controls, discovery, and migrations:** separately specify durable generic output and
  host-rendered controls (including RSM controls), repository/App-Link discovery and trust UX,
  version selection, official-channel migrations, and author tooling. None may depend on the proof
  harness, register the proof as a provider, or bypass runtime capability/lifecycle contracts.

Failed correctness gates remain mandatory inputs to the relevant follow-up proposal. A follow-up
may change the engine, binding, or process topology, but it must not reinterpret a failed gate as an
accepted production limitation without an explicit specification change. This change creates no
follow-up directories, provider registrations, APIs, persistence, package files, migrations, or UI.

## Risks / Trade-offs

- **[Risk] Lua `longjmp` or Rust panic crosses an unsafe native frame.** → Use a binding/C-shim strategy with documented protected-call boundaries, adversarial tests, and explicit JNI error mapping; reject any candidate that reaches process abort for expected input.
- **[Risk] The instruction hook appears to provide hard interruption but a host function blocks outside Lua execution.** → Expose only tiny bounded proof functions and yielding operation-token functions; test the documented boundary and reject blocking bridge designs.
- **[Risk] Per-state allocator denial triggers an unrecoverable path.** → Exercise denial at multiple allocation phases under protected execution, verify unrelated-state survival, and classify unrecoverable process OOM outside the promised containment ceiling.
- **[Risk] A bridge candidate is favored by microbenchmark while owning a more complex cancellation state machine.** → Correctness and single ownership outrank throughput; compare identical semantic probes and record rejected complexity.
- **[Risk] Stable thread affinity consumes excessive threads.** → Evaluate affinity lanes before dedicated per-instance threads; record latency and memory under several states.
- **[Risk] Worker migration passes tests but depends on non-thread-safe standard libraries.** → Use a narrow proof library set and prefer stable affinity unless migration safety is established, not merely observed once.
- **[Risk] Experimental code becomes a second permanent runtime.** → Retain only the selected kernel and conformance tests; remove losing candidates, unused dependencies, build paths, feature flags, and user-visible hooks.
- **[Trade-off] This change does not deliver installable plugins.** → Accepted: it retires the highest native-runtime risks before public lifecycle, API, package, and migration contracts depend on them.
- **[Trade-off] A negative proof can complete without a production-ready bridge.** → Accepted only when the failed gates, evidence, and required alternative follow-up are recorded; no production runtime may claim the failed substrate.

## Migration Plan

1. Add the pinned Lua source/binding and isolated native bridge build without wiring it into provider registration or application startup.
2. Build the common opaque-handle and protected-outcome proof interface.
3. Implement the minimum equivalent bridge candidates behind instrumentation-only composition.
4. Run correctness experiments first; discard any candidate that panics, aborts, violates exactly-once continuation, leaks ownership, or cannot close deterministically.
5. Run target-device thread, memory, latency, hook, allocator, and race measurements for viable candidates.
6. Select one topology, update this design with evidence and disposition, and remove losing paths.
7. Retain the selected internal kernel and automated conformance coverage without enabling a production plugin or channel.
8. Validate the Nix/Gradle/Cargo Android build and existing application build behavior, then record the follow-up change boundaries.

Rollback is removal of the internal proof bridge, pinned Lua dependency, build wiring, and proof tests. No persisted data, channel definitions, provider registration, UI state, permissions, or user-visible behavior is migrated.

## Open Questions

The following are experiment outputs rather than decisions to guess in advance:

- Which JVM/native ownership topology satisfies all correctness gates with the smallest state and cancellation surface?
- Which maintained Lua binding or C-shim boundary safely contains Lua errors without unsafe `longjmp` or Rust unwind behavior?
- Does stable affinity, affinity-lane scheduling, or serialized worker migration provide the safest acceptable Android behavior?
- What are the measured per-state memory, startup/close, hook-overhead, continuation, and multi-instance costs on the target device?
- Does allocator denial remain recoverable and state-local across every exercised allocation phase?
- Does stock in-process Lua 5.4 pass the complete proof, or must a follow-up evaluate another engine, bridge, or process topology?
