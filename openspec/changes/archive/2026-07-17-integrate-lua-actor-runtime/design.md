## Context

The archived `prove-embedded-lua-runtime` change selected a production-internal Lua substrate on the supported Android boundary. Its design and spec established that stock Lua 5.4.8, built source-only through `mlua` 0.12.0 and entered exclusively from the JVM side under a per-state serialization lock (`LuaBridgeTopology.JvmOwned`), satisfies every correctness gate: independent states, protected execution, generation-safe opaque handles, exactly-once coroutine continuation, cancellation/close races, instruction-hook interruption of pure-Lua loops, per-state allocator accounting and denial, and deterministic teardown. The retained `LuaProofBridge` interface (`create`/`load`/`start`/`resume`/`cancel`/`interrupt`/`snapshot`/`close`), the normalized `LuaProofOutcome` sealed type, the opaque `LuaStateHandle`/`LuaCoroutineHandle`/`LuaOperationHandle` identifiers, and the eight `LuaProofNative` JNI entrypoints are the kernel that this change promotes.

The existing Kotlin channel runtime stack already provides every host-owned boundary the actor must live behind:

- `ChannelRuntimeRegistry` owns the catalogue-to-runtime map, per-instance generation identities, committed input-target leases, retired entries, replacement reconciliation, and service shutdown. It assigns a fresh `RuntimeGeneration` per instance, retires the predecessor before constructing a replacement, drains committed leases, and closes each generation exactly once.
- `RuntimeInvocationBoundary` opens one `RuntimeGenerationInvocationGate` per live generation. The gate provides bounded FIFO admission, serialized callback execution, cancellation, timeout, committed-target terminal phases, `stopAdmission`, `invalidate`, `commitIfLive`, and idempotent terminal `close`. Callbacks execute on a host worker dispatcher, never on the Android main thread, never under a registry lock.
- `ChannelCapabilityHost` exposes semantic capabilities (`Transcription`, `Synthesis`, `AudioOperation`, `TextOutput`, `OpenAiCompletion`, `AsynchronousConversation`, etc.) through `RevocableChannelCapabilityScope` bound to `CapabilityScopeIdentity(channelInstanceId, runtimeGeneration)`. Leases are revoked on generation retirement; use after revocation returns `Closed`/`Cancelled` without effect.
- `ChannelRuntime` is the provider-neutral runtime contract: `prepareInput`, `handleSos`, `refreshReadiness`, `idempotent close`. It returns `ChannelInputAcceptance` and exposes a `StateFlow<ChannelRuntimeSnapshot>` for readiness and execution-status projection.
- The `channel-framework`, `channel-runtime-registry`, `channel-runtime-invocation`, and `channel-host-capabilities` main specs already require language-neutral durable operations, generation-safe effects, drain-before-close replacement, host-owned capability mediation, and no script-engine coupling at the invocation boundary. Those specs were written ahead of this change to keep the runtime language-neutral.

`PLUGIN_SYSTEM_VISION.md` describes the eventual product: independently configured Lua programs running inside the foreground-service lifetime, owning timers, polls, network requests, retries, backend cursors, plugin-owned durable state, and asynchronous coroutines, while the host owns Android resources, lifecycle, failure containment, and durable channel effects. That vision is aspirational and non-normative. This change does not implement the vision; it turns the proven kernel into the internal actor runtime the vision depends on, without defining any public Lua module, package, configuration, discovery, output, or migration contract.

The proof's recommended starting policy (one serialized actor/affinity lane per state, 4–8 MiB allocator limit, hook interval 100–1,000, operation-specific instruction budgets, synchronous bounded native host functions only for tiny fixed work, all external work yields an opaque operation token before execution) is evidence for internal policy, not a public compatibility promise. This change may remeasure and adjust those ranges against real internal workloads.

## Goals / Non-Goals

**Goals:**

