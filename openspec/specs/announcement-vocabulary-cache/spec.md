# Announcement Vocabulary Cache

## Purpose

Define the persistent announcement cache identity, manifest format, WAV integrity verification, mark-and-sweep reconciliation, atomic commits, and diagnostics.

## Requirements

### Requirement: Persistent announcement cache identity is canonical and update-scoped
The system SHALL store only `SystemAnnouncer` system-announcement PCM under `Context.noBackupFilesDir/announcement-cache`, using schema version `1`, an authoritative `manifest.json`, and content-addressed `<lowercase-64-hex-fingerprint>.wav` entry files. The system SHALL include `PackageInfo.lastUpdateTime`, the verified Supertonic model manifest identity, the selected verified voice-style identity, phrase text, and every rendering and output-format setting in each phrase fingerprint. The system SHALL NOT use `versionCode` alone, source timestamps, TTL, LRU, backup-eligible storage, or retained historical generations as cache identity or retention policy.
The system SHALL build the style identity map only from verified Supertonic manifest records whose path starts with `voice_styles/`, keyed by the exact basename after the final `/`; duplicate basenames or malformed hashes SHALL disable persistent caching. For a non-empty vocabulary, an unresolved selected-style basename SHALL produce no disk hits and a `Skipped` commit while in-memory synthesis continues. An empty vocabulary SHALL not require selected-style resolution.

#### Scenario: APK replacement invalidates all prior phrases
- **WHEN** a launch observes a non-zero `PackageInfo.lastUpdateTime` different from the identity recorded in every prior phrase fingerprint, including a same-version debug APK replacement
- **THEN** every prior phrase is a cache miss
- **AND** the prior entry files become sweep candidates after the new manifest is committed
- **AND** the system regenerates the current vocabulary rather than admitting prior-APK PCM

#### Scenario: Rendering input changes invalidate content
- **WHEN** phrase text, selected voice-style SHA-256, verified Supertonic model manifest SHA-256, language, synthesis steps, speed bits, output rate, channel count, bit depth, encoding literal, or schema version differs from a persisted phrase fingerprint
- **THEN** the phrase is a cache miss
- **AND** a newly committed fingerprint is distinct from the old fingerprint
- **AND** the old unreferenced entry is removed during reconciliation

#### Scenario: Equal rendered content shares one entry
- **WHEN** two logical vocabulary keys have equal phrase text and equal package, model, style, language, synthesis, and output-format inputs
- **THEN** both logical keys reference the same content fingerprint
- **AND** the manifest contains one file record for that fingerprint
- **AND** synthesis is invoked once for the equal-text group

### Requirement: Phrase fingerprints and manifests use byte-exact schema version 1
The system SHALL compute the Supertonic manifest identity by hashing canonical bytes consisting of a big-endian 4-byte manifest-version length and UTF-8 version, a big-endian 4-byte file count, and each `ModelSetHash.files` path and raw SHA-256 bytes sorted by unsigned UTF-8 path bytes. The system SHALL compute each phrase fingerprint by hashing, in order, schema version `Int32(1)`, package `lastUpdateTime` `Int64`, phrase UTF-8 length and bytes, raw 32-byte Supertonic manifest hash, raw 32-byte selected-style hash, language UTF-8 length and bytes, `Int32(4)`, `Float.floatToRawIntBits(1.2f)`, `Int32(16000)`, the UTF-8 encoding literal `pcm16le-wav`, `Int32(1)` channel count, and `Int32(16)` bits per sample, with every integer big-endian. The `Int32(4)` field is both the canonical announcement synthesis step count and part of persistent cache identity.
The version-1 `manifest.json` SHALL contain exactly this shape and no additional fields:
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
The system SHALL sort `logicalEntries` by unsigned UTF-8 key bytes and `files` by raw fingerprint bytes before writing, and SHALL require the file-record fingerprint set to equal the distinct logical-entry fingerprint set.

#### Scenario: Golden identity encoding is stable
- **WHEN** the same verified model records, package update identity, phrase, selected style, and four-step render settings are encoded twice
- **THEN** the canonical byte sequences are identical
- **AND** the resulting SHA-256 fingerprints are identical lowercase 64-hex strings
- **AND** changing only the logical announcement key does not change the fingerprint

#### Scenario: Prior higher-step cache entries are invalidated
- **WHEN** a persisted phrase was fingerprinted with a synthesis step count other than four
- **THEN** the four-step render settings SHALL produce a distinct fingerprint
- **AND** the prior phrase SHALL be treated as a cache miss and swept after successful reconciliation
- **AND** the current phrase SHALL be synthesized with exactly four steps

