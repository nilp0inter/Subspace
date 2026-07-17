## Why

The embedded Lua substrate proof established that stock Lua 5.4 can execute safely on the supported Android boundary, but the retained bridge is instrumentation-only and cannot yet host production channel instances, background coroutines, or lifecycle-bound asynchronous work. The next dependency-ordered step is to turn that proven kernel into one host-owned actor per runtime generation before public Lua APIs, packages, or channel migrations depend on unresolved scheduling and replacement semantics.

## What Changes

- Promote the selected JVM-owned Lua substrate from a proof-only bridge into one production-internal kernel; retain its conformance coverage and remove proof/runtime duplicate paths rather than creating a second bridge.
- Add one bounded, serialized Lua actor for each channel runtime generation, with an event mailbox, cooperative ready-coroutine scheduling, suspended-operation ownership, background-task scopes, and exactly-once terminal admission.
- Keep Lua state entry single-threaded while allowing multiple logical coroutines to suspend independently and resume through generation-safe opaque operation completions.
- Add explicit actor startup, readiness, failure-latch, draining, replacement, shutdown, and deterministic-close semantics.
- Distinguish plugin-owned policy from runtime-owned mechanisms and host-native capabilities: Lua may eventually own protocol, retry, polling, and state-machine policy, while any blocking runtime or host operation yields before execution and remains host-owned, lifecycle-bound, and generation-authorized.
- Strengthen runtime replacement so a successor is not published ready until its predecessor has stopped admission, completed any committed input terminal callback, cancelled or drained descendants, revoked effects, and closed.
- Preserve durable host records across runtime or process shutdown without preserving Lua states, suspended coroutines, volatile tasks, or prior-generation authorization.
- Keep internal memory, instruction, mailbox, deadline, and close policies configurable and measurable without making proof constants public compatibility promises.
- Do not define the public Lua runtime/API modules, package format, installation or discovery flows, configuration schema, durable output/control API, official channel migrations, or plugin-driven foreground-service retention in this change.

## Capabilities

### New Capabilities

- `lua-actor-runtime`: Production-internal per-generation Lua actor scheduling, coroutine and operation ownership, lifecycle, readiness, failure containment, replacement, resource policy, and deterministic teardown.

### Modified Capabilities

- `channel-framework`: Permit independently scheduled runtime background work while preserving host ownership of lifecycle, generation authorization, Android-native resources, and durable channel effects.
- `channel-runtime-invocation`: Distinguish bounded host-to-runtime callback admission from cooperative actor coroutine scheduling while retaining serialization, cancellation, timeout, and late-effect guarantees.
- `channel-runtime-registry`: Add actor-aware readiness, failure-latch, predecessor-drain, successor-publication, shutdown, and fresh-generation recovery requirements.
- `channel-host-capabilities`: Separate general Lua runtime modules from Subspace host-native capabilities while requiring all blocking operations and host effects to remain opaque, yielding, revocable, and generation-scoped.

## Impact

- Native runtime: the selected `rust/subspace-lua-proof` kernel, JNI ownership tables, protected calls, allocator accounting, instruction interruption, continuation handling, and conformance suite are promoted to a production-internal runtime boundary.
- Android runtime: new internal actor, scheduler, task/operation identity, lifecycle, policy, state-projection, and bridge-adapter types under the existing service/runtime composition.
- Channel integration: `ChannelRuntimeRegistry`, `RuntimeInvocationBoundary`, runtime-generation gates, capability scopes, replacement reconciliation, readiness projection, and shutdown sequencing.
- Existing Kotlin providers remain behaviorally supported; no Lua provider is registered and ordinary startup creates no Lua state until a later package/API change supplies a Lua-backed provider.
- No persisted catalogue, package, configuration, message, credential, or plugin-state schema changes occur.
- No user-visible UI, channel behavior, SDK level, permission, release-signing, or foreground-retention change is intended.
