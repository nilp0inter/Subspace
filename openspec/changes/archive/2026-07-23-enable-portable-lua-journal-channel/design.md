## Context

Subspace currently has two independent channel execution paths. Built-in providers construct Kotlin runtimes, while installed GitHub packages materialize immutable Lua providers and one isolated Lua actor per channel instance and runtime generation. The completed Lua v1 development contract already supplies detached scalar configuration, lifecycle/readiness/input callbacks, managed background tasks, opaque captured and synthesized audio, transcription, synthesis, deferred playback, structured logs, cancellation, and generation replacement.

The built-in Journal path remains outside that Lua substrate:

```text
builtin:journal
    → JournalBuiltInProvider
    → JournalRuntime
    → JournalStorageCapability
    → ServiceJournalStorageBackend
    → JournalController
    → WAV spool + OGG + transcript + Markdown + recovery
```

The Lua runtime cannot independently reproduce that behavior. It has no user-selected persistent directory authority, filesystem API, stored-recording import/export, wall-clock timestamp, post-input deferred work, or generic operation broker. Its current yielding operations are fixed transcription/synthesis/playback methods whose arguments are encoded into operation labels.

Android directory selection also resolves `ACTION_OPEN_DOCUMENT_TREE` results into raw filesystem paths and requires `MANAGE_EXTERNAL_STORAGE`. Those details cannot define a portable Lua contract: Android SAF exposes document trees through `content://` grants, while a future iOS adapter will use document-picker security-scoped bookmarks and coordinated file-provider access.

This change deliberately keeps `builtin:journal` operational. It adds the generic substrate and a separate external package so both implementations can be exercised side by side before a later change removes the built-in path. The external package is published from `nilp0inter/journal-channel`, installed through the ordinary GitHub package flow, and never bundled or automatically instantiated.

Lua API v1 and manifest v1 are unreleased development contracts with no users. They evolve directly through one clean cutover. The app, fixtures, Debug package, Diagnostics package, and new Journal package target one revised `subspace-lua-v1`; no v2, fallback parser, feature negotiation, or legacy callback/module shape is retained.

## Goals / Non-Goals

**Goals:**

- Add platform-neutral, package-declared user directory-tree mounts whose grants remain opaque to Lua and are bound to one channel instance.
- Add a bounded asynchronous document-tree filesystem API over opaque mount handles and normalized relative paths.
- Add generic opaque-recording import, description, WAV/PCM export, and OGG/Vorbis export operations over mounted storage.
- Let transcription borrow any live opaque recording, including a recording reopened by a managed task during recovery.
- Add authoritative capture wall-clock metadata, immutable runtime instance identity, and terminal-bound deferred post-input tasks.
- Replace string-encoded operation arguments with typed opaque host-operation requests governed by the existing actor/generation lifecycle.
- Implement every Journal domain decision in external Lua: configuration interpretation, layout, metadata, capture commit, derived work, Markdown, cleanup, and recovery.
- Publish and install the Journal package through the generic exact-byte GitHub path and prove independent instances, restart recovery, grant revocation, package replacement, and side-by-side operation with `builtin:journal`.
- Move Debug and Diagnostics packages and all in-tree fixtures through the revised v1 manifest/API contract without versioning the API.
- Design the Lua storage ABI around document-tree semantics that a future iOS adapter can implement without changing packages.

**Non-Goals:**

- No removal, deprecation, alias, or behavior change for `builtin:journal`.
- No automatic installation, bundling, instance creation, configuration copying, active-selection change, or identity rebinding for the external Journal package.
- No shared output root between the built-in and Lua Journal implementations during supported side-by-side evaluation.
- No removal of `MANAGE_EXTERNAL_STORAGE`, `StoragePathResolver`, the raw-path directory picker, the first-run storage gate, legacy Journal seeding, or Kotlin Journal tests in this change.
- No iOS application or storage-adapter implementation.
- No raw Android path, `content://` URI, document ID, iOS URL, bookmark, file descriptor, SDK object, or platform exception exposed to Lua.
- No unrestricted Lua `io`, `os`, package-native module, ambient filesystem, symlink, hard-link, permission-bit, ownership, inode, device, or executable-file API.
- No POSIX atomic-rename guarantee and no requirement that external document providers implement local-filesystem semantics.
- No general binary-string filesystem API, database, network, JSON, compression, archive, file-watch, advisory-lock, or cross-mount operation.
- No Journal-specific host capability, module, operation kind, metadata class, path helper, renderer, recovery worker, or publication branch.
- No guaranteed execution after the foreground service or process stops; the package recovers from durable state at the next runtime start.