#### Scenario: Malformed identity metadata disables admission
- **WHEN** the package update identity is absent, zero, or unreadable, or a required model/style SHA-256 is absent, malformed, or ambiguous because verified style records duplicate a basename
- **THEN** the system performs in-memory synthesis without loading persisted hits
- **AND** the system does not persist a selected-style phrase
- **AND** bootstrap readiness is not failed solely because persistent identity is unavailable

### Requirement: Valid all-hit and partial-hit hydration preserves complete readiness
The system SHALL load a persisted phrase into the in-memory announcement map only when its current logical mapping, expected fingerprint, file, whole-file hash, WAV metadata, and declared sample count all validate. The system SHALL reuse every valid current phrase, synthesize only missing or invalid phrases, and SHALL require native TTS readiness plus every current vocabulary key to contain non-empty SCO-ready PCM before reporting `AnnouncementResult.Ready`. Persistent hydration SHALL NOT bypass the native engine gate or the existing ready-beep defensive fallback for an unexpected runtime in-memory miss.

#### Scenario: Unchanged vocabulary is an all-hit launch
- **WHEN** the installed APK and all rendering inputs are unchanged and the authoritative manifest contains a valid entry for every current vocabulary key
- **THEN** every phrase is loaded into the in-memory announcement map
- **AND** `TtsSynthesizer.synthesize` is not called
- **AND** announcement precomputation reports `AnnouncementResult.Ready` with all current keys

#### Scenario: Current valid entries form a partial hit
- **WHEN** some current logical keys reference valid persisted PCM and other keys are missing, malformed, empty, or fingerprint-mismatched
- **THEN** valid entries are loaded before rendering misses
- **AND** synthesis is called only for the missing or invalid phrase groups
- **AND** loaded logical keys count as completed progress units before the first miss is rendered

#### Scenario: Hydration does not bypass native readiness
- **WHEN** every current phrase is a valid persisted hit but native STT or TTS has not reported `Ready`
- **THEN** bootstrap remains in loading
- **AND** the dashboard is not shown
- **AND** the existing beep-only fallback remains a defensive runtime behavior rather than a bootstrap success

### Requirement: Manifest and WAV integrity validation is strict and isolated
The system SHALL accept only a version-1 manifest with exactly the declared fields and JSON types: `schemaVersion`, `logicalEntries`, and `files`; logical entries SHALL contain `key` and `fingerprint`; file records SHALL contain `fingerprint`, `file`, `fileSha256`, `sampleRate`, `channelCount`, `bitsPerSample`, and `sampleCount`. Logical entries SHALL be sorted by unsigned UTF-8 key bytes and file records by raw fingerprint bytes when written. The file-record fingerprint set SHALL equal the distinct fingerprint set referenced by logical entries. Each admitted WAV SHALL exist, match its whole-file SHA-256, decode as mono 16-bit PCM at 16 kHz with a positive declared sample count, and contain a non-empty sample array matching that count. An invalid individual entry SHALL become a miss without invalidating otherwise valid entries; a structurally invalid manifest SHALL be discarded.

#### Scenario: One corrupt WAV does not discard other hits
- **WHEN** a structurally valid manifest references two fingerprints and one WAV is missing, truncated, wrong-rate, wrong-channel, wrong-bit-depth, empty, wrong-sample-count, or whole-file-hash-mismatched
- **THEN** only the invalid fingerprint is treated as a cache miss and removed from the active in-memory map
- **AND** the other valid fingerprint remains a hit
- **AND** the invalid artifact is removed as an orphan during reconciliation

#### Scenario: Broken or unsafe manifest references are rejected
- **WHEN** a manifest has an unknown or missing field, wrong JSON type, duplicate key or fingerprint, unsupported schema, broken logical/file reference, or a file name other than the exact `<fingerprint>.wav` basename
- **THEN** the manifest is rejected as a whole
- **AND** its `manifest.json` and entry files are removed during reconciliation
- **AND** the vocabulary is regenerated in memory rather than treated as ready from untrusted data

### Requirement: Cache reconciliation is bounded mark-and-sweep
The system SHALL reconcile in this fixed order: delete `*.tmp`; parse `manifest.json`; if invalid, delete it and all entry files; if valid, delete files not referenced by it; derive current fingerprints; load every valid desired file independently of the old logical-key mapping; synthesize misses; promote new WAVs; atomically replace the manifest; then delete files referenced only by the prior manifest. The system SHALL use one authoritative current manifest and SHALL remove abandoned temporary files, corrupt artifacts, and every entry not referenced by the successfully committed current manifest. The system SHALL NOT add TTL, LRU, backup generations, or multiple retained manifests.

