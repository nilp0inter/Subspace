## 1. Revised manifest v1 and resource declarations

- [x] 1.1 Add strict manifest-domain models for exact `resources.mounts` declarations and finite field/count bounds.
- [x] 1.2 Extend manifest parsing to require `resources`, reject unknown/duplicate keys, and reject the superseded omitted-resources shape without changing `subspace-lua-v1`.
- [x] 1.3 Validate canonical mount IDs, kinds, access modes, required flags, labels, duplicates, and package capability allowlist entries.
- [x] 1.4 Add `storage.files` and `audio.files` capability compilation without adding package-specific capability IDs.
- [x] 1.5 Carry immutable validated resource declarations through package revision, installed snapshot, provider descriptor, and runtime construction models.
- [x] 1.6 Update package archive builders and all in-tree fixtures to emit explicit resource declarations, including empty mount arrays.
- [x] 1.7 Smoke-install revised empty-resource and mounted-resource fixtures and confirm historical omitted-resource fixtures are rejected before publication.

## 2. Generic mount binding persistence and editor model

- [x] 2.1 Define language-neutral mount declaration, binding, status, compatibility, and typed unavailability domain models.
- [x] 2.2 Implement the repository-persistent binding store keyed by channel instance, provider revision compatibility, and declaration ID.
- [x] 2.3 Make binding-store writes transactional and preserve the active binding on failed selection, validation, update, or rollback.
- [x] 2.4 Implement update compatibility rules that retain only matching mount ID/kind/access bindings and never retarget incompatible grants.
- [x] 2.5 Implement removal/replacement cleanup so stale grants remain unavailable to runtimes and are released only under explicit lifecycle policy.
- [x] 2.6 Project mount declarations and portable status through the generic provider catalogue/configuration editor model.
- [x] 2.7 Add generic directory-tree selection actions keyed by configuration owner and mount declaration rather than scalar field paths.
- [x] 2.8 Preserve the built-in Journal `DirectoryField` and raw-path picker unchanged beside the new mount editor path.
- [x] 2.9 Smoke-create two package instances with separate mount selections, restart the app/service, and confirm independent restored bindings.

## 3. Android SAF mount adapter

- [x] 3.1 Launch `ACTION_OPEN_DOCUMENT_TREE` with the exact requested read/write grant flags from generic mount selection.
- [x] 3.2 Persist the returned URI permission with `takePersistableUriPermission` and record only opaque URI/flag bytes in the binding store.
- [x] 3.3 Validate selected-tree reachability and requested access before atomically replacing an existing binding.
- [x] 3.4 Map persisted-grant and provider state to `available`, `read-only`, `needs-reauthorization`, and `unavailable` without exposing Android details.
- [x] 3.5 Implement reauthorization and picker-cancellation flows that preserve the last valid binding until successful replacement.
- [x] 3.6 Implement grant release for explicit instance/binding removal without releasing grants still referenced by another valid binding.
- [x] 3.7 Ensure the new adapter never invokes `StoragePathResolver`, constructs `/storage/...` paths, or depends on `MANAGE_EXTERNAL_STORAGE`.
- [x] 3.8 Smoke-select local and provider-backed document trees, revoke permissions, repair them, and verify portable status transitions across restart.

## 4. Capability-mounted document-tree VFS

- [x] 4.1 Define the platform-neutral mounted-storage port for mount lookup, `mkdir`, `stat`, paginated `list`, UTF-8 read/write, and nonrecursive `remove`.
- [x] 4.2 Implement generation-owned opaque mount leases bound to one instance, declaration, access mode, platform grant, and revocation source.
- [x] 4.3 Implement canonical mount-relative component parsing with UTF-8, component-count, component-size, and total-path bounds.
- [x] 4.4 Reject empty/dot/dot-dot components, NUL, slash/backslash injection, absolute/platform paths, and cross-mount traversal.
- [x] 4.5 Implement `mkdir` create-versus-existing semantics without recursive ambient traversal.
- [x] 4.6 Implement portable `stat` results containing only bounded name, kind, size, and optional modified Unix time.
- [x] 4.7 Implement deterministic paginated directory listing and opaque mount/directory/generation-bound continuation tokens.
- [x] 4.8 Implement bounded strict-UTF-8 `read_text` with explicit `max_bytes` and no partial success.
- [x] 4.9 Implement `write_text` `create-new` and provider-independent complete-on-success `replace` semantics with bounded staging/cleanup.
- [x] 4.10 Implement nonrecursive `remove` and reject nonempty-directory deletion.
- [x] 4.11 Normalize provider failures to the fixed portable filesystem vocabulary without URI, path, document ID, exception, or provider leakage.
- [x] 4.12 Enforce per-operation/generation/process counts, bytes, pagination, deadlines, cancellation, close, and late-completion suppression.
- [x] 4.13 Smoke-run every VFS operation through both local-document and provider-backed SAF trees, including cancellation and permission revocation.