- Promote the selected `jvm_owned` Lua kernel from a proof-only bridge into one production-internal actor runtime per channel generation, retaining its conformance coverage and removing proof/runtime duplicate paths rather than creating a second bridge.
- Add one bounded, serialized Lua actor for each runtime generation, with an event mailbox, cooperative ready-coroutine scheduling, suspended-operation ownership, background-task scopes, and exactly-once terminal admission.
- Keep Lua state entry single-threaded while allowing multiple logical coroutines to suspend independently and resume through generation-safe opaque operation completions.
- Add explicit actor startup, readiness, failure-latch, draining, replacement, shutdown, and deterministic-close semantics.
- Separate plugin-owned policy from runtime-owned mechanisms and host-native capabilities: Lua may eventually own protocol, retry, polling, and state-machine policy, while any blocking runtime or host operation yields before execution and remains host-owned, lifecycle-bound, and generation-authorized.
- Strengthen runtime replacement so a successor is not published ready until its predecessor has stopped admission, completed any committed input terminal callback, cancelled or drained descendants, revoked effects, and closed.
- Preserve durable host records across runtime or process shutdown without preserving Lua states, suspended coroutines, volatile tasks, or prior-generation authorization.
- Keep internal memory, instruction, mailbox, deadline, and close policies configurable and measurable without making proof constants public compatibility promises.
- Provide test-only synthetic operations that exercise the actor lifecycle, mailbox, scheduling, ownership, and failure classification without leaking a public API surface.

**Non-Goals:**

- No public Lua ABI, runtime/API module, package format, installation, discovery, update, rollback, provenance, signing, or credential contract.
- No declarative configuration schema, durable message/output/control API, RSM custom-menu model, or host-rendered control surface for Lua plugins.
- No external package execution, plugin-supplied bytecode, JNI, FFI, C modules, or shared-object loading.
- No registration of a Lua channel provider, no migration of a Kotlin channel, and no current channel, PTT, runtime-registry, capability, audio, persistence, UI, or foreground-service behavior change visible to users.
- No execution guarantee after the service or Android process stops.
- No claim of containment for native defects, unprotected panics, unrecoverable process OOM, or Android process death.
- No permanent second native bridge, test-only user surface, feature flag, or dormant alternative implementation.
- No plugin-driven foreground-service retention.

## Decisions

### D1. Promote the proof kernel rather than duplicate it

The production actor runtime reuses the selected `jvm_owned` kernel: the pinned Lua 5.4.8 source, the `mlua` 0.12.0 binding, the eight `LuaProofNative` JNI entrypoints (`create`/`load`/`start`/`resume`/`cancel`/`interrupt`/`snapshot`/`close`), the opaque handle model, the normalized outcome sealed type, the per-state serialization lock, the instruction hook, and the per-state allocator. The proof-only `LuaProofBridge` interface and its `LuaProof*` Kotlin types are promoted to production-internal names under the existing `dev.nilp0inter.subspace.lua` package (or a sibling internal package) and the proof instrumentation harness is removed. No second bridge, no feature flag, no topology switch, and no retained `native_owned` candidate remain.

The kernel's eight JNI entrypoints and its JSON outcome contract are internal implementation contracts. They are not a public Lua ABI and may be reshaped by this change to serve the actor runtime. The conformance suite (Rust host tests and JVM bridge contracts) is retained and extended to cover actor lifecycle, mailbox, scheduling, ownership, and replacement; it does not become a public compatibility promise.

**Alternative considered:** build a new production bridge alongside the proof and keep the proof as instrumentation. Rejected because it creates a permanent second runtime, duplicates the JNI ownership and cancellation surface, and makes the selected-topology cutover from the proof change meaningless.

**Alternative considered:** leave the kernel proof-only and build the actor runtime entirely in Kotlin on top of the existing `ChannelRuntime` contract without a Lua entry. Rejected because the goal is a Lua actor runtime; deferring the Lua entry leaves the proven substrate unused and forces a later, larger change to introduce both the actor and the Lua entry simultaneously.

### D2. Actor scope hierarchy: one actor owns one generation

One Lua actor owns exactly one `RuntimeGeneration` for one channel instance. The actor is the single Lua entry for that generation: it owns one Lua state, one event mailbox, one ready-coroutine scheduler, a set of suspended operation tokens, and a background-task scope. All are bounded by the generation's lifetime and the generation's invocation gate.

The hierarchy is:

```text
Channel instance (stable ID)
  └─ Runtime generation G (host-assigned, monotonic per instance)
       └─ Lua actor (one Lua state, one mailbox, one scheduler)
            ├─ Ready coroutine queue (cooperative, at most one executing)
            ├─ Suspended operation tokens (generation-safe, exactly-once resume)
            └─ Background task scope (durable work, generation-bound)
```

