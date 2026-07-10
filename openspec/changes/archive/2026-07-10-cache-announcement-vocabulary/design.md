## Context

Startup readiness currently depends on `SystemAnnouncer.precompute()` finishing the complete system-announcement vocabulary. `PttForegroundService.buildVocabulary()` supplies the current seven logical keys, while `SystemAnnouncer` serially calls `TtsSynthesizer.synthesize`, converts each result to 16 kHz SCO PCM16, and retains the recordings only in a process-local `ConcurrentHashMap`. `BootstrapCoordinator.prepareCore()` waits for this rendering before publishing `BootstrapState.Ready`; every service restart therefore repeats the same Supertonic work.

This design adds a disposable, persistent, per-phrase cache for only those system announcements. The cache is owned by the announcement path, not by `TtsSynthesizer`, whose implementations must not persist text or audio. The cache is derived data and is stored in `Context.noBackupFilesDir` so it survives ordinary relaunches without entering backup data. It is content-addressed and has one authoritative current manifest; stale, corrupt, unreachable, and abandoned artifacts are removed through reconciliation rather than retained as historical generations.

The implementation crosses `AnnouncementPcmCache`, `SystemAnnouncer`, `PttForegroundService`, and `BootstrapCoordinator`. `ModelVerifier` supplies already-verified Supertonic model/style hashes, `JournalWavWriter` writes the existing PCM16 WAV representation, and `WavPcmReader` supplies the existing decoder. The existing native TTS readiness gate, complete-vocabulary readiness predicate, announcement timeout, SCO playback, and ready-beep fallback remain in force. `rsm-audio-navigation` remains behaviorally unchanged.

## Goals / Non-Goals

**Goals:**

- Reuse validated persisted PCM when the installed APK and all rendering inputs are unchanged, without invoking `TtsSynthesizer.synthesize` for a hit.
- Synthesize only current logical misses and deduplicate misses with identical phrase text and identical rendering inputs.
- Define byte-exact, deterministic identity for package update, verified Supertonic model, selected voice style, phrase text, synthesis settings, output format, and schema version.
- Require strict manifest structure and independent whole-file/WAV/sample validation before admitting a hit to the in-memory announcement map.
- Commit new content and the current logical mapping with same-filesystem atomic moves, rollback on persistence failure, and cancellation checks that prevent a canceled attempt from publishing files.
- Keep the cache bounded to the current manifest: remove temporary files, abandoned entries, corrupt artifacts, and entries not referenced by the successfully committed current manifest.
- Preserve complete in-memory readiness when persistence is unavailable or fails after successful synthesis.
- Make disk-hydrated hits observable as completed announcement work, with exact rendering progress and one deterministic cache-summary diagnostic per `precompute` call.
- Make bootstrap retry structured: a replacement attempt must join the canceled prior attempt before discarding shared controllers or touching the shared cache directory.
- Preserve the current runtime behavior that `announce()` uses only hydrated/generated in-memory PCM and plays the ready beep when an unexpected runtime miss occurs.

**Non-Goals:**

- Persisting arbitrary user TTS/STT content, channel audio, or any data outside `SystemAnnouncer` system-announcement PCM.
- Changing model acquisition, model verification, native TTS readiness, native STT readiness, controller requirements, announcement timeout, or optional integration readiness.
- Bypassing native TTS readiness because a disk entry exists.
- Live voice-style switching or regeneration after a diagnostic TTS voice change following bootstrap.
- TTL, LRU, multiple retained generations, backup-eligible `filesDir` storage, lossy compression, a compatibility reader, migration aliases, or a schema-version compatibility layer. A schema mismatch is regenerated.
- Adding `fsync` latency for this disposable derived data or claiming protection against sudden power loss.
- Modifying application code in this artifact-creation batch; this document records the implementation contract only.

## Decisions

### 1. Cache API and ownership

Add `app/src/main/java/dev/nilp0inter/subspace/audio/AnnouncementPcmCache.kt` with these exact public symbols:

```kotlin
data class AnnouncementCacheIdentity(
    val packageLastUpdateTime: Long,
    val supertonicManifestSha256: String,
    val voiceStyleSha256ByFileName: Map<String, String>,
)

data class AnnouncementRenderSettings(
    val voiceStylePath: String,
    val lang: String,
    val totalSteps: Int,
    val speed: Float,
    val scoRate: Int,
)

sealed interface AnnouncementCacheCommitResult {
    data object Unchanged : AnnouncementCacheCommitResult
    data object Written : AnnouncementCacheCommitResult
    data object Skipped : AnnouncementCacheCommitResult
    data class Failed(val reason: String) : AnnouncementCacheCommitResult
}

class AnnouncementPcmCache(
    rootDirectory: File,
    identity: AnnouncementCacheIdentity,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(
        vocabulary: Map<String, String>,
        settings: AnnouncementRenderSettings,
    ): Map<String, RecordedPcm>

    suspend fun commit(
        vocabulary: Map<String, String>,
        settings: AnnouncementRenderSettings,
        recordingsByKey: Map<String, RecordedPcm>,
    ): AnnouncementCacheCommitResult
}
```

`AnnouncementPcmCache` serializes `load` and `commit` with an internal `Mutex`; the `ioDispatcher` confines filesystem and hashing work. `load` never propagates cache I/O or parse failures. A directory or manifest failure returns zero hits after best-effort cleanup. An invalid individual WAV removes only its fingerprint and preserves valid hits for other fingerprints. `commit` requires every current logical key to have non-empty PCM; incomplete input returns `Failed(reason)` and does not change the authoritative manifest.

Change construction to `SystemAnnouncer(synthesizer: TtsSynthesizer, persistentCache: AnnouncementPcmCache? = null)`, while retaining the existing `precompute(vocabulary, voiceStylePath, scoRate)` signature. `null` disables disk caching for that announcer. Persistence deliberately remains outside `TtsSynthesizer` and does not change its no-network/no-persistence contract.

Alternatives rejected: putting persistence in each synthesizer implementation would duplicate storage policy and violate the synthesizer contract; replacing the API with a synchronous cache would block the caller and make failure/cancellation behavior harder to isolate; exposing cache files directly to `SystemAnnouncer` would duplicate manifest and rollback logic.

### 2. Android identity and service wiring

`PttForegroundService.constructAnnouncer()` creates the cache root as:

```text
File(noBackupFilesDir, "announcement-cache")
```

It reads `PackageInfo.lastUpdateTime`, using the API-33 `PackageInfoFlags` overload on API 33 and newer and the deprecated integer-flags overload on API 31–32. A missing, zero, or failed value disables persistent caching for that announcer. `versionCode`, source timestamps, and a manually bumped renderer revision are not substitutes. The last-update value is included in every phrase fingerprint, so replacing the installed APK invalidates every prior entry even when a same-version debug APK is installed.

The service builds `voiceStyleSha256ByFileName` only from verified Supertonic manifest records whose path starts with `voice_styles/`. Each key is the exact basename after the final `/`; each hash is normalized to lowercase and must be exactly 64 hexadecimal characters. A duplicate basename or malformed hash disables persistent caching. The Supertonic manifest identity is derived from the already verified `ModelVerifier` data and does not rehash ONNX files.

For a non-empty `precompute`, the selected style is resolved by exact `File(voiceStylePath).name`. If its unique style hash is absent, `load` yields no hits and `commit` returns `Skipped`; synthesis and in-memory readiness continue normally. An empty vocabulary needs no selected-style fingerprint: if package/model identity and storage are available, it still commits an empty manifest and sweeps old entries.

Alternatives rejected: `cacheDir` is OS-evictable and would not provide repeat-launch reuse; `filesDir` could place disposable audio into backups; `versionCode` does not distinguish same-version APK replacement; hashing local model files at startup duplicates expensive verification work already performed by `ModelVerifier`; accepting ambiguous basenames would make style identity nondeterministic.

### 3. Byte-exact model and phrase identity

Define cache schema version `1`.

#### Supertonic manifest identity

Compute `supertonicManifestSha256` as SHA-256 over this canonical byte stream:

1. A 4-byte big-endian length followed by the UTF-8 bytes of the Supertonic manifest version.
2. A 4-byte big-endian file count.
3. Every `ModelSetHash.files` record, sorted lexicographically by unsigned UTF-8 path bytes, encoded as:
   - a 4-byte big-endian path length;
   - the UTF-8 path bytes;
   - the raw 32 bytes decoded from the record's lowercase SHA-256.

Duplicate paths or malformed hashes are rejected. The identity is based on the verified Supertonic manifest records, not on rehashing already verified ONNX assets.

#### Phrase input fingerprint

For each logical phrase, compute a lowercase 64-hex SHA-256 over fields in exactly this order:

1. `schemaVersion`: Int32 `1`.
2. `packageLastUpdateTime`: Int64.
3. Phrase text: Int32 UTF-8 byte length, then UTF-8 bytes.
4. Raw 32-byte Supertonic manifest hash.
5. Raw 32-byte selected-style hash.
6. Language: Int32 UTF-8 byte length, then UTF-8 bytes.
7. `totalSteps`: Int32.
8. `Float.floatToRawIntBits(speed)`: Int32.
9. `scoRate`: Int32.
10. Encoding literal `pcm16le-wav`: Int32 UTF-8 byte length, then UTF-8 bytes.
11. Channel count: Int32 `1`.
12. Bits per sample: Int32 `16`.

Every integer is big-endian. The logical key is intentionally excluded: equal text under one identity and render setting maps to the same content-addressed file, `<lowercase-64-hex-fingerprint>.wav`.

The `SystemAnnouncer` settings for current synthesis are exactly `AnnouncementRenderSettings(voiceStylePath, "en", 20, 1.2f, scoRate)`. The fingerprint records the raw float bits, not a decimal rendering, to avoid representation ambiguity. Text, style, model, language, steps, speed, output rate, encoding, channel count, bit depth, package update, and schema changes therefore produce misses rather than unsafe reuse.

Alternatives rejected: using the logical key as the file identity prevents content deduplication and makes key renames expensive; concatenating unlength-prefixed fields permits ambiguity; little-endian or locale-dependent serialization would not provide a stable cross-implementation golden encoding; using only a model version or APK version omits verified content and same-version replacement boundaries.

### 4. Authoritative manifest and strict codec

The cache root contains one `manifest.json` and an `entries/` directory containing content-addressed WAV files. Version 1 has exactly these fields and JSON types:

```json
{
  "schemaVersion": 1,
  "logicalEntries": [
    {"key": "string", "fingerprint": "lowercase-64-hex"}
  ],
  "files": [
    {
      "fingerprint": "lowercase-64-hex",
      "file": "<fingerprint>.wav",
      "fileSha256": "lowercase-64-hex",
      "sampleRate": 16000,
      "channelCount": 1,
      "bitsPerSample": 16,
      "sampleCount": 1
    }
  ]
}
```

`logicalEntries` are sorted by unsigned UTF-8 key bytes before writing. `files` are sorted by raw fingerprint bytes. The codec rejects the whole manifest for missing or unknown fields, wrong JSON types, unsupported schema, malformed hashes, duplicate logical keys, duplicate fingerprints, a filename other than the exact fingerprint WAV basename, or broken references. The set of file-record fingerprints must equal the distinct set referenced by `logicalEntries`. Positive `sampleCount` is required for a file record.

A structurally valid manifest can remain useful when one referenced WAV is missing or corrupt: validation removes only that fingerprint, and other records remain eligible hits. A malformed manifest is not partially trusted: it is deleted with all entry files during reconciliation. Path traversal is rejected by the exact `entries/<fingerprint>.wav` filename rule and by refusing any filename that is not the expected basename.

Alternatives rejected: retaining unknown fields would create an accidental forward-compatibility promise and weaken byte/schema strictness; accepting partial broken references as hits could report bootstrap success without complete PCM; storing one WAV per logical key would defeat identical-text reuse; multiple manifests or generations would make mark/sweep ownership ambiguous.

### 5. WAV admission and reconciliation