## Decisions

### D1. Keep the built-in and external implementations as separate provider identities

This change retains the complete `builtin:journal` path. The external package receives the ordinary repository-derived implementation identity:

```text
builtin:journal
    ≠
github-repository:<nilp0inter/journal-channel repository database ID>
```

The app registers the built-in provider exactly as today. The user explicitly installs the external package and explicitly creates/configures an instance. The host does not reinterpret, copy, migrate, select, or rename an existing built-in instance.

The implementations use separate output roots. The application does not attempt to prove that a SAF tree URI and a raw filesystem path identify the same underlying directory, and it does not coordinate writers across the two storage models. Physical acceptance records the two roots and rejects a run in which they overlap.

**Alternative considered:** remove the built-in provider in the same change. Rejected because the user wants a period of direct comparison before a separately proposed removal.

**Alternative considered:** wrap the built-in Journal backend with a Lua-facing Journal operation. Rejected because Kotlin would retain Journal semantics and the external package would not prove general-purpose portability.

### D2. Evolve one exact pre-release v1 contract by clean cutover

The target remains:

```text
manifestVersion = 1
runtime.luaVersion = "Lua 5.4"
runtime.apiVersion = "subspace-lua-v1"
```

Manifest v1 gains a required `resources` root member. Every accepted package contains exactly the revised root keys. Debug and Diagnostics declare explicit empty resource mounts and publish new immutable package revisions. The new host rejects old release assets that omit the declaration; it does not infer an empty declaration or dispatch an older API shape.

Installed development state from the superseded contract may be cleared or explicitly updated. No compatibility carrier is added for an unreleased shape. Historical GitHub assets remain immutable evidence but are not executable by the revised host.

**Alternative considered:** introduce `subspace-lua-v2`. Rejected because the API is deliberately being built incrementally before any users depend on v1.

**Alternative considered:** make `resources` optional and default it to empty. Rejected because exact shapes and fail-closed validation avoid a permanent second manifest interpretation.

### D3. Declare user-selected mounts separately from scalar configuration

The exact manifest member is:

```json
{
  "resources": {
    "mounts": [
      {
        "id": "output",
        "kind": "directory-tree",
        "access": "read-write",
        "required": true,
        "label": "Journal directory",
        "help": "Directory containing Journal entries and daily Markdown."
      }
    ]
  }
}
```

`resources` contains only `mounts`. A mount declaration contains only `id`, `kind`, `access`, `required`, `label`, and optional `help`. V1 supports exactly `kind="directory-tree"`, `access="read-write"`, and `required=true`. IDs match `[a-z][a-z0-9_]*`, are unique, and are bounded. Labels and help are bounded valid UTF-8. Empty mounts are valid. Initial policy permits at most eight mounts, 64 UTF-8 bytes per ID, 128 bytes per label, and 512 bytes per help string; these policy numbers are not Lua compatibility promises.

A mount is authority, not ordinary package data. Its platform grant is persisted in a host resource-binding store keyed by channel instance, provider identity, and declaration ID. The scalar configuration payload does not contain a path, URI, bookmark, grant token, or serialized mount userdata. Package code refers only to the declaration ID.

The generic channel create/edit surface renders mount declarations next to scalar fields and invokes a host-owned system picker. A required unbound or unavailable mount leaves the instance explicitly unavailable but preserves its scalar configuration and binding record for repair or rollback.

**Alternative considered:** add a string configuration field with a directory control. Rejected because a path string is neither portable authority nor safe proof of a user grant.

**Alternative considered:** put mount userdata inside `configuration.values`. Rejected because configuration remains detached normalized scalar data; resource authority has different persistence, revocation, and serialization semantics.

### D4. Persist opaque mount bindings independently of runtime generations

A binding has the logical host shape:

```text
MountBinding
    channelInstanceId
    implementationId
    declarationId
    kind
    access
    platformGrantBlob
    status
```

`platformGrantBlob` is never included in logs, snapshots, Lua values, catalogue configuration, package stores, exported evidence, or provider objects. Android stores the exact persisted SAF tree URI and granted flags behind this field. A future iOS adapter may store security-scoped bookmark bytes behind the same logical contract.