## 5. Opaque typed host-operation broker and Lua filesystem module

- [x] 5.1 Replace encoded yielded-operation argument labels with bounded immutable `HostOperationRequest` kinds and payloads in the Rust state registry.
- [x] 5.2 Yield only opaque request identity and reject duplicate, foreign, stale, cancelled, closed, or unknown request claims before effects.
- [x] 5.3 Extend JNI/Kotlin transport with typed request dispatch and normalized completion without serializing large arguments into labels.
- [x] 5.4 Implement exact-once terminal gates carrying state, generation, execution owner, capability, mount lease, and operation identity.
- [x] 5.5 Reserve and inject `subspace.fs` while preventing package source from shadowing any `subspace.*` module.
- [x] 5.6 Implement synchronous `fs.mount(id)` validation and generation-owned opaque Mount userdata.
- [x] 5.7 Implement yielding `fs.mkdir`, `stat`, `list`, `read_text`, `write_text`, and `remove` argument/result validation.
- [x] 5.8 Enforce load-time effect rejection, callback/task context rules, declared `storage.files`, access modes, and live lease revalidation.
- [x] 5.9 Reject Mount userdata through serialization, logging, callback results, error data, and cross-state/generation use.
- [x] 5.10 Smoke-execute a non-Journal Lua fixture that uses all filesystem operations and confirm actor work continues while I/O is suspended.

## 6. Generic Recording file operations

- [x] 6.1 Generalize opaque captured audio to origin-neutral Recording userdata while preserving unforgeability and existing capture behavior.
- [x] 6.2 Extend audio registry quotas and ownership for task-owned stored Recording values without permitting execution transfer.
- [x] 6.3 Implement bounded synchronous `audio.describe(recording)` with portable media metadata only.
- [x] 6.4 Implement a language-neutral audio-file capability port for WAV/PCM open and WAV/OGG export through a live mount.
- [x] 6.5 Implement strict bounded mono PCM WAV decoding that publishes a Recording only after complete validation and quota admission.
- [x] 6.6 Implement WAV/PCM export that borrows Recording ownership and publishes only a complete authorized destination.
- [x] 6.7 Reuse the generic OGG/Vorbis encoder for export without exposing Journal policy, native diagnostics, or platform paths.
- [x] 6.8 Implement quota-bound app-private codec staging and cleanup on success, error, timeout, cancellation, revocation, and generation close.
- [x] 6.9 Reserve and inject `subspace.audio` with exact `describe`, `open`, and `export` validation/context/capability rules.
- [x] 6.10 Allow transcription and export to borrow capture-origin or opened Recording values owned by the current execution.
- [x] 6.11 Preserve playback's atomic consume-on-admission semantics and dispose all unconsumed Recording values at owner termination.
- [x] 6.12 Smoke capture/export/reopen/transcribe/export a Recording through Lua and confirm foreign-task, stale, malformed, and oversized cases fail without leaks.

## 7. Runtime time, identity, readiness, and deferred work

- [x] 7.1 Add authoritative capture `timestamp` with Unix milliseconds and matching bounded local calendar/UTC-offset fields.
- [x] 7.2 Expose immutable `subspace.runtime.INSTANCE_ID` without generation, actor, provider credential, or platform identity.
- [x] 7.3 Add exact `resources.mounts` status to readiness context while keeping capability eligibility and live effect authorization separate.
- [x] 7.4 Implement synchronous `runtime.defer(function)` reservation only in the current host-managed input coroutine.
- [x] 7.5 Keep deferred functions dormant until exact input SUCCESS is committed, then create a distinct managed-task execution owner.
- [x] 7.6 Discard reservations on application failure, malformed result, throw, cancellation, timeout, retirement, close, or unsuccessful terminal delivery.
- [x] 7.7 Reject inherited input audio in deferred tasks while allowing generation-owned Mount upvalues to pass live revalidation.
- [x] 7.8 Integrate released deferred tasks with task quotas, scheduling, operation ownership, failure containment, cancellation, and predecessor draining.
- [x] 7.9 Update callback context matrices so input permits `defer` and file/audio yields but not `spawn`, sleep, or raw escaping yield.
- [x] 7.10 Smoke an input that commits durable data then defers work, and prove no deferred effect occurs before terminal success or after failed input.

## 8. Revised official Debug and Diagnostics packages

