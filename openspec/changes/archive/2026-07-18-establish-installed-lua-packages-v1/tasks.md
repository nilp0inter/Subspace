## 1. Baseline and Package Domain Model

- [x] 1.1 Record the current provider, runtime-registry, immutable-program-image, service-composition, JSON-codec, and atomic-file integration seams before changing exported contracts
- [x] 1.2 Run the focused existing provider-registry, runtime-registry, Lua-provider, actor-runtime, and foreground-service tests as the pre-change baseline
- [x] 1.3 Define host-domain types for durable GitHub repository identity, observed repository coordinates, exact release identity, and exact asset identity without importing GitHub SDK or Android types
- [x] 1.4 Define canonical installed-provider ID derivation as `github-repository:<repositoryId>` with positive-decimal validation and host-reserved namespace rejection
- [x] 1.5 Define immutable package manifest, presentation, runtime-requirement, source-record, validated revision, and provider-revision fingerprint values
- [x] 1.6 Define sealed typed outcomes for package format, identity, compatibility, integrity, storage, recovery, mutation, rollback, loading, and shutdown failures
- [x] 1.7 Define finite host-configured validation bounds for exact artifact bytes, entry count, manifest bytes, path bytes, per-module bytes, total source bytes, and compression expansion
- [x] 1.8 Confirm platform ZIP metadata can enforce the specified fail-closed entry rules; update the change artifacts before introducing any new archive dependency if it cannot

## 2. Strict Package-Format v1 Validation

- [x] 2.1 Implement bounded streaming SHA-256 staging over the exact artifact bytes without retaining a partial accepted artifact on failure
- [x] 2.2 Implement strict duplicate-key JSON decoding for the exact package-format-v1 manifest shape and reject missing, unknown, mistyped, blank, or oversized fields
- [x] 2.3 Validate exact `manifestVersion`, Lua version, API version, repository ID, package version, entry module, label, and summary requirements before program-image creation
- [x] 2.4 Parse ZIP central and local metadata fail closed for encrypted entries, unsupported compression, inconsistent metadata, symlinks, explicit hard-link metadata, executable or unsupported entry types, and malformed archives
- [x] 2.5 Canonicalize archive entry names and reject absolute paths, traversal, empty or repeated segments, backslashes, percent-encoded separators, NULs, noncanonical Unicode, trailing file separators, duplicates, and case-fold collisions
- [x] 2.6 Accept exactly one root `manifest.json`, canonical parent directory entries, and one or more `lua/<module-path>.lua` regular files while rejecting every unrecognized file
- [x] 2.7 Enforce compressed-size, entry-count, manifest-size, path-size, per-source, total-source, and compression-expansion bounds while reading without growing overflow buffers
- [x] 2.8 Decode manifest and Lua sources as strict UTF-8, reject Lua binary chunks, derive canonical module names, and require the declared entry module to resolve exactly once
- [x] 2.9 Bind the manifest repository assertion to the host-resolved durable repository identity and retain coordinates, release, and asset only as source metadata
- [x] 2.10 Construct `ImmutableProgramImage` only after complete static validation and translate its typed validation or compatibility failure without creating a Lua state
- [x] 2.11 Return one immutable validated package revision containing the exact digest, bounded metadata, canonical source map, and program image only after every entry succeeds

## 3. Immutable Content Store and Atomic Index

- [x] 3.1 Define the versioned installed-package index schema for one active revision and zero or one rollback revision per durable provider identity
- [x] 3.2 Implement strict complete-index encoding and decoding with duplicate, unknown, invalid, inconsistent, and reserved-identity rejection
- [x] 3.3 Implement app-private staging and digest-addressed immutable content commit without extracting archive paths or modifying committed content in place
- [x] 3.4 Implement atomic complete-index replacement with a last-known committed recovery copy or equivalent atomic-file guarantee
- [x] 3.5 Implement absent-store startup as an empty index without reading or rewriting the channel catalogue
- [x] 3.6 Implement startup selection of one complete valid current or recovery index without merging generations or inferring installs from staged or orphaned bytes
- [x] 3.7 Rehash and fully revalidate every active exact archive before materializing an executable provider after process start
- [x] 3.8 Isolate active-content corruption to its provider while retaining valid sibling materialization and never auto-activating the rollback revision
- [x] 3.9 Implement bounded cleanup of incomplete staging and content unreferenced by the selected committed active and rollback records