Bindings outlive runtime generations and package revisions. A compatible provider update retains the binding by implementation identity and declaration ID. Removing or incompatibly changing the declaration makes it dormant/unavailable while preserving it for rollback. Removing the channel instance releases the binding and releases the underlying platform permission only when no remaining binding references the same grant.

At runtime, `subspace.fs.mount("output")` performs a bounded synchronous lookup and returns a private full-userdata handle containing only a state-local random token. The host registry maps that token to the current instance, generation, declaration, and access. The handle is generation-owned, may be retained in Lua upvalues and used by callbacks or managed tasks in that state, is non-serializable, has a locked metatable, and is invalidated on generation close.

The handle does not keep an Android or iOS resource permanently open. Each asynchronous operation resolves and acquires platform access for its own bounded lifetime, performs the operation, and releases access in `finally`/equivalent cleanup.

### D5. Model a capability-mounted document-tree VFS, not POSIX

`subspace.fs` exposes only:

```lua
mount, err = fs.mount(id)
result, err = fs.mkdir(mount, relative_path, options)
result, err = fs.stat(mount, relative_path)
page, err = fs.list(mount, relative_path, options)
result, err = fs.read_text(mount, relative_path, options)
result, err = fs.write_text(mount, relative_path, text, options)
result, err = fs.remove(mount, relative_path, options)
```

`mount` is synchronous and effect-guarded. Every I/O operation yields and is permitted only from `handle_input` or a runtime-managed task. Startup starts recovery with `runtime.spawn`; readiness observes host-supplied capability/resource status and performs no I/O.

Paths use `/` as a virtual separator and are always relative to one mount. For root discovery, only `list` accepts the empty string as an explicit zero-component mount-root selector. Every other operation rejects an empty path. The host rejects absolute paths, empty components, `.`, `..`, NUL, backslash, invalid UTF-8, over-bound component/path sizes, and any operation whose resolution would leave the selected tree. Packages must not rely on case sensitivity or Unicode-normalization distinctions. V1 exposes no symlink API and adapters do not intentionally follow provider aliases outside the granted tree.

`list` returns bounded pages of `{name, kind}` plus an opaque continuation token. Order is unspecified. A token is bound to state, generation, mount, directory, and one listing session and becomes stale after close or terminal pagination failure.

`write_text` supports exactly `mode="create-new"` and `mode="replace"`. Successful `create-new` never knowingly overwrites an existing document. Successful `replace` means the complete supplied UTF-8 text is visible when the operation returns; the contract does not claim crash-atomic replacement across every document provider. There is no append or rename operation in v1.

`remove` deletes one file or one empty directory only. It is not recursive. Package cleanup traverses explicitly under host pagination and operation limits.

The API applies finite host-configured bounds to path components, path bytes, text bytes, page size, concurrent operations, operation duration, and process-wide transferred bytes. Bounds failure occurs before an effect when it can be determined statically; provider-side size growth during a read aborts with `E_TOO_LARGE` and publishes no partial Lua string.

**Alternative considered:** expose stock Lua `io`. Rejected because it grants ambient host paths and assumes local POSIX behavior.

**Alternative considered:** expose platform URIs or URLs. Rejected because it makes the Lua ABI platform-specific and lets packages confuse identifiers with granted authority.

**Alternative considered:** expose opaque per-node handles only. Rejected because deterministic package-owned hierarchy construction is substantially simpler with validated logical relative paths; the mount remains the authority boundary.

### D6. Use SAF directly for new Android mounts while preserving the old raw-path path

The generic resource editor launches `ACTION_OPEN_DOCUMENT_TREE`, takes persistable read/write URI permission, validates that the selected tree supports the requested access, and stores the URI/flags only in the generic binding store. The storage adapter uses `ContentResolver` and `DocumentsContract`/document-provider operations. It never calls `StoragePathResolver` and never turns the tree into `/storage/...`.

This coexists with the built-in Journal editor, which continues resolving its selection to a raw path and continues relying on `MANAGE_EXTERNAL_STORAGE`. Removing that legacy mechanism is deferred until the built-in provider is removed.