#### Scenario: Unreferenced entries are swept after a successful mark
- **WHEN** a valid current manifest is committed while `entries/` contains an unreferenced lowercase-64-hex WAV and abandoned temporary files
- **THEN** all temporary files are deleted during reconciliation
- **AND** the unreferenced WAV is deleted
- **AND** every file referenced by the successfully committed manifest remains available for the next launch

#### Scenario: A renamed key reuses its content
- **WHEN** a new vocabulary maps a different logical key to a fingerprint whose validated WAV remains referenced by the prior manifest
- **THEN** the validated WAV is loaded independently of the prior logical-key name
- **AND** the new logical mapping is committed without resynthesizing that content
- **AND** entries no longer referenced by the new manifest are swept

### Requirement: Empty vocabulary commits an empty cache state
The system SHALL treat an empty current vocabulary as a valid complete result. It SHALL clear the in-memory announcement map, commit an empty manifest when package/model identity and storage are available, sweep all prior entry files, and return `AnnouncementResult.Ready(emptySet())` without synthesis. Resolving a selected voice-style fingerprint SHALL NOT be required for this empty-vocabulary operation, while the existing native TTS readiness gate SHALL remain unchanged.

#### Scenario: Empty vocabulary removes all prior entries
- **WHEN** `precompute` receives an empty vocabulary and the cache root is usable
- **THEN** the in-memory announcement map is empty
- **AND** an empty logical-entry and file manifest is atomically committed
- **AND** every prior entry and temporary file is swept without any synthesis call

#### Scenario: Empty vocabulary remains ready when style selection is unavailable
- **WHEN** the vocabulary is empty but the selected voice-style path cannot be resolved to a unique verified hash
- **THEN** the empty manifest is still committed if package/model identity and storage are available
- **AND** precomputation returns `AnnouncementResult.Ready(emptySet())`
- **AND** the native TTS readiness requirement is still enforced by bootstrap

### Requirement: Commit is atomic, cancellation-safe, and nonfatal to readiness
The system SHALL write each new entry to `entries/<fingerprint>.wav.tmp`, reopen and validate it against the intended `RecordedPcm`, hash the entire finalized WAV, and promote it with same-filesystem `ATOMIC_MOVE` and `REPLACE_EXISTING`. It SHALL check coroutine cancellation immediately before every entry promotion and immediately before manifest replacement. If a write, validation, promotion, or required atomic move fails, it SHALL retain the old authoritative manifest, best-effort delete newly promoted entries not referenced by that manifest, delete temporary files, and report a nonfatal persistence failure. If cancellation is observed, it SHALL perform the same cleanup and rethrow `CancellationException` instead of returning a commit result. A successful complete in-memory rendering SHALL remain ready after a `Skipped` or `Failed` cache commit.

#### Scenario: Complete synthesis atomically replaces the manifest
- **WHEN** every current logical key has non-empty PCM and all new WAVs pass decode and hash validation
- **THEN** each entry is atomically promoted before the new manifest is atomically replaced
- **AND** the committed manifest references exactly the validated current logical mapping and file set
- **AND** the commit reports `Written` or `Unchanged` without exposing a partial manifest

#### Scenario: Write failure rolls back to the old manifest
- **WHEN** an entry write, reopen validation, hash, or atomic promotion fails after a prior manifest is valid
- **THEN** the prior manifest remains authoritative
- **AND** newly promoted entries not referenced by that prior manifest are best-effort deleted
- **AND** temporary files are deleted and the commit reports `Failed(reason)` without failing bootstrap if memory is complete

#### Scenario: Cancellation cannot publish a partial generation
- **WHEN** coroutine cancellation is observed immediately before an entry promotion or immediately before manifest replacement
- **THEN** no canceled generation becomes authoritative
- **AND** the prior manifest remains authoritative
- **AND** cancellation is rethrown after cleanup so the owning bootstrap attempt can join it

### Requirement: Cache I/O and selected-style failures degrade to synthesis
The system SHALL treat directory creation, manifest read/parse, file read, WAV decode, and cache cleanup failures as cache misses or nonfatal persistence failures. For a non-empty vocabulary with unavailable package/model/style identity, it SHALL skip disk hydration and persistence while continuing normal in-memory synthesis. A cache persistence failure after successful synthesis SHALL NOT convert complete in-memory readiness into `AnnouncementResult.Failed`; a synthesis failure SHALL preserve the existing failed announcement and bootstrap recovery behavior, and stale PCM SHALL NEVER be played merely because regeneration failed.

#### Scenario: Cache read failure regenerates phrases
- **WHEN** the cache directory or manifest cannot be read or parsed
- **THEN** the loader returns zero persisted hits after best-effort cleanup
- **AND** the announcer synthesizes the current vocabulary in memory
- **AND** bootstrap does not fail solely because cache I/O failed