Use `JournalWavWriter` for PCM16 little-endian WAV creation and `WavPcmReader` for decoding. The reader's broad format support is narrowed by cache validation: an entry is admitted only when the file exists, the manifest's whole-file SHA-256 matches, decoding succeeds, the decoded WAV is mono, 16-bit PCM at exactly 16,000 Hz (the current requested `scoRate`), the decoded sample array is non-empty, and its length equals the manifest's declared positive `sampleCount`. The returned `RecordedPcm` must be non-empty and correspond to the validated samples.

Reconciliation order is fixed:

1. Delete every abandoned `*.tmp` file.
2. Parse `manifest.json`.
3. If the manifest is invalid, delete it and all entry files; there is no trusted prior mapping.
4. If valid, delete entry files not referenced by it.
5. Derive the current phrase fingerprints from the current vocabulary and render settings.
6. Load every valid desired file record independently of the old logical-key mapping, so a renamed key can reuse unchanged content.
7. Synthesize misses in `SystemAnnouncer` and collect complete in-memory recordings.
8. Promote newly written WAVs.
9. Atomically replace the manifest.
10. Delete files referenced only by the prior manifest.

This order makes old logical-key names irrelevant to content reuse and bounds crash orphans until the next load. Mark/sweep is based on the successfully committed current manifest; no TTL, LRU, or retained historical generation is introduced.

### 6. Atomic commit, cancellation, and rollback

For each new fingerprint, write to `entries/<fingerprint>.wav.tmp`, finalize the writer, reopen the temporary WAV, and require decoded samples to equal the intended `RecordedPcm`. Require mono PCM16 at the requested output rate and a positive declared sample count, then compute SHA-256 over the entire finalized WAV before promotion.

Promote entries and the manifest on the same filesystem using:

```text
Files.move(temp, final, ATOMIC_MOVE, REPLACE_EXISTING)
```

The manifest is written to `manifest.json.tmp` and atomically replaced into `manifest.json`. Check coroutine cancellation immediately before every entry promotion and immediately before manifest replacement. If atomic move is unsupported, or any write, validation, or promotion fails, retain the old valid authoritative manifest, best-effort delete every newly promoted fingerprint not referenced by that manifest, delete all temporary files, and return `AnnouncementCacheCommitResult.Failed(reason)`. Cleanup failures are not fatal and are retried as orphan cleanup on the next `load`. If the old manifest was already structurally invalid, reconciliation has deleted it and its entries; no invalid manifest is resurrected.

The protocol guarantees consistency across coroutine cancellation and ordinary process death, not sudden power loss. No `fsync` is added for disposable PCM. A torn or lost WAV/manifest after sudden power loss fails whole-file or WAV validation and is regenerated. `commit` returns:

- `Unchanged` only when the validated manifest already exactly represents the desired logical mapping and file set; no file or manifest is rewritten.
- `Written` after successful entry and manifest replacement.
- `Skipped` when persistence is disabled for the selected style or identity (including a missing selected style hash), or when the announcer has no persistent cache.
- `Failed(reason)` for a nonfatal persistence failure.

Complete valid in-memory PCM remains sufficient for `AnnouncementResult.Ready` after `Skipped` or `Failed`.

Alternatives rejected: non-atomic writes could expose a manifest pointing at partial files; writing directly to final names would make process death and cancellation visible as corrupt entries; retaining a new manifest after entry promotion failure could orphan or misrepresent content; `fsync` would add startup latency to disposable data without changing the miss/regenerate safety property. Cancellation checks alone are insufficient when native synthesis ignores cancellation, so bootstrap also joins the prior attempt before replacement.

### 7. Announcement hydration, synthesis, progress, and fallback

`SystemAnnouncer.precompute` is serialized by a mutex separate from playback `jobMutex`. It clears the in-memory map first, waits for the existing native TTS readiness condition, constructs the exact render settings, and seeds the map only with validated cache hits. Blocking synthesis remains off the main thread as required by the existing `TtsSynthesizer` contract. Clearing first ensures stale PCM is never played merely because regeneration fails.