The adapter treats provider disappearance, revoked permission, moved/deleted roots, read-only providers, remote-provider delay, and quota exhaustion as ordinary portable outcomes. Generic mount status is exactly `available`, `read-only`, `needs-reauthorization`, or `unavailable`. A user can reselect a required mount without changing the package's scalar configuration or provider identity.

A future iOS adapter maps selection to a security-scoped bookmark, resolves it for each operation, balances `startAccessingSecurityScopedResource`/`stopAccessingSecurityScopedResource`, and coordinates external document access. Those APIs never enter the Lua contract.

### D7. Use a fixed portable filesystem error vocabulary

Expected filesystem failures return `nil, error_table` and never throw across the host boundary. The stable codes are:

```text
E_INVALID_ARGUMENT
E_INVALID_PATH
E_INVALID_CONTEXT
E_CAPABILITY_UNDECLARED
E_MOUNT_UNAVAILABLE
E_REAUTHORIZATION_REQUIRED
E_READ_ONLY
E_NOT_FOUND
E_EXISTS
E_NOT_DIRECTORY
E_IS_DIRECTORY
E_TOO_LARGE
E_NO_SPACE
E_BUSY
E_TIMEOUT
E_CANCELLED
E_CLOSED
E_STALE
E_UNSUPPORTED
E_IO
```

An optional bounded language-neutral `reason` may be returned. It contains no Android exception, URI, document ID, raw path, iOS error domain, URL, bookmark, provider account, or device identity. Unknown platform failures collapse to `E_IO`.

### D8. Add generic recording file operations without exposing bytes

The host injects `subspace.audio` with:

```lua
metadata, err = audio.describe(recording)
result, err = audio.export(recording, mount, relative_path, {
    format = "wav-pcm-s16le" -- or "ogg-vorbis"
})
recording, err = audio.open(mount, relative_path, {
    format = "wav-pcm-s16le"
})
```

`describe` is a bounded synchronous registry lookup returning sample rate, channels, duration milliseconds, and retained PCM bytes. It performs no decoding or I/O and is effect-guarded during module evaluation.

`export` is yielding, borrows rather than consumes the recording, validates the writable mount and destination before encoding, and publishes no successful result until the complete destination is visible. WAV/PCM and OGG/Vorbis are exact v1 format tokens. The host may encode into an app-private bounded temporary file and stream the complete result through the document-provider adapter because the current native OGG encoder requires a real path. Temporary storage is host-owned, quota-bound, cleaned on every terminal path, and never exposed to Lua.

`open` is yielding and initially accepts only `wav-pcm-s16le`. It validates and decodes a bounded file into a new opaque recording owned by the calling managed task or input invocation. Oversized, malformed, unsupported, foreign, stale, or unavailable input creates no userdata.

A generic opaque `Recording` kind replaces the public assumption that every transcribable recording originated in the current capture callback. Live capture injection and `audio.open` both create Recording handles; synthesized audio remains a distinct kind. Transcription accepts Recording. Playback accepts Recording or Synthesized according to its existing consume rules. Export borrows Recording. Loaded recordings remain execution-owned, non-transferable, non-serializable, quota-accounted, and disposed when their execution owner terminates.

**Alternative considered:** return PCM or encoded bytes as Lua strings. Rejected because it duplicates large buffers into the Lua heap and bypasses host audio quotas.

**Alternative considered:** expose `transcribe_file`. Rejected because it fuses storage and transcription around one current use case instead of composing generic recording import with the existing transcription operation.

### D9. Replace encoded operation labels with typed opaque requests

The Rust kernel becomes the validation and ownership boundary for generic host calls. A host module validates Lua arguments, resolves userdata tokens, constructs a bounded `HostOperationRequest` in a per-state registry, and yields only an opaque request token. The yielded label contains no path, text, JSON, audio token, or provider argument.

Logical request kinds are generic:

```text
FS_MKDIR
FS_STAT
FS_LIST
FS_READ_TEXT
FS_WRITE_TEXT
FS_REMOVE
AUDIO_OPEN
AUDIO_EXPORT
TRANSCRIBE
SYNTHESIZE
PLAYBACK
```

The Kotlin dispatcher claims each request exactly once, maps it to generic capability ports/adapters, and returns a typed normalized completion. Large bounded text or binary transfers use explicit JNI strings/byte arrays or host-managed streams rather than concatenated labels. Request payloads cannot be inspected or forged by Lua after admission.

