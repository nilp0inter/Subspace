## Context

The completed Lua work now has four stable layers:

```text
provider-neutral channel platform
        ↓
source-only Lua 5.4.8 kernel
        ↓
per-generation Lua actor runtime
        ↓
public Lua Runtime v1 and Lua provider adapter
```

`ImmutableProgramImage` is currently constructed in memory and passed directly to one internal `LuaChannelImplementationProvider`. Production service composition registers only the Journal, Debug, Keyboard, and OpenAI Agent built-in providers. No package index, artifact validator, installed source store, package-specific provider identity, or production Lua registration exists.

The channel catalogue already contains the correct instance-level binding: each definition stores a stable instance ID, a stable implementation-provider reference, and provider-owned opaque configuration. It preserves definitions whose provider is absent or incompatible. Executable package identity and bytes therefore belong in a separate provider-level store, not in `ChannelDefinition` or its configuration payload.

The vision requires one GitHub repository to identify one package and one channel provider, with multiple independently configured channel instances. Repository renames and transfers must not change provider identity, and mutable `owner/repository` coordinates must not permit a new repository to inherit an installed provider. The eventual normal installation path uses exact GitHub release assets, but GitHub querying, discovery, trust UI, and final signing are deliberately outside this change.

Package code is trusted once installed, but package parsing is not trusted execution. A malformed archive must not traverse paths, allocate without bounds, shadow built-ins, execute Lua during validation, or partially replace an active provider. Android API 31+, app-private storage, foreground-service lifecycle, generation-safe actor teardown, and existing Kotlin providers remain constraints.

Package-format v1 intentionally supports only the already-public Lua surface. Its providers have empty configuration, no declared host capabilities, no writable package state, and no general HTTP/filesystem modules. This yields a narrow but complete external-artifact-to-production-runtime substrate without pre-deciding later configuration, credential, I/O, output, or migration contracts.

## Goals / Non-Goals

**Goals:**

- Define one deterministic versioned ZIP package format containing a strict manifest and source-only Lua modules.
- Bind every accepted package to a host-resolved durable GitHub repository identity and exact release asset provenance without treating mutable coordinates or package assertions as authority.
- Validate the complete artifact statically and with finite resource bounds before storing, activating, registering, or executing it.
- Store exact artifacts immutably by SHA-256 digest and maintain a separate crash-safe installed-provider index with one active and at most one rollback revision per provider.
- Support idempotent install, atomic update, explicit rollback, and removal while preserving the previously committed active state on pre-commit failure.
- Materialize a package-specific Lua provider for each active installed package without creating a Lua state at package load or provider registration time.
- Compose installed providers atomically with immutable built-in registrations and reject the whole installed snapshot on any provider-ID collision.
- Make package revision changes trigger ordinary runtime-registry reconciliation and drain-before-ready replacement for every affected instance.
- Preserve catalogue instances and opaque configuration when an installed provider is missing, removed, corrupt, incompatible, or fails construction.
- Load and validate installed packages off the Android main thread, keep unrelated installed providers and all built-ins operational after a local package failure, and expose typed diagnostics.
- Prove the full exact-artifact → installed store → provider registry → catalogue instance → Lua actor path on the supported Android device.

**Non-Goals:**

- No GitHub API client, release enumeration, topic discovery, website index, QR code, App Link, copied-link flow, or automatic update policy.
- No user-facing package browser, installer, warning, trust, update, rollback, or uninstall UI.
- No claim that SHA-256 authenticates a publisher; no final signature, certificate, Sigstore, attestation, or transparency-log format.
- No installation or execution from a mutable branch, working tree, arbitrary source directory, loose Lua file, network-loaded chunk, or bytecode.
- No package assets, documentation rendering, native libraries, C modules, JNI, FFI, cross-plugin dependencies, or plugin-supplied executable formats in package-format v1.
- No declarative data/UI configuration schemas, nonempty plugin configuration, dynamic choices, secure credential access, or host-resource references.
- No package-writable data/cache/temp directories, persistent plugin key-value state, backup/export policy, or retained data after uninstall.
- No public HTTP, JSON, socket, filesystem, database, event-loop, audio, transcription, synthesis, text-output, durable-message, playback, or RSM-control Lua modules.
- No change to Lua Runtime v1 callback, value, error, timer, logging, source-map, or exact Lua/API compatibility semantics.
- No official Kotlin-channel migration and no change to existing built-in provider behavior.
- No execution guarantee after the foreground service or Android process stops.
- No per-channel package-version pinning or package artifact reference in catalogue configuration.