#### Scenario: Persistence failure leaves complete memory ready
- **WHEN** all current phrases synthesize successfully but the cache cannot create, write, or atomically replace its files
- **THEN** the in-memory map contains every current non-empty SCO PCM
- **AND** precomputation returns `AnnouncementResult.Ready`
- **AND** the cache commit is reported as `Failed(reason)` rather than failing bootstrap

#### Scenario: Regeneration failure excludes stale audio
- **WHEN** a persisted phrase is invalidated and its replacement synthesis fails
- **THEN** the failed logical key is absent from the ready in-memory map
- **AND** the old invalidated PCM is not played
- **AND** announcement precomputation reports `AnnouncementResult.Failed` and bootstrap enters recovery

### Requirement: Announcement progress and diagnostics are exact and launch-local
The system SHALL serialize each `precompute` call independently of playback, clear the in-memory map first, preserve vocabulary iteration order, group misses by exact text in first-occurrence order, and count validated disk hits as completed logical-key units. Before each unique-text synthesis it SHALL emit `Rendering(completed = readyLogicalKeyCount, total, currentKey = firstKeyInGroup)`; after installing non-empty PCM for every key in a group it SHALL increment completed by that group's logical-key count. If no misses exist, it SHALL transition directly from `WaitingForTts` to `Ready` without `Rendering`. It SHALL emit exactly one non-persistent Android log per `precompute` from a `finally` path under tag `SystemAnnouncer`, with `ANNOUNCEMENT_CACHE_SUMMARY hits=<logical-hit-count> misses=<initial-logical-miss-count> syntheses=<unique-synthesis-call-count> commit=<unchanged|written|skipped|failed> outcome=<ready|failed|cancelled>`.

#### Scenario: All hits report zero synthesis and no rendering stage
- **WHEN** every current logical key is hydrated from a valid persisted entry
- **THEN** progress reports all logical keys complete without a `Rendering` state
- **AND** no synthesis call occurs
- **AND** exactly one summary is logged as `ANNOUNCEMENT_CACHE_SUMMARY hits=7 misses=0 syntheses=0 commit=unchanged outcome=ready` for the current seven-key vocabulary

#### Scenario: Duplicate-text misses count logical keys
- **WHEN** two or more missing logical keys share exact phrase text and appear in a group after the hydrated hits
- **THEN** one synthesis call is made for that text group
- **AND** the `Rendering` state names the first key in the group and starts at the hit count
- **AND** completed progress increases by the number of logical keys in the group after non-empty PCM is installed for all of them

#### Scenario: Failure and cancellation summaries are complete
- **WHEN** synthesis fails or `precompute` is canceled after observing some hits, misses, and synthesis calls
- **THEN** exactly one summary is logged with the observed counts, `commit=skipped`, and `outcome=failed` or `outcome=cancelled`
- **AND** cancellation rethrown `CancellationException`
- **AND** the failed group is excluded from completed progress

#### Scenario: Cache-write failure reports ready without hiding the outcome
- **WHEN** all seven current phrases synthesize successfully but persistence fails while writing or replacing the manifest
- **THEN** exactly one summary is logged as `ANNOUNCEMENT_CACHE_SUMMARY hits=0 misses=7 syntheses=7 commit=failed outcome=ready`
- **AND** precomputation remains `AnnouncementResult.Ready` with all seven in-memory entries
- **AND** the next launch may regenerate the entries because no failed manifest was published

### Requirement: Canceled attempts cannot commit after retry
The system SHALL make bootstrap attempt ownership structured: prerequisite and core preparation work, including STT/TTS async children, SHALL be children of one tracked `attemptJob`; a retry SHALL coalesce while active, cancel the prior attempt and `cancelAndJoin` it before discarding controllers or starting its replacement, and cache promotion checks SHALL occur before shared-directory mutation. A canceled or stale attempt SHALL NOT replace the authoritative cache manifest after its replacement attempt begins.

#### Scenario: Retry waits for cancellation-ignoring synthesis
- **WHEN** a prior attempt is synthesizing with a blocking implementation that ignores cancellation until it returns and a retry is requested
- **THEN** the retry job waits for `prior.cancelAndJoin()` before discarding controllers
- **AND** the replacement attempt does not touch the shared cache directory before that join completes
- **AND** the prior attempt cannot commit its generated entries after the replacement attempt starts

#### Scenario: Concurrent retries are coalesced
- **WHEN** multiple retry requests arrive while one retry job is active
- **THEN** subsequent requests return without creating another replacement job
- **AND** only the joined replacement attempt may commit the current manifest
- **AND** canceling the attempt clears both tracked attempt and retry references
