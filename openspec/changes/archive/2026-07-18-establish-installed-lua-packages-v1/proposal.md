## Why

The public Lua runtime and provider adapter now execute immutable host-supplied program images through the production actor stack, but production startup has no installed-package source, durable provider identity, or package-to-provider registration path. Establishing a bounded installed-package substrate now closes that seam before GitHub discovery, richer Lua APIs, configuration, or official-channel migrations depend on ad hoc source loading or per-instance executable references.

## What Changes

- Define a versioned, source-only Lua channel package archive and manifest that maps one package to one durable provider identity, one release version, one entry module, and a bounded immutable module source map.
- Bind provider identity to a host-resolved durable GitHub repository identity while retaining mutable `owner/repository` coordinates only as presentation and link metadata; package-declared coordinates do not establish identity or provenance.
- Add static, non-executing package validation for manifest structure, runtime compatibility, canonical module names and paths, duplicate and case-colliding entries, archive traversal, source encoding, content bounds, and exact artifact digest.
- Add a separate app-private installed-package index and immutable content store that atomically stages, activates, updates, rolls back, and removes provider packages without placing package paths, hashes, versions, or source in `ChannelDefinition` configuration.
- Materialize one package-specific Lua `ChannelImplementationProvider` per active installed provider and compose it with built-in providers through the existing provider registry, catalogue, runtime registry, generation context, actor, and capability boundaries.
- Reconcile package activation, update, rollback, removal, corruption, and incompatibility as provider availability or provider-revision changes: preserve every channel instance and opaque configuration, drain old runtime generations before new ones become ready, and never silently fall back to another package or revision.
- Keep package-format v1 providers deliberately configuration-empty and capability-free. They may use the existing Lua v1 timer, lifecycle, input-metadata, SOS, and structured-log contracts; declarative configuration, credentials, writable state, runtime I/O modules, durable output, and official channel migration remain later changes.
- Accept only an exact host-supplied release artifact plus host-resolved repository/release identity in this change. GitHub querying, App Links, QR codes, topic discovery, automatic updates, final signing/attestation, and user-facing trust/install flows remain out of scope.

## Capabilities

### New Capabilities

- `lua-package-format`: Versioned Lua package archive and manifest, durable provider/release binding, immutable source mapping, compatibility declarations, digesting, and static fail-closed validation.
- `installed-lua-packages`: App-private package index and content store, atomic activation/update/rollback/removal, startup recovery and corruption handling, and publication of active installed providers.

### Modified Capabilities

- `lua-channel-api`: Replace the single internal test identity with package-specific production provider descriptors and host-resolved immutable images while retaining ordinary registry construction, generation isolation, and no-state-at-registration behavior.
- `channel-implementation-providers`: Compose a collision-safe installed-provider snapshot with built-in providers and expose atomic provider revision changes without shadowing, partial publication, or platform-object leakage.
- `channel-runtime-registry`: Reconcile installed-provider addition, revision replacement, removal, corruption, and recovery through existing unavailable-entry and drain-before-ready generation semantics.

## Impact

- Package domain: new manifest/archive parser, typed validation outcomes, durable repository/provider identity, exact artifact digest, package revision model, immutable installed content, and crash-safe active index under app-private storage.
- Lua integration: parameterized package-specific provider descriptor and immutable program-image materialization over the existing `LuaChannelImplementationProvider`, `ImmutableProgramImage`, actor factory, and JNI kernel.
- Channel composition: `PttForegroundService` provider setup, `ChannelImplementationProviderRegistry`, `ChannelRuntimeRegistry`, and service-lifetime reconciliation of installed-provider snapshots.
- Persistence: a new provider-level installed-package store; the existing channel catalogue schema and per-instance opaque configuration remain unchanged.
- Verification: focused archive/adversarial validation tests, transactional store and crash-recovery tests, provider collision/revision reconciliation tests, full package-to-Lua actor JVM tests, and physical-device installation/update/rollback/removal evidence through production composition.
- No SDK-level, Android permission, release-signing, built-in provider behavior, existing catalogue data, normal channel configuration, network/discovery, or user-facing UI change is intended.