The actor does not outlive its generation. Replacement installs generation H with a fresh actor; G's actor is drained and closed. The actor is not a process-global singleton, not shared across instances, and not transferable between generations.

**Alternative considered:** one actor per channel instance with internal generation switching. Rejected because the existing registry already assigns distinct generations and gates per replacement; tying the Lua state to an instance rather than a generation would require internal generation switching inside Lua state, conflating host and actor ownership and weakening the drain-before-ready invariant.

**Alternative considered:** one actor per background task or per coroutine. Rejected because it fragments Lua state, defeats the single-entry serialization invariant proven by the kernel, and removes the natural mailbox/scheduler boundary.

### D3. Cooperative ready/suspended scheduling with single Lua entry

The actor uses cooperative scheduling. At most one Lua coroutine executes at a time. A ready queue holds coroutines eligible to run; a suspended set holds coroutines waiting for host completion. The scheduler runs in the generation-owned child scope on the bounded runtime worker dispatcher. Every dequeue validates the generation's live state, and every Lua entry uses the kernel's per-state serialization lock; a continuation does not occupy the host invocation FIFO merely because it belongs to the same generation.

A coroutine becomes ready when: the actor entry starts it, a host operation completion resumes it, a timer fires, or an external event is delivered to the mailbox. A coroutine becomes suspended when it calls the internal `yield_operation` (or equivalent) mechanism and receives an opaque operation token. The scheduler does not preempt; the instruction hook interrupts only pure-Lua infinite loops, not suspended coroutines waiting on host operations.

Multiple logical coroutines may suspend independently. Each suspended operation token is generation-safe: a resume, cancel, or close for a foreign or stale token returns a typed outcome without entering Lua. The scheduler resumes at most one coroutine per ready dequeue and at most one terminal completion per operation token.

The host remains authoritative for scheduling policy. The actor's scheduler is a mechanism owned by the runtime; it does not own Android lifecycle, does not create threads, and does not execute outside the generation's invocation gate.

```text
Mailbox event → actor admits → ready queue → scheduler picks one
    → Lua entry (serialized) → coroutine runs
        → completes  → terminal, next ready
        → yields op   → suspended set, release entry
        → fails       → failure latch, terminal
Host op completes → resume token → ready queue (if generation live)
```

**Alternative considered:** preemptive coroutine scheduling with time slices. Rejected because Lua coroutines are cooperative by design, the kernel proved cooperative suspension/resumption, and preemption would require VM-level hooks that the instruction hook does not provide for suspended coroutines.

**Alternative considered:** one coroutine at a time, no ready queue. Rejected because a ready queue is needed to interleave timer, event, and completion callbacks deterministically; without it, later events would starve behind a single long-running coroutine that frequently yields and resumes.

### D4. Host callback versus actor continuation semantics

There are two distinct execution paths into the actor:

1. **Host callback** (inbound): the host invokes the actor through the generation's invocation gate to deliver an event—a lifecycle callback, an input event, a readiness refresh, an SOS action, or a durable-run callback. The callback is serialized and bounded while it admits the event and runs that event's current Lua slice. If the slice yields, the callback returns a normalized admitted or yielded result and releases its gate slot; the resulting suspended coroutine remains actor-owned.

2. **Actor continuation** (internal): a coroutine suspended on an operation token becomes ready when the host completes, cancels, or fails that operation. The actor scheduler resumes it under the generation's live-state gate and per-state serialization lock, but not as a new host invocation-queue callback.

The distinction matters for ownership and liveness: a host callback carries an invocation identity and deadline only until its current slice completes or yields; a continuation carries the operation token's generation, task, and operation authorization. Both require the generation to remain live and both are suppressed after retirement or close. A yielded event therefore does not hold the serialized host-callback slot while external work is pending, and later host events may be admitted while its coroutine remains suspended.

The actor does not expose raw Kotlin coroutine jobs, Android callbacks, or SDK futures to Lua. Host callbacks and actor continuations both cross the opaque bridge boundary as normalized outcomes and opaque handles.

**Alternative considered:** model all continuations as new host callbacks. Rejected because it would make every operation completion a new gate invocation, serializing behind unrelated queued work and losing the actor's internal scheduling authority; it would also conflate host-driven and actor-driven work, weakening ownership tracing.

