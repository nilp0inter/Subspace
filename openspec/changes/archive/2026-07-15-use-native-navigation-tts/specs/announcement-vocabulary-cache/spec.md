## REMOVED Requirements

### Requirement: Persistent announcement cache identity is canonical and update-scoped

**Reason**: The persistent announcement PCM cache under `Context.noBackupFilesDir/announcement-cache` is deleted in its entirety. Navigation announcements are synthesized on demand by the installed offline Android TTS engine at request time, so no canonical cache identity, content-addressed entry files, authoritative `manifest.json`, or update-scoped fingerprint inputs are needed.

**Migration**: Remove `AnnouncementPcmCache`, the `manifest.json` authority, content-addressed `<fingerprint>.wav` entry files, and all identity inputs (`PackageInfo.lastUpdateTime`, Supertonic model manifest identity, selected voice-style identity, phrase text, and rendering/output-format settings) that fed phrase fingerprints. Navigation phrase text is resolved from the current channel catalogue at request time and fed directly to the native TTS renderer.

### Requirement: Phrase fingerprints and manifests use byte-exact schema version 1

**Reason**: The byte-exact fingerprint and manifest schema existed solely to content-address persisted PCM entries. With the cache removed, no fingerprint computation, manifest serialization, or golden encoding is performed.

**Migration**: Remove the schema-version-1 hashing logic, the `manifest.json` shape definition, and all canonical byte-ordering rules. No replacement identity scheme is introduced; transient PCM is never persisted.

### Requirement: Valid all-hit and partial-hit hydration preserves complete readiness

**Reason**: Hydration loaded persisted PCM into the in-memory announcement map to avoid resynthesis. On-demand native synthesis renders the current phrase at request time, so there is no persisted hit to hydrate, no partial hit to complete, and no in-memory announcement map to populate during bootstrap.

**Migration**: Remove the hydration path, the in-memory announcement map, and the `AnnouncementResult.Ready` precomputation state. Bootstrap readiness for announcements is replaced by a proven offline Android navigation voice gate; runtime synthesis produces transient PCM that is discarded after playback.

### Requirement: Manifest and WAV integrity validation is strict and isolated

**Reason**: Strict manifest and WAV validation existed to admit or reject persisted cache artifacts. With no persistent cache, there is no manifest to validate and no WAV to decode or hash-check.

**Migration**: Remove all manifest field/type validation, logical/file reference checks, WAV decode, whole-file SHA-256 verification, and orphan-removal logic. No on-disk integrity validation remains.

### Requirement: Cache reconciliation is bounded mark-and-sweep

**Reason**: Mark-and-sweep reconciliation managed the lifecycle of persisted entry files against the current manifest. With the cache removed, there are no entry files to sweep, no manifest to replace, and no temporary files to delete.

**Migration**: Remove the fixed-order reconciliation pipeline, the `*.tmp` deletion step, the manifest-replacement logic, and the prior-manifest-referenced-file deletion step. No reconciliation or sweep logic remains.

### Requirement: Empty vocabulary commits an empty cache state

**Reason**: The empty-vocabulary path committed an empty manifest and swept prior entries to represent a valid complete cache result. With the cache removed, there is no manifest to commit and no prior entries to sweep; an empty vocabulary simply produces no announcement.

**Migration**: Remove the empty-vocabulary manifest commit, the prior-entry sweep, and the `AnnouncementResult.Ready(emptySet())` return. An empty catalogue at request time results in no synthesis and no playback.

### Requirement: Commit is atomic, cancellation-safe, and nonfatal to readiness

**Reason**: The atomic, cancellation-safe commit protocol published a consistent manifest after entry promotion. With no persistent cache, there are no temporary WAV writes, no entry promotions, and no manifest replacement to make atomic or cancellation-safe.

**Migration**: Remove `entries/<fingerprint>.wav.tmp` writes, reopen validation, same-filesystem `ATOMIC_MOVE`/`REPLACE_EXISTING` promotion, pre-promotion and pre-manifest cancellation checks, and the rollback-to-old-manifest logic. Transient PCM is held in memory only for the duration of one playback.

### Requirement: Cache I/O and selected-style failures degrade to synthesis

**Reason**: Cache I/O failures and missing style identity were tolerated by falling back to in-memory synthesis while keeping the cache in a degraded state. With the cache removed, there is no cache I/O to fail and no selected-style identity to resolve for persistence; native on-demand synthesis is the only path.

**Migration**: Remove all cache-read-failure regeneration, persistence-failure reporting, and stale-PCM exclusion logic. The native TTS renderer's own failure and recovery behavior replaces these paths; there is no `AnnouncementResult.Failed` cache state.

### Requirement: Announcement progress and diagnostics are exact and launch-local

**Reason**: The `precompute` progress stage and `ANNOUNCEMENT_CACHE_SUMMARY` diagnostics reported eager vocabulary rendering status and cache commit outcomes. Eager complete-vocabulary announcement rendering is removed from bootstrap, so there is no `precompute` call, no `Rendering` progress state, and no cache summary to emit.

**Migration**: Remove the `precompute` entry point, the `Rendering(completed, total, currentKey)` progress emission, the `WaitingForTts`-to-`Ready` transition, and the `ANNOUNCEMENT_CACHE_SUMMARY` log line. Bootstrap no longer has an announcement-rendering progress stage.

### Requirement: Canceled attempts cannot commit after retry

**Reason**: This requirement's cache-specific guarantee prevented a canceled bootstrap attempt from publishing a stale cache manifest after its replacement attempt began. With the persistent and in-memory announcement cache removed, there is no manifest to commit, no cache promotion check to race, and no shared cache directory mutation to order against retry.

**Migration**: Remove only the cache-publication-specific guarantees: the requirement that cache promotion checks occur before shared-directory mutation, and the requirement that a canceled or stale attempt SHALL NOT replace the authoritative cache manifest. The general `BootstrapCoordinator` `attemptJob` tracking, retry coalescing, and `cancelAndJoin` before discard remain required by the `app-bootstrap` capability for native engine and controller retry, and SHALL NOT be removed here.