Each request carries state, instance, generation, execution owner, operation ID, declared-capability eligibility, and relevant userdata ownership. Success, validation failure, host failure, deadline, explicit input cancellation, task cancellation, generation revocation, and close race through one atomic terminal gate. Completion resumes Lua only while every owner remains current. Close discards suspended executions without re-entry, releases mounts and temporary files, invalidates new recording tokens, and suppresses late publication.

Existing transcription, synthesis, and playback move through the same broker in the clean cutover; no legacy label parser remains.

### D10. Add authoritative input time and immutable runtime identity

The capture event gains exact `timestamp` data:

```lua
event.timestamp = {
    unix_ms = 1784752965123,
    local_time = {
        year = 2026,
        month = 7,
        day = 22,
        hour = 21,
        minute = 42,
        second = 45,
        millisecond = 123,
        utc_offset_minutes = 120
    }
}
```

The host captures the instant and local offset at input release before invoking Lua. Every value is an integer within documented calendar ranges. Packages do not depend on Android timezone objects or Lua `os`.

`subspace.runtime.INSTANCE_ID` is an immutable string set from the host channel instance identity. Provider and generation identifiers remain host-owned because the Journal package needs stable instance attribution, not lifecycle implementation tokens. The existing event session UUID provides a collision-resistant per-input component for entry IDs.

No general wall-clock callable is needed for the first Journal package. Durable state records use the authoritative capture timestamp and do not require platform time during recovery. A later concrete requirement may add a generic clock without expanding this change.

### D11. Add terminal-bound deferred work

`subspace.runtime.defer(function)` is usable only from the host-managed `handle_input` coroutine. It validates and reserves a managed-task slot synchronously but does not run the function until the input callback terminates with exact `{ok=true}`. If the callback fails, throws, is malformed, is cancelled, or its generation closes before commit, the reservation and closure are discarded without execution.

Once committed, the task is an ordinary generation-owned managed task. It has its own execution owner, may yield in filesystem/audio/transcription operations, cannot use the input invocation's audio userdata, and is cancelled on generation replacement or close. It must reopen any durably exported recording. Task failure is locally logged and does not rewrite the already terminal input status.

The Journal callback first exports the spool and appends a durable pending state, then defers derivation, then returns success. If defer admission returns `E_BUSY`, package code may derive inline before returning or return an application failure while leaving recoverable durable state. Startup always spawns a recovery task, so a process/generation interruption eventually resumes pending work.

**Alternative considered:** allow ordinary `spawn` from input and run immediately. Rejected because the task could race the callback before its durable commit and outlive a failed terminal result.

**Alternative considered:** use a permanently polling startup task. Rejected as the only mechanism because it adds avoidable scans, timer wakeups, and latency for every successful input.

### D12. Keep resource readiness separate from capability eligibility

The revised readiness context is:

```lua
{
    capabilities = {
        ["storage.files"] = "available",
        ["audio.files"] = "available",
        ["audio.transcription"] = "available"
    },
    resources = {
        mounts = {
            output = "available"
        }
    }
}
```

Only declared public capabilities and declared mount IDs appear. Mount states are exactly `available`, `read-only`, `needs-reauthorization`, or `unavailable`. The callback remains synchronous and non-yielding. Every filesystem/audio call rechecks live declaration, mount binding, access, generation, context, and capability state; readiness is not an authorization grant.

Public capability compilation is:

```text
storage.files       → generic mounted-storage eligibility/port
audio.files         → generic audio import/export eligibility/port
audio.transcription → existing transcription eligibility/port
```

Neither compilation nor readiness contains Journal-specific logic.

### D13. Make authoritative Journal state immutable and provider-portable

The external package owns this layout under its `output` mount:

```text
YYYY/
  YYYY-MM/
    YYYY-MM-DD/
      entries/
        <timestamp-and-session-entry-id>/
          states/
            000001-created.json
            000002-capture-finished.json
            000003-encoding-running.json
            000004-encoding-finished.json
            000005-transcription-running.json
            000006-transcription-finished.json
          capture.wav
          recording.ogg
      journal-day-YYYY-MM-DD.md
```

Each state file is a complete bounded JSON snapshot written with `create-new`. The package vendors a bounded pure-Lua JSON module. State sequence numbers are fixed-width and strictly increasing. Recovery lists and validates snapshots, selects the highest valid contiguous sequence, ignores malformed/noncontiguous trailing records, and never treats the replaceable Markdown projection as authoritative.