- [x] 8.1 Update exact Debug package sources/fixtures to the revised v1 manifest with explicit empty resources and unchanged runtime behavior.
- [x] 8.2 Update exact Diagnostics package sources/fixtures to the revised v1 manifest with explicit empty resources and unchanged runtime behavior.
- [x] 8.3 Build deterministic source-only `subspace-channel.zip` assets and inspect archive structure, manifest declarations, identity, and bounds.
- [x] 8.4 Publish stable policy-immutable Debug `v1.2.0` and Diagnostics `v1.3.0` releases with exactly one canonical asset each.
- [x] 8.5 Record resolved repository IDs, owner ID `1224006`, asset metadata, and SHA-256 hashes from the exact published bytes.
- [x] 8.6 Update app dependency fixtures and exact-release installation checks without adding live network access to runtime tests.
- [x] 8.7 Smoke update, rollback, removal, independent instances, timer/log behavior, and restart for both revised packages.

## 9. External Journal Lua package

- [x] 9.1 Create `nilp0inter/journal-channel`, resolve its positive immutable repository database ID, and establish deterministic source-only packaging.
- [x] 9.2 Declare the required read-write `output` mount, `storage.files`, `audio.files`, `audio.transcription`, and exact `output_mode` choice schema.
- [x] 9.3 Implement package-local bounded canonical JSON encode/decode without adding a generic host JSON API.
- [x] 9.4 Implement authoritative timestamp-derived directory layout, entry IDs, filenames, and immutable fixed-width create-new state snapshots in Lua.
- [x] 9.5 Implement input commit ordering: initial snapshot, WAV export, capture-finished snapshot, defer admission, then exact success.
- [x] 9.6 Implement VOICE derivation by reopening the WAV and exporting OGG/Vorbis with running/finished/failed state transitions.
- [x] 9.7 Implement TRANSCRIPT derivation by reopening the WAV and invoking generic transcription with running/finished/failed transitions.
- [x] 9.8 Implement VOICE_AND_TRANSCRIPT ordering, terminal-state decisions, retryable failure recording, and raw-WAV cleanup only after all requested success.
- [x] 9.9 Implement deterministic daily Markdown discovery, ordering, transcript rendering, relative OGG links, and replaceable projection regeneration.
- [x] 9.10 Implement bounded idempotent startup recovery for interrupted capture, running derivatives, retained WAV retries, abandoned artifacts, and changed-day regeneration.
- [x] 9.11 Implement readiness, structured logging, cancellation, grant-revocation, and generation-replacement behavior using only generic APIs.
- [x] 9.12 Smoke all output modes, malformed/trailing state, interrupted export/transcription/Markdown, retry, cleanup, and restart using package-local tests.
- [x] 9.13 Build and inspect deterministic `subspace-channel.zip`, then publish stable non-draft/non-prerelease `v1.0.0` with exactly that asset.
- [x] 9.14 Record Journal repository/owner/release/asset identity and SHA-256 from the exact published bytes.

## 10. Application integration and end-to-end acceptance

- [x] 10.1 Install the exact published Journal asset through the generic validator/store and register its repository-derived provider without special dispatch.
- [x] 10.2 Create external Journal instances through the normal catalogue/editor and bind output trees through the generic resource UI.
- [x] 10.3 Verify scalar configuration and mount replacement each create an atomic fresh runtime generation with predecessor authority revoked.
- [x] 10.4 Verify package update/rollback preserves compatible bindings, rejects incompatible declarations, and never rewrites provider/instance identity.
- [x] 10.5 Smoke external Journal capture end to end through real PTT input, mounted storage, deferred work, OGG, transcription, Markdown, and restart recovery.
- [x] 10.6 Smoke two external Journal instances with distinct mounts and modes and prove actor, state, file, readiness, and recovery isolation.
- [x] 10.7 Smoke side-by-side `builtin:journal` and external Journal with physically distinct roots and prove there is no cross-writing or host Journal capability use.
- [x] 10.8 Add generic manifest/resource, binding-store/editor, VFS, typed-broker, audio-file, ownership, and defer lifecycle contract tests after the smoke paths work.
- [x] 10.9 Add exact-package installation/update/rollback tests and end-to-end Journal package tests against the published immutable fixtures.
- [x] 10.10 Run focused Rust, Kotlin/JVM, and Android instrumentation suites covering every changed contract and repair all failures.
- [x] 10.11 Search production Kotlin/Rust for Journal concepts reachable from the external provider and confirm only generic package presentation data remains.
- [x] 10.12 Confirm the complete built-in Journal path, catalogue seed, bootstrap, raw-path picker, all-files permission, recovery, behavior, and tests remain unchanged.
- [x] 10.13 Capture physical-device evidence for each Journal output mode, restart recovery, grant revocation/repair, multiple instances, and built-in/external coexistence.
- [x] 10.14 Record exact app revision, package identities/digests, mount-root distinction, generations, terminal ordering, artifacts, and failure/recovery observations.
- [x] 10.15 Run the repository build and complete test suite with bounded timeouts after focused and physical acceptance passes.