The supplied vocabulary iteration order is preserved. Cache misses are grouped by exact text in first-occurrence order; each group retains its first logical key as the progress/current-key representative. `total` is the vocabulary size and `readyLogicalKeyCount` is the number of logical keys seeded by valid hits or installed after group success.

- With no misses, transition directly from `WaitingForTts` to `AnnouncementResult.Ready` and emit no `Rendering` state. All-hit launches make zero synthesis calls.
- Before each unique-text synthesis, emit `Rendering(completed = readyLogicalKeyCount, total, currentKey = firstKeyInGroup)`.
- On successful non-empty synthesis, convert samples with `TtsAudio.toScoPlayback`, install the PCM for every logical key in the group, and increment completed by that group's logical-key count (not by one synthesis call).
- On `ModelNotReady`, `EmptyText`, empty converted PCM, or synthesis failure, return the existing `AnnouncementResult.Failed` shape with the first key in the failed group and exclude that group from completed. Do not commit a partial result and do not retain stale PCM.
- After all logical keys are ready, call `persistentCache.commit(vocabulary, settings, recordingsByKey)`. A successful commit, `Skipped`, or `Failed` persistence result does not change an otherwise complete in-memory `Ready` result. A synthesis failure sets commit diagnostic state to `skipped` and retains the existing bootstrap recovery behavior.
- An empty vocabulary clears the map, performs no synthesis, commits an empty manifest when identity/storage are available, sweeps all prior entries, and returns `AnnouncementResult.Ready(emptySet())`. The coordinator still invokes `precompute` only after native TTS initialization, so the native readiness gate is not bypassed.
- `announce()` reads only the hydrated in-memory map. If a key is unexpectedly absent or empty at playback time, it retains the existing defensive ready-beep fallback; that fallback cannot satisfy bootstrap readiness.

Emit exactly one non-persistent Android log from a `finally` path for each `precompute`, under tag `SystemAnnouncer`, with this exact message shape:

```text
ANNOUNCEMENT_CACHE_SUMMARY hits=<logical-hit-count> misses=<initial-logical-miss-count> syntheses=<unique-synthesis-call-count> commit=<unchanged|written|skipped|failed> outcome=<ready|failed|cancelled>
```

`hits` counts logical keys loaded from disk, not distinct fingerprints. `misses` is the initial logical miss count before synthesis. `syntheses` counts unique-text synthesis calls and includes a failed call. A synthesis failure reports `commit=skipped`; a cache-write failure after successful synthesis reports `commit=failed outcome=ready`; cancellation reports the counts observed before cancellation with `commit=skipped outcome=cancelled`, then rethrows `CancellationException`. The summary is launch-local and is not persisted.

A diagnostic TTS voice-style change after bootstrap does not trigger live announcement regeneration. The next bootstrap resolves the selected style and its fingerprint as usual.

Alternatives rejected: counting one completion per synthesis would under-report duplicate logical keys; emitting `Rendering` for all-hit launches would report work that did not occur; retaining the old map on failure would permit stale audio playback; treating the beep as a rendered phrase would weaken the complete-vocabulary gate; persisting summary logs would turn diagnostics into another cache lifecycle.

### 8. Structured bootstrap attempt and retry ownership

Refactor prerequisite checking and core preparation into one structured suspend `runAttempt()` owned by `attemptJob`. STT/TTS `async` children are created inside `runAttempt`'s `coroutineScope`, never from the outer service scope. Native TTS readiness and the existing announcement timeout continue to gate `BootstrapState.Ready`.

The ownership sequence is exact:

1. `launchAttempt()` returns without work when `attemptJob?.isActive == true` or `retryJob?.isActive == true`; otherwise it assigns `attemptJob = scope.launch { runAttempt() }`.
2. `retry()` returns when `retryJob` is active. Otherwise it captures the current `attemptJob` as `prior`.
3. It creates one lazy replacement job whose body executes `prior?.cancelAndJoin()`, then `coreInit.discardControllers()`, then `runAttempt()`.
4. It assigns that same job to both `retryJob` and `attemptJob`, registers completion cleanup that clears each reference only when it still refers to that exact job (referential equality), and starts the job.
5. `cancelAttempt()` cancels both references and clears both references.
6. `startBootstrap()`, `refreshPrerequisites()`, and successful model acquisition call only `launchAttempt()`.