## 4. Package Mutation Transactions

- [x] 4.1 Implement serialized per-provider mutation admission and repository-wide index generation ordering outside the Android main thread
- [x] 4.2 Implement first install as stage, validate, immutable-content commit, complete-index commit, then one immutable snapshot publication
- [x] 4.3 Implement exact active revision reinstall as an idempotent result with no index generation, rollback slot, snapshot, or runtime-revision change
- [x] 4.4 Implement update so the incoming validated digest becomes active, the former active becomes the sole rollback revision, and older retained content becomes orphan-eligible only after commit
- [x] 4.5 Implement explicit rollback by revalidating the retained exact archive and atomically swapping active and rollback without downloading or version inference
- [x] 4.6 Implement typed no-rollback behavior that leaves active content, index, provider snapshot, and runtimes unchanged
- [x] 4.7 Implement removal as an atomic provider-binding deletion that leaves every channel definition and opaque configuration untouched
- [x] 4.8 Preserve the prior committed index, active and rollback bindings, provider snapshot, and live runtime generations on every pre-commit validation, staging, content, or index failure
- [x] 4.9 Treat a successful index commit as authoritative when in-process publication fails or shutdown intervenes, and reconstruct publication from that index on restart
- [x] 4.10 Implement repository close to stop admission, bound or cancel staging and load work, close file resources, discard incomplete staging, and suppress late publication

## 5. Package-Specific Lua Provider Materialization

- [x] 5.1 Generalize `LuaChannelImplementationProvider` from the fixed internal identity to host-supplied implementation ID, presentation, immutable image, revision fingerprint, actor factory, kernel bridge, and policy
- [x] 5.2 Provide a schema-version-1 empty-object configuration provider with no fields, defaults, capabilities, credentials, dynamic choices, or host-resource references
- [x] 5.3 Materialize one descriptor per validated active repository identity and keep source records, archive paths, digests, and package-store clients outside runtime construction requests
- [x] 5.4 Keep provider materialization, descriptor publication, configuration validation, and catalogue loading Lua-dormant with no actor or Lua state creation
- [x] 5.5 Construct one isolated actor and Lua state only for an enabled matching catalogue instance through the ordinary provider and runtime-registry path
- [x] 5.6 Preserve per-instance actors, states, module caches, timers, tasks, logs, generation contexts, and capability scopes when multiple instances share one immutable package revision
- [x] 5.7 Retain the fixed internal Lua identity only for focused fixtures that still require it, and remove every production registration or fallback to a generic Lua provider

## 6. Atomic Provider Registry Snapshots

- [x] 6.1 Refactor `ChannelImplementationProviderRegistry` into immutable deterministic built-ins plus one atomically replaceable immutable installed-provider snapshot
- [x] 6.2 Add an opaque stable built-in revision fingerprint and expose the exact active artifact digest as each installed provider revision fingerprint
- [x] 6.3 Validate the complete installed candidate snapshot for canonical unique IDs, reserved or built-in collisions, and agreement among snapshot key, durable identity, descriptor, configuration provider, and revision
- [x] 6.4 Reject an invalid candidate snapshot without partial publication, shadowing, or mutation of the predecessor resolution map
- [x] 6.5 Publish a valid installed snapshot atomically with a monotonic snapshot revision so concurrent resolution observes either the complete predecessor or successor
- [x] 6.6 Keep package parsing, file I/O, descriptor construction, configuration callbacks, runtime construction, and observer reconciliation outside provider-registry synchronization
- [x] 6.7 Preserve explicit unavailable-provider resolution without lookup by display name, package version, configuration shape, catalogue order, or built-in kind