## Decisions

### D1. Package-format v1 is a strict source-only ZIP

An artifact is a ZIP file with exactly these file categories:

```text
manifest.json
lua/<canonical-module-path>.lua
```

`manifest.json` is one UTF-8 JSON object with these required fields:

```text
manifestVersion       integer, exactly 1
repositoryId          decimal string for the durable GitHub repository ID
packageVersion        nonblank bounded release-version string
entryModule           canonical Lua module name
presentation.label    nonblank bounded string
presentation.summary  nonblank bounded string
runtime.luaVersion    exact required Lua language string
runtime.apiVersion    exact required Subspace Lua API string
```

No field establishes authority merely because the archive contains it. The install request supplies a host-resolved repository identity and exact release-asset record. `repositoryId` must exactly match that resolved identity or validation fails. The host derives provider identity from the resolved repository ID, not from mutable coordinates, presentation strings, package filename, release tag, or code content.

A Lua file path maps deterministically to one module name:

```text
lua/main.lua          → main
lua/client/http.lua   → client.http
```

Each segment must satisfy `[a-z][a-z0-9_]*`. Empty segments, `.`/`..`, backslashes, repeated separators, absolute paths, uppercase aliases, percent-encoded separators, Unicode lookalikes, trailing separators, and any noncanonical spelling are rejected. `entryModule` must resolve to one included module. The package contains no explicit module map; deriving names from canonical paths eliminates alias and traversal ambiguity.

The archive must contain exactly one `manifest.json`, at least one Lua source, and no unrecognized regular files. Directory entries may be ignored only when their normalized path is a parent of an accepted Lua file. Duplicate names, case-fold collisions, non-regular entries, encrypted entries, unsupported compression methods, data descriptors that prevent bounded accounting, and inconsistent local/central metadata fail the complete artifact. Package-format v1 permits stored and deflated regular entries only. Portable ZIP metadata does not encode a regular file's filesystem inode or link count, so validation rejects explicit link and non-regular metadata but does not misclassify two independent byte-identical regular entries as a hard link.

Manifest unknown fields fail validation. Future additive fields use a later `manifestVersion`; v1 readers do not guess forward compatibility. Lua modules must be valid UTF-8 source text, must pass existing `ImmutableProgramImage` validation, and must not begin with the Lua binary-chunk signature. The existing source-map and runtime bounds remain authoritative after archive-level bounds pass.

The parser enforces explicit limits for compressed artifact bytes, archive entries, manifest bytes, per-source uncompressed bytes, total uncompressed bytes, path bytes, and compression expansion. It streams bounded content and never extracts into caller-selected paths.

**Alternative considered:** install a directory or loose `main.lua`. Rejected because it cannot provide immutable exact-artifact identity, complete-file validation, atomic storage, or reproducible rollback.

**Alternative considered:** include a manifest module-name-to-path map. Rejected because two independent namespaces create alias, collision, and traversal cases without adding useful v1 flexibility.

**Alternative considered:** permit unknown manifest fields for forward compatibility. Rejected because ignored executable-package metadata can acquire ambiguous meaning. Format-version negotiation is explicit.

### D2. Durable repository identity is separate from coordinates and artifact integrity

The host-domain identity is:

```text
GitHubRepositoryIdentity(repositoryDatabaseId)
```

The repository database ID is stored as a positive decimal string so it remains lossless across JSON and Kotlin/Java numeric boundaries. The corresponding channel implementation ID is derived in the reserved installed-provider namespace:

```text
github-repository:<repositoryDatabaseId>
```

`builtin:*`, `internal:*`, and future host-reserved namespaces cannot be supplied by a package. One repository identity can have only one active provider package. Repository rename or transfer updates separately observed `owner/repository` link metadata but does not change the implementation ID or instance bindings.

The install request also carries a host-resolved source record:

```text
repository identity
owner/repository coordinates observed at resolution
release identity and tag
release prerelease flag
asset identity and asset name
exact artifact bytes
```

This source record is persisted as provenance metadata for diagnostics and future distribution integration. It is not delivered to Lua. The package's repository assertion and the host-resolved identity must match.

SHA-256 is computed over the exact artifact bytes while staging. The digest supplies content addressing and detects corruption or substitution relative to the installed index. It does not prove publisher authorship. No UI or diagnostic may describe a digest-only package as signed, verified by its publisher, reviewed, or endorsed.

**Alternative considered:** derive provider identity from `owner/repository`. Rejected because rename, transfer, deletion, and name reuse can either orphan instances or transfer identity to a different repository.

**Alternative considered:** derive identity from the artifact digest. Rejected because every update would create a new provider and break the one-provider/many-instances model.

**Alternative considered:** trust the manifest's repository ID without an externally resolved expected identity. Rejected because any archive can self-assert another repository's ID.

### D3. Exact archives are immutable content; activation lives in a separate index

The app-private store has this logical shape:

```text
installed-lua-packages/
  content/sha256/<digest>     exact ZIP bytes, immutable after commit
  staging/<operation-id>     incomplete private writes only
  index.json                 atomic active/rollback bindings
  index.backup.json          last known committed index
```

Exact private filenames are implementation details, but these ownership rules are contractual:

- Content is addressed by lowercase SHA-256 and is never modified in place.
- A digest path is published only after the full bytes are written, hashed, parsed, compatibility-checked, and converted successfully to an `ImmutableProgramImage`.
- The index maps one provider identity to one active revision and zero or one rollback revision.
- A revision records repository identity, observed coordinates, release and asset identity, package version, artifact digest, manifest version, runtime requirements, and presentation metadata. It does not duplicate Lua source.
- Index replacement is all-or-nothing. A failed write, flush, rename, decode, or validation leaves the prior committed index authoritative.
- Content not referenced by the committed index is orphaned and may be removed during bounded recovery cleanup.
- The exact archive is reparsed and its digest revalidated when materialized after process start. No unchecked decoded cache becomes an executable source of truth.

Storing the exact archive rather than an extracted source tree removes mutable extraction state and makes digest verification cover every executable byte. Materialization reads the archive into a validated immutable source map before provider publication; runtime construction never performs package download or unbounded filesystem discovery.

**Alternative considered:** store extracted modules as the active package. Rejected because file-by-file replacement and partial extraction complicate integrity, crash recovery, and rollback.

**Alternative considered:** put package digest, path, or version in every `ChannelDefinition`. Rejected because executable selection is provider-level, updates must affect all same-provider instances consistently, and the existing catalogue already preserves a stable provider reference independently of availability.

### D4. Install, update, rollback, and removal are explicit transactions

All mutations are serialized by the installed-package repository and use a prepare/commit/publish sequence:

```text
receive exact artifact and resolved source identity
  → stream to private staging while hashing and enforcing compressed bound
  → parse and validate entire archive without executing Lua
  → validate repository binding and exact runtime compatibility
  → construct ImmutableProgramImage in memory
  → commit immutable digest content if absent
  → write and atomically replace complete index
  → publish one immutable installed-provider snapshot
```

Operation semantics:

- **First install:** active becomes the validated revision; rollback is absent.
- **Idempotent reinstall:** installing the exact active provider/digest/source returns the existing active result and creates no new revision or runtime replacement.
- **Update:** validated incoming revision becomes active; the former active revision becomes the sole rollback revision. A previous rollback revision is dropped from the new index and later becomes eligible for orphan cleanup.
- **Explicit rollback:** the rollback revision is fully revalidated, then active and rollback swap atomically. Rollback never means “find something older” or fetch an artifact.
- **Removal:** the provider binding and both retained revisions are removed atomically. Unreferenced content becomes eligible for cleanup. Channel definitions are untouched and project the ordinary missing-provider state.
- **Invalid or incompatible incoming artifact:** no content/index/provider publication occurs; the current active provider remains unchanged.
- **Failure after index commit but before in-process publication:** the committed revision remains authoritative. Publication is retried from the index; the host does not silently revert persisted state.

No operation executes Lua, creates an actor, creates a Lua state, invokes provider callbacks, or mutates the channel catalogue. Activation selects executable provider source; runtime creation remains demand-driven by catalogue reconciliation.

Automatic rollback is prohibited. Corruption, compatibility failure after an app/runtime upgrade, or runtime construction failure makes the provider explicitly unavailable until the user or a future policy chooses update, rollback, or removal. This avoids executing an older revision without an explicit decision.

**Alternative considered:** fetch or unpack inside `constructRuntime`. Rejected because runtime construction must receive deterministic local inputs and must not block registry construction on network/package I/O or introduce package lifetime into generation teardown.

**Alternative considered:** automatically fall back to the retained revision. Rejected because an older package may have known defects or incompatible state assumptions, and fallback would execute unselected code.

### D5. Built-ins are immutable; installed providers publish as one collision-safe snapshot

`ChannelImplementationProviderRegistry` is divided logically into:

```text
immutable built-in registrations
        +
replaceable immutable installed-provider snapshot
        =
current provider resolution snapshot
```

Built-ins continue to use deterministic one-time `register`. A new host-owned installed-snapshot operation validates the entire candidate set before publication:

- every installed implementation ID is nonblank, canonical, and derived from its resolved repository identity;
- installed IDs are unique;
- no installed ID collides with a built-in or host-reserved ID;
- descriptor ID, configuration-provider ID, package identity, and snapshot key agree;
- every provider carries one immutable revision fingerprint equal to its artifact digest;
- any invalid entry rejects the complete candidate snapshot without changing current resolution.

Publication swaps one immutable map atomically and increments a monotonic provider-snapshot revision. `resolve` and `descriptors` observe either the complete predecessor or complete successor, never a partial mix. Package parsing and provider construction occur before the registry lock or atomic swap. No package callback executes under registry synchronization.

The package repository exposes its load/publication state as host-domain values such as `Loading`, `Ready(snapshot)`, and `Failed(error)`. Built-ins remain resolvable in every package-repository state. A malformed individual active artifact yields an unavailable installed-provider record for that repository identity while valid siblings still materialize. A globally unreadable index publishes no installed providers and one actionable store failure; it never invalidates built-ins.

**Alternative considered:** call existing duplicate-rejecting `register` for every package on every update. Rejected because registration cannot replace a provider revision atomically and would leave stale descriptors or partial installed sets.

**Alternative considered:** rebuild and replace built-ins together with packages. Rejected because package failure must not perturb trusted static provider composition.

### D6. Installed provider revision is part of runtime reconciliation identity

A live registry entry is currently keyed by channel definition/generation. This change also associates its resolved provider revision fingerprint:

```text
Runtime source identity
    = ChannelDefinition effective revision
    + resolved provider implementation ID
    + resolved provider revision fingerprint
```

Reconciliation behavior:

- Provider addition: a definition previously projected as missing can construct normally from the newly available provider.
- Same active digest republished: no runtime replacement.
- Provider digest changes by update or rollback: every enabled definition referencing that provider receives a successor generation.
- Provider removal, corruption, or incompatibility: new admission stops; committed targets finish their terminal callback; descendants and effects drain or cancel; the old generation closes; the definition remains in place as unavailable.
- Provider recovery with the same or another explicitly selected digest: a fresh generation is constructed. Volatile Lua state, coroutines, timers, logs, and operation authorization are never restored.
- One provider's failure or replacement does not reconstruct or close instances belonging to another provider.