The input callback writes a created snapshot, exports the WAV spool, writes a capture-finished snapshot, admits deferred derivation, and returns success. A partially copied audio document has no capture-finished snapshot and is classified/cleaned on recovery. Derived work reopens the WAV, writes running/finished/failed snapshots, exports OGG when requested, transcribes when requested, and regenerates day Markdown from authoritative entries. `skipped` is terminal for an unrequested derivative. The WAV is removed only when every requested derivative finished successfully; it is retained after failure for a later retry/recovery.

Daily Markdown uses `replace` because it is a reproducible projection. Process death during replacement cannot corrupt authoritative state; startup regenerates affected days. The package does not depend on provider rename atomicity.

The output configuration is one exact choice:

```text
VOICE
TRANSCRIPT
VOICE_AND_TRANSCRIPT (default)
```

A single choice makes the invalid `saveVoice=false, saveText=false` state unrepresentable without adding cross-field configuration constraints. Lua maps the choice to requested derivative states.

### D14. Publish an external package with no app special cases

The canonical repository is `nilp0inter/journal-channel`. The implementation creates the repository, resolves its positive immutable repository database ID, and places that exact ID in the manifest. Official provenance follows the existing owner database ID `1224006`; it does not imply review, audit, signing, or defect freedom.

The first stable release is `v1.0.0` with exactly one canonical `subspace-channel.zip` asset containing only the revised exact manifest and UTF-8 Lua source modules. The app validates exact local bytes before publication, records repository/release/asset IDs, size, digest, and timestamp, then installs the public bytes through the ordinary package manager. The app contains no production package source, archive, automatic install, automatic instance, repository-name branch, or Journal implementation-ID branch.

Debug `v1.2.0` and Diagnostics `v1.3.0` are republished from their existing repositories with explicit empty resources and the revised module/callback contract. Diagnostics uses `v1.3.0` because its immutable historical `v1.2.0` asset predates required resources and remains untouched. Their behavior remains otherwise unchanged. Published historical assets are never replaced.

Release immutability here is an exact-byte project policy, not a dependency on GitHub's optional immutable-releases setting. Recorded published tags/assets are never replaced; acceptance pins their IDs and SHA-256.

### D15. Verify generic contracts before package behavior

Verification is layered:

1. Manifest/resource validation: exact keys, bounds, duplicates, canonical IDs, supported kind/access, empty declarations, and rejection of the superseded shape.
2. Binding store/editor: selection, persisted SAF permission, restart, update compatibility, rollback, reauthorization, instance removal, and no grant leakage.
3. VFS: path adversaries, tree confinement, create/replace semantics, pagination, UTF-8, bounds, revocation, cancellation, deadlines, provider failures, and no platform leakage.
4. Audio files: WAV open/export, OGG export, staging cleanup, malformed/oversized input, borrow semantics, execution ownership, quotas, cancellation, and stale completion.
5. Actor broker/defer: typed request integrity, no argument labels, exact-once completion, context rules, terminal-bound task start/discard, replacement, close, and late-effect suppression.
6. Revised packages: Debug and Diagnostics exact releases, update path, and preserved behavior under one revised v1.
7. Journal package: all output modes, path/state format, commit order, Markdown, failures, recovery, restart, configuration replacement, grant revocation/repair, and multiple isolated instances.
8. Side-by-side/device: independently named built-in and Lua instances with distinct roots; PTT capture and observable outputs compared without cross-writing or host Journal special cases.

Host contract tests use anonymous/generic fixture packages for storage, audio, broker, and defer behavior. Journal-domain tests live in the external package repository or exercise the exact installed public artifact. Kotlin production and generic fixtures do not call built-in Journal helpers on behalf of Lua.

## Risks / Trade-offs

