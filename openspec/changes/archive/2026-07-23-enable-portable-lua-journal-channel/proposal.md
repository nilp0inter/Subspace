## Why

The current Lua runtime cannot implement the Journal channel independently because packages cannot retain a user-selected directory tree, perform bounded filesystem operations, persist or reopen opaque recordings, or defer durable post-input work. Adding portable low-level APIs now lets an external Lua Journal package exercise the intended plugin architecture alongside the existing `builtin:journal`, while keeping the public contract independent of Android paths and suitable for a later iOS adapter.

## What Changes

- Add package-declared, instance-bound directory-tree mounts with host-rendered selection, opaque persisted grants, generation-owned Lua mount handles, explicit availability, and no platform URI or path exposure.
- Add a bounded yielding `subspace.fs` API over mount handles for directory creation, metadata, paginated listing, UTF-8 reads and writes, and removal using normalized relative document-tree paths and platform-neutral errors.
- Add generic audio-file operations that export opaque recordings as WAV/PCM or OGG/Vorbis into a mount and reopen stored recordings without exposing PCM, file descriptors, or platform locations.
- Generalize transcription from current-capture userdata to any live execution-owned recording userdata.
- Add capture wall-clock metadata, immutable runtime instance identity, and terminal-bound deferred tasks that become runnable only after a successful input callback.
- Replace operation-label argument encoding with an opaque typed host-operation broker capable of mediating filesystem and audio-file operations under existing generation, deadline, cancellation, and quota rules.
- Implement and publish an external, non-bundled Lua Journal package whose Lua code owns configuration policy, paths, immutable metadata records, capture commit, OGG derivation, transcription, Markdown rendering, cleanup, discovery, and restart recovery.
- Keep `builtin:journal`, its Kotlin implementation, catalogue seed, raw-path configuration, all-files permission, bootstrap behavior, and tests operational for side-by-side evaluation. The two implementations use separate storage roots.
- **BREAKING**: Evolve the single unreleased `subspace-lua-v1` and manifest-v1 contracts directly. Resource declarations become part of the exact accepted manifest shape; existing Debug and Diagnostics development packages and fixtures move to the revised v1 contract and are republished without adding API version negotiation or compatibility shims.

## Capabilities

### New Capabilities

- `lua-resource-mounts`: Package declarations, host selection, persistent instance binding, generation handles, availability, reauthorization, update compatibility, and platform-neutral lifecycle for user-selected directory trees.
- `lua-filesystem-api`: Bounded asynchronous document-tree operations, relative-path rules, pagination, completion semantics, errors, cancellation, and revocation exposed through `subspace.fs`.
- `lua-audio-file-api`: Portable opaque-recording open, description, WAV/PCM export, and OGG/Vorbis export operations targeting mounted storage.
- `lua-journal-channel`: Behavior, package identity, configuration, durable state machine, derived artifacts, Markdown projection, recovery, publication, installation, and side-by-side acceptance for the external Lua Journal package.

### Modified Capabilities

- `lua-package-format`: Require exact bounded resource-mount declarations in manifest v1 and reject the superseded shape without inference or compatibility dispatch.
- `lua-channel-provider`: Compile validated resource declarations and generic public capabilities into package-specific providers without executing Lua or adding Journal-specific branches.
- `lua-channel-api`: Add authoritative capture timestamps and terminal-bound deferred post-input work while preserving exact callback results and generation ownership.
- `lua-runtime-api`: Expose immutable instance identity, generic mount/filesystem modules, and opaque typed operation dispatch under the incrementally evolved `subspace-lua-v1` contract.
- `lua-audio-api`: Generalize opaque recording origin and transcription eligibility while retaining execution-owner, generation, quota, and serialization rules.
- `lua-actor-runtime`: Mediate typed generic host requests, deferred-task admission, suspension, resumption, deadlines, cancellation, and close without string-encoded operation arguments.
- `channel-host-capabilities`: Add language-neutral generic mounted-storage and audio-file host ports without exposing Android, filesystem, codec-implementation, or Journal domain objects.
- `channel-runtime-invocation`: Define terminal-bound deferred work and ensure replacement or closure drains, cancels, and suppresses it under the existing per-generation invocation boundary.
- `installed-lua-packages`: Preserve and republish the revised exact manifest declaration through validation, storage, materialization, update, rollback, and restart without trusting cached pre-cutover shapes.

## Impact

- Android application: package validation/domain/store, generic channel editor and resource selection, provider materialization, runtime construction, capability mediation, SAF-backed storage adapter, opaque audio registry, audio codecs, transcription adapter, service composition, and package-management UI.
- Rust Lua actor: manifest-facing modules, mount/audio userdata, path and argument validation, typed operation requests, coroutine suspension/resumption, and deferred-task lifecycle.
- External repositories: create and publish the official Journal package; revise and republish current Debug and Diagnostics development packages against the evolved v1 manifest.
- Existing built-in Journal: retained unchanged as an independent provider and evaluation baseline; its eventual removal, raw-path cleanup, permission cleanup, seed migration, and old-definition policy belong to a later change.
- Portability: Android stores persisted SAF grants behind opaque mount bindings; a future iOS implementation can map the same contract to document-picker security-scoped bookmarks and coordinated file-provider access without changing Lua packages.