All replacements retain the existing predecessor drain-before-successor-ready invariant. A successor may validate immutable source while the predecessor drains but may not execute startup, receive effect authorization, or publish readiness before predecessor close. Package update does not introduce a second runtime cutover path.

**Alternative considered:** reconcile only when `ChannelDefinition` changes. Rejected because provider code can change globally while every instance definition remains byte-identical.

**Alternative considered:** keep existing actors alive until service restart after package update. Rejected because the active installed revision and running code would disagree, and rollback/update behavior would be nondeterministic.

### D7. One active package materializes one package-specific Lua provider

The current internal Lua provider becomes a reusable adapter whose constructor receives host-owned values:

```text
implementation ID derived from repository identity
presentation metadata from validated manifest
empty v1 configuration provider bound to that implementation ID
empty configuration fields
empty required-capability set
immutable program image
artifact digest as provider revision fingerprint
retained Lua kernel bridge and actor policy
```

The fixed `internal:lua` provider remains only where focused internal fixtures require it or is removed after those fixtures use package-specific test identities. Production package descriptors never use a generic shared Lua implementation ID: one repository/provider identity maps to one descriptor and may back many independent catalogue instances.

Package-format v1 accepts only schema version 1 with an empty JSON object. Existing generic catalogue create/update/migration paths remain authoritative. A package cannot declare configuration fields, capabilities, Android resources, source paths, credentials, or platform objects through unrecognized manifest fields.

Provider materialization and registration create no Lua actor or Lua state. A state is created only when `ChannelRuntimeRegistry` constructs a generation for a matching enabled catalogue definition. Multiple instances using one package share immutable archive/source bytes where safe but receive distinct program-image validation context, actors, states, module caches, timers, logs, generation contexts, and capability scopes.

**Alternative considered:** register one generic `internal:lua` provider and put package identity in instance configuration. Rejected because it conflates provider and instance identity, weakens update/uninstall behavior, and contradicts one repository = one provider.

### D8. Package loading is asynchronous and does not block Android service creation

The service composition root creates the installed-package repository and starts bounded loading on the existing service-owned worker/IO boundary. Built-ins, catalogue loading, and foreground-service startup do not wait for archive hashing or parsing on the Android main thread.

Until installed-provider loading finishes, catalogue definitions referencing installed IDs remain preserved and project a typed provider-loading or missing-provider state. When the repository publishes a snapshot, the service:

1. atomically replaces the installed-provider snapshot;
2. republishes available descriptors for host surfaces;
3. reconciles the current catalogue snapshot against the new provider snapshot.

The same sequence handles install, update, rollback, removal, and recovery. Configuration is empty in v1, so an initially missing installed provider requires no delayed configuration migration; runtime construction validates the preserved schema version and payload when the provider becomes available.

Package repository shutdown stops mutation admission, joins or cancels staging operations, discards incomplete staging files, and publishes no later provider snapshot. Runtime-registry shutdown remains authoritative for actor and capability teardown. Package files and committed index records survive service/process shutdown; actors and Lua states do not.

**Alternative considered:** synchronously parse every active archive inside `PttForegroundService.onCreate`. Rejected because bounded file I/O is still Android main-thread I/O and package count/size can vary.

### D9. Failures are typed, local where possible, and fail closed

Typed outcomes distinguish at least:

- unsupported manifest format;
- malformed or oversized manifest;
- repository identity mismatch;
- invalid or reserved provider identity;
- runtime/API incompatibility;
- malformed ZIP or unsupported ZIP feature;
- duplicate, case-colliding, noncanonical, traversing, or unexpected entry;
- entry-count, compressed-size, uncompressed-size, path-size, or expansion-limit exhaustion;
- missing or duplicate entry module;
- invalid UTF-8, binary chunk, invalid source, or program-image validation failure;
- artifact digest mismatch or committed-content corruption;
- package-version/source-record mismatch;
- provider collision or inconsistent descriptor;
- staging, content, index, commit, or cleanup I/O failure;
- no rollback revision;
- repository/index closed or busy.

