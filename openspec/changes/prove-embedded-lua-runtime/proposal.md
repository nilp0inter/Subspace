## Why

`PLUGIN_SYSTEM_VISION.md` depends on an in-process Lua runtime, but the repository has no evidence that stock Lua can be embedded on the target Android toolchain with safe JNI continuation handling, bounded interruption, per-instance memory accounting, deterministic teardown, and acceptable device cost. Committing the production actor runtime to an unproven bridge topology would spread assumptions through later lifecycle, API, package, and channel-migration changes.

## What Changes

- Add a non-user-visible, source-only Lua 5.4 runtime substrate proof for Android API 31+ on the supported `arm64-v8a` target.
- Establish decision-grade experiments for independent Lua-state creation, protected package and callback execution, coroutine yield/resume across the JVM/native boundary, cancellation races, instruction-budget interruption, allocator-enforced memory denial, and deterministic state closure.
- Compare the credible JVM/native ownership boundaries and select one bridge topology using measured correctness, lifecycle, latency, memory, and maintainability evidence; remove losing experimental paths before completion.
- Define and verify the initial in-process containment ceiling: pure-Lua failures and quota violations are instance-contained, while native engine, JNI/Rust bridge, unprotected panic, unrecoverable process-OOM, and Android process-death failures are not claimed to be isolated.
- Record target-device measurements and explicit proof outcomes that later production-runtime changes can consume without treating experimental constants as a public compatibility contract.
- Identify follow-up OpenSpec changes for the production actor scheduler and lifecycle integration, public Lua runtime/API contract, package installation, configuration/state, durable output, discovery, and official channel migrations.
- Do not register external providers, change current Kotlin channel behavior, expose a public Lua ABI, load third-party packages, or ship a permanent user-visible conformance channel in this change.

## Capabilities

### New Capabilities

- `embedded-lua-runtime-substrate`: Android build integration, protected native execution, coroutine continuation, bounded interruption, memory accounting, state isolation and teardown, containment limits, and decision evidence for the embedded Lua substrate.

### Modified Capabilities

None. Existing channel, runtime-registry, invocation, capability, audio, configuration, persistence, and foreground-service requirements remain unchanged until a later production-runtime change deliberately revises them.

## Impact

- Native build: a pinned stock Lua 5.4 source dependency and an internal JNI/native bridge or bridge candidates under the existing Nix, Cargo/NDK, Gradle, and `arm64-v8a` build constraints.
- Android code: internal proof harnesses and instrumentation seams that do not participate in production channel registration or user-facing navigation.
- Verification: host tests for bridge state machines plus device instrumentation and measurements on the supported Android hardware.
- Reliability: every JVM-to-Lua entry must use protected execution; bridge failures must be returned as normalized proof outcomes rather than relying on Rust panics or Lua's process-aborting panic path.
- Follow-up work: successful evidence informs separate changes for production scheduling/lifecycle, versioned Lua APIs, packaging/distribution, and channel migration; a failed proof requires revising the selected engine or runtime topology before those changes proceed.