A retry therefore coalesces concurrent retry requests and cannot discard controllers or begin a replacement cache commit while the prior attempt's blocking synthesis is still running. The prior attempt's cache promotion checks provide the final cancellation boundary; `cancelAndJoin` ensures the replacement cannot race the shared directory. Existing failure states, retry-safe recovery, controller construction, and final `isCoreReady()` checks remain otherwise unchanged.

Alternatives rejected: canceling without joining permits a cancellation-ignoring native synthesis to commit after replacement starts; assigning separate jobs for retry and attempt loses ownership and allows a second retry to bypass the join chain; launching STT/TTS children in the service scope leaks them beyond the attempt; discarding controllers before prior completion races shared native/cache state.

## Risks / Trade-offs

- **Disposable persistence and power loss:** No `fsync` means sudden power loss can leave a torn artifact. Whole-file hash and WAV/sample validation convert it to a miss on the next launch, at the cost of regenerating the phrase.
- **Blocking native synthesis:** A synthesizer that ignores coroutine cancellation can delay retry completion. The explicit `cancelAndJoin` sequence prevents corruption but may delay replacement readiness until the native call returns.
- **Cache failures add no readiness failure:** Directory, manifest, atomic-move, and write errors can cause repeated synthesis and diagnostic `commit=failed`, but complete in-memory PCM still reaches `Ready`. This intentionally favors startup correctness over persistence availability.
- **Strict schema rejection:** Unknown fields and schema changes discard reuse rather than risking an incompatible interpretation. The trade-off is a one-time regeneration after any schema change.
- **APK invalidation breadth:** `lastUpdateTime` invalidates every phrase on package replacement, including same-version debug installs. This is conservative and may repeat synthesis even when rendered bytes would otherwise match.
- **Style identity availability:** Missing or ambiguous verified style metadata disables disk reuse for that announcer. Synthesis continues, avoiding a false hit at the cost of launch latency.
- **Manifest replacement window:** A process death between entry promotion and manifest replacement can leave bounded orphans until the next reconciliation. The authoritative manifest prevents unreferenced files from becoming hits.
- **Current vocabulary changes:** Added, removed, or renamed logical keys are represented by the next manifest. Content-addressed files permit renamed keys to reuse matching text; removed keys are swept after successful commit.
- **Progress semantics differ from synthesis-call count:** Duplicate text advances by logical-key count while diagnostics report unique synthesis calls. This is deliberate but requires consumers/tests to use the specified fields separately.

## Migration Plan

There is no compatibility migration from the old process-local map because it has no durable representation. On the first launch after implementation, create `noBackupFilesDir/announcement-cache`, synthesize current misses, and write schema-version-1 `manifest.json` plus content-addressed entries. If the root cannot be created or identity cannot be read, disable persistence for that launch and use the existing in-memory path without failing bootstrap.

A pre-existing cache with an unsupported schema is not read or migrated: reconciliation deletes its manifest and entry files and regenerates the current vocabulary. A valid version-1 cache is reconciled in place. A package replacement changes `lastUpdateTime`, making all previous entries misses; after successful synthesis the new manifest is committed and prior entries are swept. The subsequent unchanged launch hydrates all current entries and returns `Unchanged` without rewriting.

The operational lifecycle is therefore:

1. First generation: seven current logical keys are misses; seven unique texts synthesize, commit `Written`, and become `Ready`.
2. Unchanged relaunch: seven logical hits, zero misses, zero syntheses, `Unchanged`, `Ready`.
3. Same-version APK replacement: update identity changes; seven misses synthesize, commit `Written`, and old entries become sweep candidates.
4. Next unchanged relaunch: seven hits, zero misses, zero syntheses, `Unchanged`, `Ready`.
5. Any injected unreferenced lowercase-64-hex WAV under `entries/` is removed by reconciliation while the seven referenced files and manifest remain.

No application-code migration, backup restore, historical-generation retention, or data conversion is required.

## Open Questions

None.