Validation rejects the whole candidate artifact. No validator strips unknown files or manifest fields, substitutes a module, repairs a path, chooses a different repository identity, downgrades compatibility, or publishes a partial source map.

Per-provider active-content corruption makes only that installed provider unavailable. Global index corruption uses the last known committed backup only when that backup is itself complete and valid; otherwise installed publication fails as a whole while built-ins remain operational. Recovery reports the exact selected index generation and never merges fields from corrupt documents.

Diagnostics identify provider identity, artifact digest prefix, release/asset identity, validation phase, and normalized outcome. They exclude Lua source, channel configuration, credentials, message text, audio, mutable filesystem paths outside the package root, and full raw archive contents.

### D10. Use existing platform libraries and preserve the trust boundary

Implementation uses platform/JDK facilities already available on API 31+: bounded file streams, `java.util.zip`, `MessageDigest`, and the existing strict JSON and atomic-file patterns. No general archive, package-manager, cryptography, GitHub, or scripting dependency is introduced unless implementation proves the platform ZIP metadata cannot satisfy a required validation; any such dependency requires an artifact update before implementation continues.

Package parsing remains a host operation, not a Lua capability. Lua receives only its validated source map and existing public modules. Package code cannot inspect its archive path, digest, repository coordinates, release record, or installed-store objects through Lua Runtime v1.

Trusted-code installation does not weaken host reliability. Source execution still occurs only inside the actor generation with allocator/instruction policy, protected entry, generation authorization, revocable effects, replacement draining, and deterministic close. The package layer neither promises isolation from native engine defects nor grants raw Android/JVM/native objects.

### D11. Verification covers archive hostility, transactional failure, and real runtime cutover

Verification uses immutable generated package fixtures and recorded source identities, never a mutable repository branch. It has four layers:

1. **Pure format/identity tests:** valid manifest/source mapping; every malformed JSON/type/bound; canonical path grammar; duplicate and case collision; traversal; unexpected files; unsupported ZIP features; UTF-8 and bytecode rejection; repository mismatch; compatibility mismatch; exact digest; no Lua execution during validation.
2. **Transactional store tests:** first install, idempotent reinstall, update, rollback, removal, orphan cleanup, corruption, backup recovery, and injected failure before/after every content/index commit boundary. Assertions observe committed index/content/provider snapshots rather than private helper calls.
3. **Provider/runtime tests:** package-specific descriptors, namespace collisions, atomic snapshot publication, multiple instances, no-state-at-registration, package revision replacement, predecessor drain-before-successor-ready, stale-effect suppression, removal-to-unavailable, and recovery-to-fresh-generation through the real provider/runtime registries and actor kernel.
4. **Android device evidence:** install an exact instrumentation fixture artifact into app-private storage through the production package repository, create or use a matching catalogue definition, observe startup/background timer logging through the packaged JNI runtime, update to a distinguishable revision, explicitly roll back, remove the package, and verify ordered ready/unavailable transitions without native crash or late old-generation effect. Reinstall/restart verifies committed package persistence and fresh Lua generation state.

The release-equivalent APK contains the package subsystem and single Lua JNI library but no bundled external package, fixture archive, production test provider, installer UI, mutable-source loader, or automatic network path. An empty installed store keeps ordinary startup Lua-dormant and preserves all built-in behavior.

## Risks / Trade-offs

