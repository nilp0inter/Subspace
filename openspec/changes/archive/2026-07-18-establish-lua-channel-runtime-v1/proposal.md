## Why

The production-internal Lua actor now provides bounded, generation-safe execution, but no Lua program can participate in the channel framework because the application has no public Lua runtime contract, Subspace module API, or Lua-backed provider/runtime adapter. Define and prove that compatibility boundary now, before package installation, distribution, or official-channel migrations depend on internal actor, JNI, or Kotlin details.

## What Changes

- Define Subspace Lua Runtime v1 as a source-only Lua 5.4.8 environment with explicit language behavior, standard-library availability, package-local pure-Lua module loading, per-instance module isolation, runtime/API version reporting, protected errors, cancellation, shutdown, and compatibility-failure semantics.
- Define the initial public Lua module surface: `subspace.runtime` for runtime identity and cooperative timer operations, `subspace.channel` for lifecycle, input, SOS, readiness, and terminal result contracts, and `subspace.log` for bounded structured plugin logging.
- Define one public asynchronous operation/result model shared by timer operations and future Subspace-native modules, without exposing actor identities, JNI outcome JSON, Kotlin coroutine objects, Android objects, or native handles.
- Add an immutable host-domain Lua program image and a Lua-backed `ChannelImplementationProvider`/`ChannelRuntime` adapter that translates provider construction, activation, generic channel events, input targets, readiness projection, replacement, and close through the existing actor runtime and generation gate.
- Prove the public contract with black-box Lua source fixtures through the real provider registry, runtime registry, capability scope, actor kernel, replacement, and shutdown paths, including independent same-provider instances, package-local `require`, proactive timer work while unselected, normalized failures, and stale-effect suppression.
- Keep ordinary application startup Kotlin-only: no production Lua provider is registered, no persisted catalogue entry changes, and no Lua state is created unless an explicitly supplied Lua program provider is resolved.
- Do not define the release archive/manifest format, GitHub repository identity, installation, discovery, update, rollback, signing, provenance, declarative UI schema, secure credential selection, plugin-owned writable directories, durable message/output APIs, RSM controls, unsolicited playback policy, official channel migration, or plugin-author tooling in this change.

## Capabilities

### New Capabilities

- `lua-runtime-api`: Versioned public Lua language/runtime environment, standard-library policy, package-local pure-Lua module loading, cooperative asynchronous operation semantics, errors, cancellation, shutdown, isolation, and compatibility reporting.
- `lua-channel-api`: Public `subspace.channel`, `subspace.runtime`, and `subspace.log` modules, including channel lifecycle/event callbacks, input outcomes, timers, structured logging, and Lua-visible normalized values.
- `lua-channel-provider`: Immutable Lua program images and the provider/runtime adapter that executes them through the existing channel registry, actor lifecycle, generation authorization, and host projections without a privileged parallel path.

### Modified Capabilities

- `channel-implementation-providers`: Extend provider-neutral runtime construction with an opaque host-owned generation execution context so language adapters can bind continuations, child work, and close to the registry generation without receiving Kotlin coroutine, actor, gate, or Android implementation objects.

## Impact

- Lua runtime: public module registration, value encoding, package-local source resolution, callback validation, traceback normalization, timer operations, and runtime/API version reporting over the existing source-only actor kernel.
- Android channel integration: new internal Lua program-image, provider, `ChannelRuntime`, input-target, snapshot, event, and operation adapters layered on `ChannelImplementationProvider`, `ChannelRuntimeRegistry`, `RuntimeGenerationInvocationGate`, and `ActorRuntime`.
- Native bridge: package-local source/module loading support may extend the internal kernel, but JNI entrypoints, handle encodings, outcome JSON, actor policy values, and scheduler topology remain private implementation details.
- Verification: public-contract JVM tests, Rust/kernel module-loading conformance where required, and physical-device instrumentation using immutable test fixtures through the complete provider-to-actor path.
- Existing Kotlin providers, catalogue persistence, UI, PTT/audio routing, capabilities, foreground-service behavior, SDK levels, permissions, release signing, and normal startup remain unchanged.
