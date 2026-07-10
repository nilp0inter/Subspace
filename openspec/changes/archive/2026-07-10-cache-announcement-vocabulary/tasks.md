## 1. Cache Identity and Persistent Store

- [x] 1.1 Add `AnnouncementCacheIdentity`, `AnnouncementRenderSettings`, `AnnouncementCacheCommitResult`, and `AnnouncementPcmCache` in `app/src/main/java/dev/nilp0inter/subspace/audio/AnnouncementPcmCache.kt` with the exact schema-version-1 signatures from `design.md`, an injectable IO dispatcher, and mutex-serialized `load`/`commit` operations.
- [x] 1.2 Implement canonical big-endian Supertonic-manifest and phrase-fingerprint encoding, lowercase SHA-256 normalization, unsigned UTF-8 ordering, verified `voice_styles/` basename mapping, and missing/duplicate/malformed identity rejection exactly as specified.
- [x] 1.3 Implement strict version-1 `manifest.json` parsing and deterministic writing, including exact fields/types, duplicate and unknown-field rejection, referential-integrity checks, exact fingerprint filenames, sorted logical/file records, and whole-WAV-file hashes.
- [x] 1.4 Reuse `JournalWavWriter` and `WavPcmReader` to implement per-fingerprint temporary WAV creation, decoded-sample equality validation, mono PCM16/rate/sample-count admission, and content-addressed entry loading.
- [x] 1.5 Implement fixed-order reconciliation, all-hit `Unchanged`, empty-manifest cleanup, same-filesystem atomic entry/manifest replacement, cancellation checks, pre-manifest rollback, temporary/orphan/corrupt/stale entry cleanup, and nonfatal `Skipped`/`Failed` commit outcomes.
- [x] 1.6 Delegate `AnnouncementPcmCacheTest.kt` to the Tester agent; cover golden model/fingerprint/manifest encoding, all/partial hits, identical-text reuse, every semantic invalidator, missing/duplicate style metadata, empty vocabulary, strict manifest failures, every WAV integrity failure, unchanged no-rewrite, mark/sweep, rollback, and cancellation before entry or manifest promotion.

## 2. Android Cache Identity and Service Wiring

- [x] 2.1 Add a pure manifest-to-`AnnouncementCacheIdentity` builder that hashes the verified `ModelVerifier.SUPERTONIC_DIR` version plus sorted path/hash records without rehashing model files, and returns cache-disabled for duplicate paths/basenames, malformed hashes, or invalid package identity.
- [x] 2.2 In `PttForegroundService.constructAnnouncer()`, read `PackageInfo.lastUpdateTime` with API-appropriate package-manager overloads, construct `File(noBackupFilesDir, "announcement-cache")`, and inject `AnnouncementPcmCache`; fall back to `SystemAnnouncer(..., persistentCache = null)` when identity or storage setup is unavailable.
- [x] 2.3 Extend the focused cache tests for identity construction and compile both API 31–32 and API 33+ package lookup branches without adding Android, serialization, or storage dependencies.

## 3. Announcement Hydration and Selective Synthesis

- [x] 3.1 Update `SystemAnnouncer` construction to accept `persistentCache: AnnouncementPcmCache? = null` and serialize `precompute` with a mutex independent of playback `jobMutex`; clear the in-memory map before each attempt.
- [x] 3.2 Hydrate validated logical hits with `AnnouncementRenderSettings(voiceStylePath, "en", 20, 1.2f, scoRate)`, preserve vocabulary order, group misses by exact text in first-occurrence order, synthesize each group once, and map non-empty SCO PCM to every group key.
- [x] 3.3 Implement exact progress semantics: all-hit `WaitingForTts` directly to `Ready`, hit-seeded completed counts, first group key as `currentKey`, logical-key-count increments after group success, failed-group exclusion, and empty-vocabulary `Ready(emptySet())` with an empty cache commit.
- [x] 3.4 Commit only a complete current map; keep complete in-memory readiness after `Skipped` or `Failed`, retain synthesis failure/recovery and ready-beep fallback behavior, and never restore invalidated stale PCM.
- [x] 3.5 Emit exactly one `SystemAnnouncer` `ANNOUNCEMENT_CACHE_SUMMARY` from `finally` with logical hits/misses, unique synthesis calls, commit outcome, and `ready|failed|cancelled`; report canceled work as `commit=skipped outcome=cancelled` and rethrow `CancellationException`.
- [x] 3.6 Delegate extensions to `SystemAnnouncerTest.kt` to the Tester agent; cover all-hit zero synthesis/no rendering, partial-hit progress, duplicate-text counts, group failures, empty vocabulary, nonfatal commit results, stale exclusion, concurrent `precompute`, and exactly one ready/failed/canceled summary.

## 4. Structured Bootstrap Retry Ownership

- [x] 4.1 Refactor `BootstrapCoordinator.checkPrerequisites()` and `prepareCore()` into one tracked suspend `runAttempt()` and create STT/TTS `async` children inside its coroutineScope, preserving all existing state transitions, timeouts, readiness checks, and failure diagnostics.
- [x] 4.2 Add `retryJob` and the exact lazy replacement sequence from `design.md`: coalesce active retries, capture and `cancelAndJoin` the prior attempt, discard controllers only after join, assign one replacement to both tracked references, clear references by identity on completion, and make bootstrap/refresh/model-success paths use only `launchAttempt()`.
- [x] 4.3 Update `cancelAttempt()` to cancel and clear both tracked jobs, and verify a cancellation-ignoring blocking synthesis observes cancellation before any entry/manifest promotion and cannot commit after replacement starts.
- [x] 4.4 Delegate `BootstrapCoordinatorTest.kt` extensions to the Tester agent; cover structured child cancellation, retry coalescing, join-before-discard, no replacement progress before handoff, and stale-attempt commit exclusion.

## 5. Focused Verification and Device Acceptance

- [x] 5.1 From the repository root, run `nix develop --no-write-lock-file -c gradle :app:testDebugUnitTest --tests 'dev.nilp0inter.subspace.audio.AnnouncementPcmCacheTest' --tests 'dev.nilp0inter.subspace.audio.SystemAnnouncerTest' --tests 'dev.nilp0inter.subspace.service.BootstrapCoordinatorTest'`, then run `nix develop --no-write-lock-file -c gradle :app:compileDebugKotlin`; resolve every focused failure.
- [x] 5.2 Verify `nix develop --no-write-lock-file -c adb devices` reports `B02PTT-FF01` as `device`, install the debug APK, remove only `run-as dev.nilp0inter.subspace`'s `no_backup/announcement-cache`, clear logcat, launch the activity, and capture `ANNOUNCEMENT_CACHE_SUMMARY hits=0 misses=7 syntheses=7 commit=written outcome=ready`.
- [x] 5.3 Force-stop and relaunch the unchanged APK; require `hits=7 misses=0 syntheses=0 commit=unchanged outcome=ready` and verify the manifest and seven referenced WAVs remain unchanged.
- [x] 5.4 Run `nix develop --no-write-lock-file -c gradle installDebug` again to replace the same-version APK; require the next launch to report `hits=0 misses=7 syntheses=7 commit=written outcome=ready`, followed by `7/0/0/unchanged/ready` on the next unchanged relaunch.
- [x] 5.5 Inject one unreferenced lowercase-64-hex `.wav` under the cache `entries/` directory, relaunch, and use `run-as` to prove reconciliation removed it while preserving `manifest.json` and all seven referenced files; capture summaries with `nix develop --no-write-lock-file -c adb logcat -d -s SystemAnnouncer:I '*:S'` and record timing only as evidence without inventing a pass threshold.
