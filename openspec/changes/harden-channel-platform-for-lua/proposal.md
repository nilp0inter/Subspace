## Why

Subspace now has a useful host-owned PTT/audio boundary and an instance-keyed runtime registry, but the surrounding channel model remains closed over Kotlin built-ins and several lifecycle paths are unsafe for slow, failing, or externally supplied runtime code. Harden the channel platform now so current built-ins obey one instance-safe, host-controlled contract and a future shareable Lua package runtime can be added without redesigning persistence, hardware ownership, routing, or teardown.

## What Changes

- **BREAKING (internal architecture):** replace the persisted `ChannelKind`/sealed-configuration coupling with stable implementation identifiers and losslessly preserved, versioned configuration payloads resolved through registered channel implementation providers.
- Register each built-in through a descriptor that owns its default configuration, schema validation and migration, runtime construction, presentation metadata, and generic preparation traits.
- Preserve valid channel instances whose provider is missing, incompatible, or fails to load; project them as explicitly unavailable instead of rejecting the whole catalogue or silently omitting them.
- Introduce a host-owned channel capability boundary. Channels request semantic operations while Subspace owns Android resources, hardware transports, connection/reconnection policy, concurrency, cleanup, and detailed diagnostics.
- Move Sleepwalker ownership entirely behind a host text-output capability. Keyboard channel instances submit text and logical profile information without controlling BLE/GATT, HID operations, connection state, or reconnect policy.
- Generalize recoverable input preparation so routing depends on provider/runtime capability state rather than `ChannelKind.KEYBOARD` branches.
- Harden runtime invocation and teardown: no provider/runtime callbacks under registry locks or on Android main, bounded and cancellable invocation work, deterministic close ordering, exact-once terminal cleanup, and no late effects after closure.
- Complete the prior channel-generalization cutover by removing legacy channel models/accessors, obsolete controller-owned PTT/capture/route paths, singleton-ID dispatch, and first-by-kind configuration/resource lookup.
- Preserve the existing host-owned audio route, capture, beep, playback, catalogue ordering, active-selection, atomic persistence, and committed-target lease semantics.
- Add focused contract and composition tests for provider resolution, opaque configuration migration, unavailable providers, multiple same-provider instances, capability ownership, exception containment, timeout/cancellation, shutdown ordering, and exact-once cleanup.

## Capabilities

### New Capabilities

- `channel-implementation-providers`: Defines stable implementation descriptors, provider-owned configuration schemas and migration, runtime construction, presentation metadata, preparation traits, and explicit unavailable-provider behavior.
- `channel-host-capabilities`: Defines semantic, host-owned capabilities through which channels request effects while Android resources, hardware lifecycle, connection policy, and cleanup remain outside channel implementations.
- `channel-runtime-invocation`: Defines serialized and bounded runtime callback execution, cancellation and error normalization, lock/thread confinement, deterministic closure, and suppression of late effects.

### Modified Capabilities

- `channel-catalogue`: Replaces the closed built-in kind/configuration algebra with provider references and losslessly preserved versioned payloads while retaining stable instance IDs, ordering, active-selection, atomic commit, and legacy migration guarantees.
- `channel-framework`: Makes channel behavior depend only on generic runtime events, validated configuration, and host capabilities; removes built-in controller and platform-object leakage.
- `channel-routing`: Replaces Keyboard-specific recovery branches with generic preparation availability and preserves refusal before capture when required capabilities cannot be acquired.
- `channel-runtime-registry`: Prevents callbacks under registry locks, explicitly represents unavailable implementations, and defines close ordering relative to committed target leases and shutdown.
- `audio-input-session-lifecycle`: Strengthens every terminal path so target notification, route release, lease release, active-session clearance, and completion publication remain exact-once despite exceptions, cancellation, timeout, or service teardown.
- `keyboard-channel`: Moves Sleepwalker connection, reconnect, HID compilation/transport, and cleanup to the host text-output capability while preserving per-instance profile and text-delivery behavior.
- `main-device-dashboard`: Discovers creation/configuration/presentation metadata from registered providers and keeps unavailable channel instances visible with actionable status.
- `car-media-channel-browse`: Preserves catalogue order and stable media IDs for unavailable provider-backed instances rather than dropping them from projections.

## Impact

- Affected model/persistence code: channel definitions, catalogue validator/codec/file migration, repository initialization, and legacy channel adapters.
- Affected runtime code: runtime/factory contracts, registry reconciliation and shutdown, PTT dispatch policy, audio-session terminal handling, and runtime snapshot aggregation.
- Affected built-ins: Journal, Debug, and Keyboard runtime composition; Sleepwalker becomes a shared host service exposed through a semantic text-output port.
- Affected UI/projections: catalogue creation, configuration routing, channel cards, and Android Auto browse availability.
- Persisted catalogue format requires an atomic forward migration that preserves existing seeded IDs, order, active selection, names, enabled state, and built-in configuration.
- No Lua engine, script package installer, package signing/distribution mechanism, persistent script KV store, Lua API syntax, or built-in Lua rewrite is introduced by this change.
