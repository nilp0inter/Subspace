## Why

System-announcement PCM is regenerated serially on every service start even though the seven-phrase vocabulary and rendering inputs are normally unchanged, making announcement rendering the primary avoidable bootstrap delay. Persisting validated generated PCM removes that repeated work while deterministic invalidation and mark/sweep cleanup prevent stale audio and unbounded cache growth.

## What Changes

- Add a per-phrase, content-addressed announcement PCM cache under Android `noBackupFilesDir`, with one authoritative manifest and no retained historical generations.
- Reuse validated phrases, synthesize only missing or invalid phrases, and deduplicate phrases with identical text and rendering inputs.
- Invalidate cached phrases when the installed APK changes or when vocabulary text, selected voice style, verified Supertonic model identity, synthesis settings, output format, or cache schema changes.
- Validate manifest structure, whole-file SHA-256, WAV format, sample rate, and non-empty PCM before admitting an entry to bootstrap readiness; treat invalid cache data as a miss.
- Commit generated entries and the current manifest atomically, remove temporary, corrupt, orphaned, and no-longer-referenced files, and keep cache I/O failures nonfatal after complete in-memory synthesis.
- Preserve native TTS readiness and complete-vocabulary bootstrap gates while seeding announcement progress from disk hits and preventing a canceled bootstrap attempt from committing after retry.
- Emit one launch-local cache summary for focused unit and physical-device verification; do not persist arbitrary TTS/STT content or change live announcement voice selection.

## Capabilities

### New Capabilities

- `announcement-vocabulary-cache`: Defines persistent phrase identity, hit/miss reuse, integrity validation, commit/rollback behavior, APK-update invalidation, and bounded mark/sweep cleanup for system-announcement PCM.

### Modified Capabilities

- `app-bootstrap`: Extends core readiness, observed announcement progress, and retry cleanup requirements to account for disk-hydrated phrases and prior-attempt cancellation/join behavior.

## Impact

- `SystemAnnouncer` gains persistent-cache hydration, selective synthesis, exact progress accounting, and one cache-summary diagnostic.
- `PttForegroundService` supplies `noBackupFilesDir`, package-update identity, and verified Supertonic model/style hashes.
- `BootstrapCoordinator` consolidates prerequisite checking and core preparation into one structured, retry-safe attempt.
- New cache filesystem/manifest code reuses `JournalWavWriter`, `WavPcmReader`, `ModelVerifier`, SHA-256, and same-filesystem atomic moves; no new runtime dependency is required.
- `rsm-audio-navigation` remains behaviorally unchanged: announcements still play precomputed SCO PCM and retain the ready-beep fallback for an unexpected in-memory miss.