- **[Risk] Android document providers have weaker or slower behavior than local filesystems.** → Use common-denominator operations, bounded pagination/deadlines, immutable authoritative snapshots, and replaceable projections; test local and provider-backed trees.
- **[Risk] `replace` is not crash-atomic across all providers.** → Journal authoritative state uses create-new immutable snapshots; Markdown is regenerated after interruption.
- **[Risk] SAF permissions are revoked, moved, or become read-only.** → Persist opaque binding status, fail closed with `needs-reauthorization`/`read-only`, preserve package state, and provide generic reselection.
- **[Risk] The native OGG encoder requires a real path while the destination is a document tree.** → Encode in quota-bound app-private temporary storage, stream the completed result through the storage adapter, and clean every terminal path.
- **[Risk] Copying a completed staged artifact to a remote provider is interrupted.** → Publish capture/derivative state only after successful copy; recovery ignores or removes uncommitted artifacts.
- **[Risk] Typed broker and multiple large-payload operations expand JNI complexity.** → Keep one bounded request registry, opaque IDs, explicit payload transport, one terminal gate, and generic conformance tests rather than adding per-domain label grammars.
- **[Risk] Deferred derivation is cancelled by generation replacement after input success.** → Durable pending snapshots precede defer admission; successor startup recovery reopens the spool and resumes work.
- **[Risk] A package captures input audio in a deferred closure.** → Audio remains execution-owned; deferred use fails as foreign/stale, and the Journal package reopens the durable WAV.
- **[Risk] Lua recovery scans a very large tree.** → Hierarchical date layout, paginated listing, bounded task slices, and package-owned incremental state avoid unbounded host responses.
- **[Risk] Built-in and Lua implementations write the same physical directory.** → Supported evaluation requires distinct roots and records them in evidence; no unsafe cross-model identity inference is attempted.
- **[Risk] The clean v1 cutover makes historical Debug/Diagnostics assets unavailable.** → Publish revised immutable releases and update fixtures before final app acceptance; do not preserve an unreleased parser/runtime shape.
- **[Risk] Resource declarations become a second configuration system.** → Keep scalar values in the existing exact configuration contract and restrict resources to host-owned authority with separate binding/revocation semantics.
- **[Trade-off] Built-in all-files permission remains despite the new portable mount path.** → Accepted temporarily because built-in Journal remains operational; its removal is explicitly deferred to the subsequent elimination change.
- **[Trade-off] Pure-Lua JSON is duplicated in the Journal package.** → Accepted to avoid prematurely expanding the host ABI; package-local pure Lua is already supported and portable.

## Migration Plan

1. Define the revised exact manifest/resource domain and validation bounds. Update all in-tree package builders and reject the superseded manifest shape.
2. Add the generic mount-binding store, provider materialization data, host editor projection, SAF selection, persisted permission lifecycle, status, and restart recovery without changing the built-in raw-path picker.
3. Add mount userdata, path validation, `subspace.fs`, generic storage capability ports, and the Android document-tree adapter. Prove confinement, bounds, revocation, and provider failures with generic fixtures.
4. Generalize opaque audio to Recording, add `subspace.audio` describe/open/export, add generic audio-file ports, and route WAV/OGG staging through mounted storage. Move transcription to Recording.
5. Replace fixed string-encoded operation requests with the typed opaque broker and migrate transcription, synthesis, and playback before adding storage/audio request kinds.
6. Add capture timestamp, `runtime.INSTANCE_ID`, readiness resource status, and terminal-bound `runtime.defer`; close cancellation/replacement races.
7. Update and locally validate Debug and Diagnostics against the revised v1, publish their next immutable releases, and update exact-byte fixtures/evidence.
8. Create `nilp0inter/journal-channel`, resolve its repository ID, implement the Lua state machine and all output modes, and prove it locally through generic package installation/materialization.
9. Publish Journal `v1.0.0`, install the public asset, create independently configured instances, and run automated restart, revocation, update, and recovery scenarios.
10. Run physical side-by-side acceptance with distinct roots and record provider identity/digest/generation, output mode, capture terminal ordering, files, transcript, Markdown, recovery, and absence of cross-writing.

Rollback before publishing the app means reverting the complete host change and retaining immutable external release assets as historical artifacts. After publication, app rollback may make revised packages unavailable to the older exact parser; catalogue definitions, installed package bytes, and mount bindings remain preserved but are not reinterpreted. Rolling back a package uses only another revision valid under the revised v1 and never substitutes `builtin:journal`.

## Open Questions

None. The coexistence boundary, external/non-bundled distribution, single evolving v1 contract, capability-mounted document-tree model, Android SAF mapping, future iOS abstraction, filesystem/audio surface, typed broker, deferred work, immutable Journal state, separate roots, publication path, and later built-in removal are fixed by this design.