- **[Risk] ZIP parsing accepts a path or metadata ambiguity that differs across JDK implementations.** → Normalize and validate names before use, reject unsupported flags/features, compare local and central metadata where exposed, never extract by entry path, and defend every accepted/rejected class with real archive fixtures.
- **[Risk] Hashing and reparsing active archives increases startup I/O.** → Load off-main, bound artifact count/size, publish built-ins immediately, cache only after verification within one process, and measure startup/materialization cost without turning observations into public limits.
- **[Risk] Index commit succeeds but process death occurs before provider publication.** → Treat the committed index as authoritative and reconstruct publication on restart; never revert silently to in-memory predecessor state.
- **[Risk] Package update replaces several live instances simultaneously.** → Reuse per-instance generation reconciliation and drain-before-ready; updates select provider source globally but do not collapse instance lifecycles into one actor.
- **[Risk] A corrupt active archive leaves instances unavailable despite a valid rollback revision.** → Fail closed and require explicit rollback. Automatic execution of older code is intentionally prohibited.
- **[Risk] Repository database ID semantics change or prove insufficient for deletion/recreation handling.** → Isolate the representation behind a host-domain identity type and persist the original resolved source record; do not derive identity from coordinates. Revisit through an artifact update before GitHub integration if source-verified API guarantees differ.
- **[Risk] Package-format v1 becomes too narrow for real channels.** → Accepted. Its purpose is executable artifact identity, installation, and provider cutover. Later manifest versions add declarative configuration, module requirements, or assets explicitly rather than hiding them in v1.
- **[Risk] Digest language is mistaken for publisher authentication.** → Persist and display digest as integrity/content identity only; prohibit signed/verified/endorsed claims until a separate provenance contract exists.
- **[Risk] Removing a package strands channel instances.** → This is intended provider-unavailable behavior. Preserve definitions, order, names, active selection, schema version, and opaque payload so reinstalling the same durable provider can recover them.
- **[Trade-off] The first installable Lua packages can only use timers, callbacks, input metadata, SOS, and logs.** → Accepted. It closes the production executable path before richer APIs and official migrations expand the compatibility surface.
- **[Trade-off] Exact runtime/API equality limits compatible release selection.** → Retain the established Lua Runtime v1 contract. Compatibility ranges and per-module versions belong to a later distribution/versioning change.
- **[Trade-off] One rollback revision limits historical downgrade choices.** → Accepted for bounded app-private storage and deterministic semantics. A later package-management UX may define a larger retained history.

## Migration Plan

1. Add package-format and host-domain identity types, strict parser/validator, exact digesting, immutable revision representation, and adversarial fixture coverage without production composition.
2. Add the app-private content store and atomic index with absent-store-as-empty migration, transaction failure injection, recovery, and orphan cleanup.
3. Generalize the Lua provider adapter to accept a package-derived descriptor identity, presentation, empty configuration provider, immutable image, and revision fingerprint while retaining internal fixture compatibility only where still required.
4. Split provider registry ownership into immutable built-ins plus one atomically replaceable installed snapshot; add monotonic snapshot revision and collision validation.
5. Extend runtime reconciliation identity with provider revision and exercise addition/update/rollback/removal/recovery through existing generation teardown.
6. Compose asynchronous package loading/publication in the foreground-service shell and reconcile the current catalogue after every committed installed snapshot.
7. Add end-to-end JVM and physical-device package lifecycle evidence, release-equivalent packaging checks, and empty-store/built-in regression barriers.
8. Remove transitional fixed-ID production paths, loose fixture loaders, duplicate package parsing, and any code that places executable references in channel configuration.

Existing installations require no catalogue migration. Absence of the installed-package store means an empty installed snapshot. Existing built-ins retain their identifiers, descriptors, configurations, runtimes, and startup behavior.

Rollback removes installed-provider composition and package mutation entrypoints while leaving the channel catalogue untouched. If a development or test installation contains package-backed definitions, they naturally become missing-provider entries. App-private package content/index files may be removed by an explicit rollback cleanup or ignored by the older build; they are never interpreted as channel configuration.

## Open Questions

None for this change. Exact private class names, directory filenames, internal numeric bounds, dispatchers, and JSON codec helpers are implementation details. GitHub API guarantees, discovery UX, final provenance/signing, package configuration, writable state, runtime I/O modules, richer compatibility ranges, and official-channel migration remain deliberately unresolved for later changes.