## 7. Provider-Revision Runtime Reconciliation

- [x] 7.1 Extend resolved provider and live runtime entries with the stable implementation ID and opaque provider-revision fingerprint that constructed each generation
- [x] 7.2 Reconcile unchanged definitions and equal provider fingerprints without replacing actors, states, or runtime generations
- [x] 7.3 Reconcile provider addition by constructing fresh generations for preserved enabled definitions that were previously unavailable
- [x] 7.4 Reconcile update and explicit rollback by replacing every enabled instance using the changed provider while leaving other providers' generations unchanged
- [x] 7.5 Reuse the existing drain-before-successor-ready path so predecessor admission, committed terminal callbacks, descendants, effects, capabilities, actor, and gate finish before successor startup or readiness
- [x] 7.6 Reject old-generation timer, task, callback, and operation completions after package replacement without entering or mutating the successor Lua state
- [x] 7.7 Reconcile provider removal, corruption, incompatibility, or materialization failure by closing affected generations and preserving typed unavailable entries and definitions
- [x] 7.8 Keep a failed successor unavailable without restarting the predecessor or automatically selecting the retained rollback revision
- [x] 7.9 Reconcile explicit reinstall or recovery as a fresh generation without restoring prior globals, module caches, timers, coroutines, tokens, latches, queues, logs, or authorization

## 8. Asynchronous Service Composition

- [x] 8.1 Compose one service-owned installed-package repository and bounded worker or I/O loading boundary without blocking built-in registration or foreground-service startup
- [x] 8.2 Expose host-domain loading, ready-snapshot, and failure states with stale-generation suppression for older load results
- [x] 8.3 Preserve package-backed catalogue definitions as loading or unavailable until a complete installed snapshot is ready
- [x] 8.4 Apply each complete publication by replacing the installed provider snapshot, updating host descriptor surfaces, and reconciling the current catalogue outside registry synchronization
- [x] 8.5 Keep built-in Kotlin providers operational and Lua-dormant when the installed store is absent, empty, globally corrupt, or still loading
- [x] 8.6 Wire install, update, rollback, removal, startup recovery, and repository shutdown through the same composition lifecycle without adding package UI or network discovery
- [x] 8.7 Emit bounded structured diagnostics for provider identity, digest prefix, source identity, validation phase, and typed outcome while excluding source, configuration, credentials, message text, audio, and raw archives

## 9. Functional Smoke Path

- [x] 9.1 Build a valid exact package fixture and smoke the static artifact-to-immutable-program-image path without Lua execution
- [x] 9.2 Smoke first install through committed index and installed-provider snapshot publication with an unchanged empty channel catalogue
- [x] 9.3 Smoke one package-backed definition through provider resolution, runtime construction, actor startup, readiness, and bounded log or timer behavior
- [x] 9.4 Smoke update, explicit rollback, removal, reinstall, and process restart through observable generation and availability transitions
- [x] 9.5 Smoke an empty or failed installed store and confirm ordinary built-in providers still resolve, construct, prepare input, and close

## 10. Focused Automated Verification