**Alternative considered:** let Lua coroutines call host capabilities directly and synchronously. Rejected because any blocking host operation must yield before execution (D5); a synchronous call would block the Lua entry thread and violate the kernel's bounded-call invariant.

### D5. Mediated runtime mechanisms versus host-native capabilities

The actor exposes only mediated runtime mechanisms to Lua: coroutine creation, operation yield/resume, timers, event mailbox delivery, and internal state access. These are runtime-owned mechanisms implemented inside the actor and the kernel. They are deliberately limited to what cooperative scheduling requires.

Host-native capabilities—audio capture/playback, text output, transcription, synthesis, OpenAI completion, model discovery, delayed playback, journal, keyboard transport, phone/RSM/Android Auto projection—remain host-owned and are accessed only through the existing `ChannelCapabilityHost` and `RevocableChannelCapabilityScope`. The actor does not reimplement, wrap, or bypass them. When Lua needs a host effect, it yields an operation token that the host resolves through the capability boundary; the result is delivered back as a continuation.

Any blocking runtime or host operation yields before execution. Synchronous native host functions are permitted only for deliberately tiny bounded fixed-size work (as established by the proof). Filesystem, network, model, Android, or other external work always yields an opaque operation token before execution; the Lua entry thread is never blocked waiting for external completion.

This separation keeps the host authoritative for Android resources, transport implementation and reconnect policy, credentials, capability admission, and durable effects. Lua may eventually own protocol-level retry, polling, and state-machine policy inside the actor; a runtime or capability operation remains the revocable mechanism that executes each attempt. The actor's mechanisms are internal scheduling primitives, not a public API.

```text
Lua coroutine
  ├─ yield_operation(token) → suspended → host resolves via capability
  │      → capability host (generation-scoped, revocable lease)
  │      → completion/failure → actor continuation
  └─ internal mechanism (timer, mailbox, coroutine) → no host effect
```

**Alternative considered:** expose host capabilities directly as Lua callable functions that block. Rejected because it violates the kernel's bounded-call invariant and the proof's documented boundary; blocking host work while the VM is entered was explicitly rejected as a candidate defect.

**Alternative considered:** give the actor its own capability implementation layer that duplicates the host's semantic capabilities. Rejected because it duplicates the host's ownership, revocation, and generation-safety logic and creates a second path for every effect; the existing `RevocableChannelCapabilityScope` already enforces descriptor-declared eligibility, instance scoping, revocation, and generation-safe late-effect suppression.

### D6. Identity and envelope model

Every actor-owned reference is an opaque, generation-safe identifier. The kernel's `LuaStateHandle(stateId, generation)`, `LuaCoroutineHandle(stateHandle, coroutineId)`, and `LuaOperationHandle(stateHandle, coroutineId, operationId)` model is retained and extended to actor scope. No native pointer, Lua registry index, or raw Kotlin object crosses the Lua boundary.

Actor event, task, and operation identities are language-neutral host-domain values. Every asynchronous actor completion carries the channel instance ID, runtime generation, task or event identity, and operation identity. A durable channel operation additionally carries its durable run identity; ordinary timers, startup work, and background operations do not receive fabricated durable-run identities. These values layer actor ownership over the existing `CapabilityScopeIdentity` and registry generation contracts rather than creating a competing generation space.

Mailbox events are delivered as opaque envelopes carrying their event kind, owning generation, and any host-domain payload. The actor decodes envelopes internally; the host does not expose Kotlin classes, Android callbacks, Compose state, SDK types, or transport objects in the envelope.

**Alternative considered:** reuse raw kernel handles as the public actor identity. Rejected because the kernel handles are internal to the bridge and carry no durable-run or mailbox-event semantics; the actor needs its own opaque identities layered on the same generation-safe principle.

**Alternative considered:** expose Kotlin coroutine `Job` or `Deferred` as operation handles. Rejected because the existing specs require language-neutral host-domain values; exposing Kotlin jobs would couple the actor to a specific host implementation and violate the durable framework contracts.

### D7. Lifecycle and failure classification

The actor lifecycle is bounded by the generation's invocation gate and the registry's entry lifecycle. States:

```text
constructing → staged → starting → live (ready) → retiring → drained → closed
                               ↘ failed (failure latch)
```