- [x] 10.1 Add pure package-format tests for valid mapping, strict manifests, identity binding, canonical names, traversal, duplicates, case and Unicode collisions, unsupported entries, ZIP metadata mismatches, UTF-8, bytecode, every bound, compatibility, digesting, and no-execution validation
- [x] 10.2 Add transactional store tests for first install, idempotent reinstall, update, rollback, no rollback, removal, corruption isolation, orphan cleanup, absent-store migration, backup recovery, and failure injection around every commit boundary
- [x] 10.3 Add shutdown and concurrency tests for serialized mutations, stale load suppression, commit-before-publication restart recovery, bounded close, incomplete staging cleanup, and no late publication
- [x] 10.4 Add provider tests for package-specific identities, empty configuration, immutable image ownership, multiple instances, reserved and built-in collisions, invalid complete snapshots, atomic publication, and no state at registration
- [x] 10.5 Add runtime-registry tests for provider addition, equal-fingerprint no-op, update and rollback replacement, multi-instance cutover, provider isolation, drain-before-ready, stale completion rejection, removal, failed successor, and fresh recovery
- [x] 10.6 Add service-composition tests proving asynchronous package loading, preserved loading definitions, catalogue reconciliation after publication, empty-store Lua dormancy, built-in survival, and shutdown ownership
- [x] 10.7 Re-run the focused existing provider, catalogue, runtime, Lua actor, foreground-service, and lifecycle suites and fix only regressions caused by this change
  - Evidence: repaired `ServicePlatformCompositionTest` by running lease-blocked replacement reconciliation concurrently, awaiting its completion after terminal lease release, and comparing cleanup against acquired process-wide generation identities. The full focused suite passed with no skipped test.

## 11. Android Device and Packaging Evidence

- [x] 11.1 Add immutable instrumentation fixtures that exercise the production package repository and real provider-to-actor path without shipping a production test provider or mutable-source loader
- [x] 11.2 Install an exact package on the supported Android device, create or load a matching catalogue definition, and record successful packaged-JNI startup plus distinguishable timer or log behavior
- [x] 11.3 Update the device package to a distinguishable digest and verify ordered predecessor close, successor readiness, and absence of late old-generation effects
- [x] 11.4 Explicitly roll back, remove, reinstall, and restart on device and record expected ready or unavailable projections, committed persistence, and fresh volatile Lua state
- [x] 11.5 Exercise malformed, incompatible, or corrupt package failure on device and verify failure remains local while built-ins and valid sibling packages remain operational
- [x] 11.6 Re-run the established physical RSM, SCO, Telecom, background-operation, and disconnect-teardown acceptance flow to confirm package composition does not alter existing behavior
  - Evidence: user reported all prescribed physical checks successful: PTT press/release, Control/Active mode transitions, volume click reset, headset-routed echo, background operation with foreground notification, and disconnect notification teardown.
- [x] 11.7 Build debug and release-equivalent APKs and verify the package subsystem and single Lua JNI library are present while external packages, fixture archives, test providers, installer UI, loose loaders, duplicate bridges, bytecode loaders, and native plugin modules are absent
- [x] 11.8 Run the repository flake validation and capture APK/build provenance, per-case device outcomes, observed transition ordering, and measured startup/materialization observations without declaring new public numeric guarantees
  - Evidence: debug and unsigned release APKs built successfully; each contains exactly `lib/arm64-v8a/libsubspace_lua_actor.so`, production package classes, and no fixture/test-provider/loose-loader/native-plugin archive entries. Four production-path device cases passed; transition assertions cover predecessor close before successor startup, fresh state on rollback/reinstall/restart, and local corruption isolation. `nix flake check --no-write-lock-file` passed on x86_64-linux.

## 12. Final Cutover and Artifact Reconciliation

- [x] 12.1 Remove obsolete production fixed-ID Lua registration, duplicate package parsing or source loading, executable references in channel configuration, and scaffolding not used by the final path
- [x] 12.2 Review all exported provider and runtime call sites for the final revision-fingerprint contract and remove transitional aliases, fallbacks, and special-case dispatch
- [x] 12.3 Reconcile proposal, design, delta specs, tasks, and implementation evidence with any implementation-discovered contract changes before declaring the change complete
- [x] 12.4 Confirm every task and acceptance scenario is evidenced, all affected main and test sources are included, and the change is ready for OpenSpec verification and archive
  - Evidence: all 92 tasks are complete, the previously skipped lifecycle test and full focused suite pass, device/RSM and packaging evidence is recorded, and strict OpenSpec validation reports the change valid.