- **Constructing:** the provider constructs the runtime and creates an independent state; no runtime is ready.
- **Staged:** source is loaded and its entrypoint is validated, but a replacement has not yet received authorization to run startup or effects.
- **Starting:** the predecessor is closed and the host has authorized bounded protected startup.
- **Live (ready):** startup reported ready through the host-owned latch; the actor accepts events and generation-scoped background work.
- **Failed (failure latch):** startup, lifecycle-critical, instruction, memory, ownership-integrity, or another generation-fatal outcome sets the terminal host-owned latch. Recovery requires a fresh generation.
- **Retiring:** replacement or removal stopped admission; committed leases are draining.
- **Drained:** committed leases released and descendants were cancelled or joined.
- **Closed:** terminal close ran exactly once; late effects are suppressed.

Protected errors from an ordinary event handler or background task terminate and report that event or task without latching an otherwise sound actor. A failed yielded operation resumes its owning coroutine with a normalized failure. Syntax or validation failure during construction/startup, instruction interruption, allocator denial, invalid ownership indicating a runtime invariant failure, or another lifecycle-critical failure latches the generation and prevents further Lua entry. Native engine or bridge defects, unprotected panic, native memory corruption, unrecoverable process OOM, and Android process death remain outside the claimed containment ceiling.

The host owns the failure latch and decides when to retire the failed generation and construct a successor. The actor never resets its Lua state silently within the same generation.

**Alternative considered:** let the actor internally reset its Lua state on failure and remain on the same generation. Rejected because it breaks generation ownership, hides failure from the registry, and can repeat effects without fresh authorization.

**Alternative considered:** latch every protected Lua or host-operation error. Rejected because ordinary event, task, and operation failures are expected programmable outcomes; latching them would make one backend error destroy an otherwise healthy actor.

**Alternative considered:** promise containment for native defects or unprotected panic. Rejected because the substrate proof explicitly places those failures outside the in-process containment ceiling.

### D8. Drain-before-ready replacement
When reconciliation replaces generation G with generation H for one channel instance, H may create its state and load or statically validate source while G drains, but H does not execute plugin startup, acquire effect authorization, or publish readiness until G has:

1. Stopped admission (`gate.stopAdmission`).
2. Completed any committed input terminal callback (the gate's committed-target phases: `INPUT_RELEASED`, `INPUT_PLAYBACK_COMPLETED`, `INPUT_CANCELLED`, `INPUT_FAILED`).
3. Cancelled or drained descendant work (background tasks and suspended operations).
4. Revoked capability leases and outstanding generation-bound effects.
5. Closed the actor and generation gate exactly once.

The registry retains H as a staged pending generation while G is live. Once G's `closed` deferred completes, the host issues H's generation authorization, executes H's bounded protected startup, and publishes H ready only if startup succeeds. No plugin effect from H overlaps a live G generation. Shutdown remains bounded by the existing shutdown policy; ordinary replacement does not treat a timeout as permission to expose two live generations.

```text
Reconcile definition change for instance I
  → stop G admission
  → create H state and load/validate source only (staged, unauthorized)
  → finish G committed terminal callback
  → cancel/drain G descendants and revoke effects
  → close G
  → authorize and run H startup
  → publish H ready
```

**Alternative considered:** publish H ready while G closes in the background. Rejected because two plugin generations could poll, write state, or publish the same external event concurrently.

**Alternative considered:** execute H startup while G drains but delay only readiness publication. Rejected because startup may create background work or external effects even while readiness is hidden.

**Alternative considered:** defer all H state creation and source validation until G closes. Rejected because effect-free staging can reduce replacement latency without weakening the single-live-generation invariant.


### D9. Service shutdown

Service shutdown follows the existing `ChannelRuntimeRegistry.shutdownAndAwait` ordering: stop admission for all generations, cancel the active PTT session, wait within the configured bound for committed leases to release, then close each generation exactly once. The actor must participate by:

- Stopping mailbox admission immediately when its gate stops admission.
- Letting any executing host callback reach its terminal phase or timeout within the gate's close bound.
- Cancelling suspended operation tokens and joining background tasks within the gate's `closeTimeoutMillis`.
- Closing its Lua state idempotently through the kernel's `close` operation, invalidating all descendant handles.
- Producing no late effects after close; late completions are `Stale`/`Closed`.

Durable host records (persisted runs, messages, terminal-state records) survive shutdown. Lua states, suspended coroutines, volatile tasks, and prior-generation authorization do not. On restart, the registry assigns fresh generations; recovery of persisted durable runs is an explicit host operation under the fresh generation and does not import prior volatile Lua context. This matches the existing `Runtime restart creates a fresh generation boundary` spec requirement.

The actor does not own foreground-service retention. Shutdown is host-initiated and host-bounded; the actor does not delay it beyond the configured bound. Plugin-driven foreground retention is a non-goal.

**Alternative considered:** let the actor veto shutdown by keeping operations alive. Rejected because shutdown must be host-bounded and idempotent; an actor that could veto shutdown would break the reliability boundary.

**Alternative considered:** preserve suspended coroutines across shutdown and resume them on restart. Rejected because the kernel's generation-safe handles are process-local and non-portable; resuming a prior-process coroutine would require persisting Lua state, which is explicitly out of scope and unsupported by the kernel.

### D10. Configurable resource policy

Internal resource policy is configurable and measurable but not a public compatibility promise. The actor reads its policy from a host-owned internal configuration supplied at construction, not from plugin configuration. Policy covers:

- Per-state Lua allocator limit (starting range 4–8 MiB, per the proof's recommendation).
- Instruction hook interval (starting range 100–1,000).
- Operation-specific instruction budgets (1,000,000 as a representative starting budget; 10,000 retained only as an interruption-test setting).
- Mailbox capacity (bounded queue; reject with `Busy` when full, matching the gate's bounded queue).
- Callback deadlines and close timeout (reusing `RuntimeInvocationPolicy` fields or internal actor-specific equivalents).
- Background-task scope bounds (maximum concurrent tasks, per-task deadline).

Policy values are evidence-derived and may be remeasured against real internal workloads. They are not persisted as plugin-facing limits and do not appear in the public configuration schema. The actor reports current and peak memory, denial counts, instruction counts, and latency through internal diagnostics that feed host-owned observability, not through a public API.

The proof's observed values (empty state ~20 KiB, loaded state ~22 KiB, 10,000-element allocation ~286 KiB, denial at 8 MiB limit, interruption in ~0.5 ms at interval 10) are starting evidence, not normative limits.

**Alternative considered:** hardcode the proof's observed values as fixed limits. Rejected because the proof explicitly states that its single-device run must not be promoted into a stable public or private limit; production workloads differ and policy must be adjustable.

**Alternative considered:** expose policy as plugin configuration. Rejected because resource policy is a host reliability mechanism, not plugin-facing; the vision explicitly states that CPU limits, memory limits, and callback deadlines are host runtime-policy decisions, not plugin configuration.

### D11. Test-only synthetic operations without public API leakage

The actor runtime is exercised by test-only synthetic operations: internal test harnesses that create an actor, load synthetic Lua source, enqueue mailbox events, yield and resume operations, trigger the failure latch, race cancellation against completion, and validate drain-before-ready replacement. These operations are internal test utilities; they are not exposed as a public API, not registered as a provider, and not wired into application startup.

Synthetic operations cover:

- Actor construct → ready → failed → close transitions.
- Mailbox admission, bounded rejection, and ordering.
- Coroutine yield/resume/cancel races under the generation gate.
- Failure latch set and projection as unavailable.
- Replacement: G drain → H ready publication ordering.
- Shutdown: stop admission → drain → close within bound.
- Generation-safe late-effect suppression.
- Instruction-hook interruption and allocator-denial containment inside the actor.

The existing `LuaProofInstrumentationTest` entrypoint is replaced or repurposed into internal actor-runtime instrumentation; no proof-only user surface remains. The conformance suite is retained and extended.

**Alternative considered:** expose a test-only public API for external validation. Rejected because it creates a public surface that must be maintained as a compatibility promise; the actor runtime is internal and its validation is internal.

**Alternative considered:** rely only on unit tests without device instrumentation. Rejected because the kernel's correctness gates require on-device evidence (JNI, thread topology, allocator behavior, interruption latency); the proof established this and the actor runtime inherits the requirement.

## Risks / Trade-offs

- **[Risk] A late operation completion on a retired generation reaches the actor after H is published ready.** → Generation-safe handles and the gate's `commitIfLive`/`invalidate` suppress late effects; the drain-before-ready sequencing (D8) ensures G is closed before H is ready, minimizing the window. The gate's `Closed`/`Stale` outcomes prevent Lua entry.
- **[Risk] The instruction hook fails to interrupt a blocking host function called from Lua.** → Only tiny bounded synchronous host functions are permitted; all external work yields an operation token before execution (D5). The hook is not claimed to preempt native calls; this boundary is tested.
- **[Risk] An actor failure latch is not set for a partial Lua state corruption that does not raise a normalized error.** → The kernel's protected execution wraps every Lua entry; any unprotected panic is a bridge defect that fails the proof's containment ceiling and is not instance-contained. The actor treats unrecognized native defects as generation-fatal and relies on the host to retire and restart.
- **[Risk] Drain-before-ready increases replacement latency.** → Accepted: correctness outranks latency. H's construction overlaps G's drain, so only readiness publication waits. The shutdown bound prevents indefinite drain.
- **[Risk] Multiple suspended coroutines accumulate memory before drain.** → Per-state allocator accounting and the background-task scope bounds limit accumulation; drain cancels and closes all descendants. Policy is configurable (D10).
- **[Risk] Promoting the proof kernel breaks existing proof instrumentation tests.** → The proof's `LuaProof*` types are renamed/refactored internally; conformance coverage is retained and extended. This is a clean cutover, not a parallel path.
- **[Risk] The actor's internal scheduler deadlocks on a coroutine that never yields and never completes within the deadline.** → The instruction hook interrupts pure-Lua infinite loops; the gate's callback timeout cancels the executing callback; the close timeout bounds terminal close. Deadlock is bounded by policy.
- **[Trade-off] This change does not deliver installable plugins or a public Lua API.** → Accepted: it retires the highest scheduling and replacement risks before public lifecycle, API, package, and migration contracts depend on them.
- **[Trade-off] Existing Kotlin providers are unchanged but a Lua provider is not yet registered.** → Accepted: ordinary startup creates no Lua state until a later package/API change supplies a Lua-backed provider. Kotlin compatibility is explicit and unaffected.

## Migration Plan

1. Rename and promote the `jvm_owned` kernel types (`LuaProofBridge` → internal actor bridge, `LuaProof*` → internal actor types) under the existing internal package; retain the eight JNI entrypoints, opaque handles, normalized outcomes, per-state serialization, instruction hook, and allocator.
2. Remove the proof-only instrumentation harness, candidate-only tests, and any proof-only user surface; retain and extend the conformance suite.
3. Implement the actor runtime: event mailbox, cooperative ready/suspended scheduler, background-task scope, operation-token ownership, failure latch, and resource policy, all gated by the existing `RuntimeGenerationInvocationGate`.
4. Implement the actor-to-host capability mediation: Lua yields an operation token; the host resolves it through `RevocableChannelCapabilityScope`; the completion resumes the actor continuation.
5. Implement drain-before-ready replacement: strengthen `ChannelRuntimeRegistry` replacement sequencing so the successor's readiness publication waits for the predecessor's close (ordinary replacement) or the shutdown bound (shutdown).
6. Implement actor lifecycle: construct → pending → live → failed/retiring → drained → closed, mapping kernel outcomes to `ChannelPreparationReason` and `ChannelExecutionStatus`.
7. Add test-only synthetic operations covering actor lifecycle, mailbox, scheduling, ownership, failure classification, replacement, shutdown, and late-effect suppression.
8. Validate Kotlin compatibility: existing Kotlin providers continue to register, construct, and run without a Lua provider registered; ordinary startup creates no Lua state.

Rollback is removal of the promoted actor runtime, renamed kernel types, extended conformance tests, and registry sequencing changes. No persisted data, channel definitions, provider registration, UI state, permissions, or user-visible behavior is migrated. The kernel's JNI library and conformance coverage may be retained or removed depending on whether the proof change is considered independently revertible; this change does not depend on keeping the proof-only harness.

## Open Questions

None. All architectural decisions are resolved above. Implementation naming (exact Kotlin type names, package layout for promoted types, and internal config field names) is a non-contractual implementation detail left to the implementation change.