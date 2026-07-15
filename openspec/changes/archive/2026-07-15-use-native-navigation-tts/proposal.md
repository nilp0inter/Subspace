## Why

Supertonic needs 2–4 seconds to render each navigation phrase, so generating the current seven-phrase announcement vocabulary adds roughly 14–28 seconds to the first boot after installation or cache invalidation. Android's installed offline TTS engine can render these short system phrases on demand while Subspace retains exact target-device routing through its existing app-owned PCM playback path.

## What Changes

- Add a navigation-only Android `TextToSpeech` renderer that selects and proves a deterministic installed English voice declared not to require a network connection, requests no network-specific synthesis feature, synthesizes each requested phrase to transient audio, normalizes it to 16 kHz mono PCM16, and never uses engine-owned audible playback.
- Add a first-class offline navigation voice prerequisite. A missing engine, missing/incomplete offline voice, failed voice selection, failed synthesis probe, or probe timeout keeps bootstrap in `NeedsSetup` and exposes an Android TTS settings/install action.
- Keep Parakeet and Supertonic initialization as hard bootstrap gates, retain the successfully probed Android TTS instance for runtime use, and remove eager complete-vocabulary announcement rendering from bootstrap.
- Resolve navigation phrase text from the current channel catalogue at request time and apply strict latest-wins ownership across synthesis, recovery, and active navigation playback.
- Preserve exact target-RSM ownership proof and output-device selection by feeding transient `RecordedPcm` through `HostAudioCoordinator`, `ModePlaybackRouteResolver`, and the existing app-owned `AudioTrack` with `setPreferredDevice`.
- Make host playback cancellation terminal and teardown-ordered: canceling an owning caller stops its exact playback, waits for `AudioTrack` cleanup, releases the route, and clears only the matching coordinator owner before the replacement proceeds.
- Permit one bounded Android TTS reinitialization, offline-voice probe, and retry of only the newest pending announcement after a runtime engine failure; transition out of `Ready` if recovery cannot restore the hard gate.
- **BREAKING** Remove the persistent and in-memory announcement PCM cache, its manifest/fingerprints/WAV reconciliation, cache diagnostics, `AnnouncementResult` precomputation state, ready-beep cache fallback, and announcement-rendering progress stage.

## Capabilities

### New Capabilities

- `native-navigation-tts`: Defines offline Android voice discovery and proof, transient PCM synthesis and normalization, latest-wins generation ownership, bounded runtime recovery, and renderer lifecycle.

### Modified Capabilities

- `announcement-vocabulary-cache`: Removes the announcement cache capability and all persisted/in-memory vocabulary precomputation requirements.
- `rsm-audio-navigation`: Replaces memoized pre-computed announcements and beep fallback with current-text on-demand native synthesis, strict latest-wins behavior, and exact app-owned route playback.
- `app-bootstrap`: Replaces the complete phrase-cache readiness/rendering gate with a proven offline navigation voice gate while retaining native STT and Supertonic readiness.
- `initial-setup`: Adds a user-resolvable offline navigation voice step and automatic prerequisite recheck after returning from Android TTS settings.
- `half-duplex-audio-coordination`: Makes caller cancellation complete active playback and physical route teardown before ownership is released or replacement playback is admitted.

## Impact

- Removes `AnnouncementPcmCache`, `SystemAnnouncer`, announcement cache tests, cache manifest/WAV state under `noBackupFilesDir`, and cache-specific bootstrap/UI models and diagnostics.
- Adds Android `TextToSpeech` service visibility, service-owned renderer lifecycle, offline voice/setup state, synthesis callback handling, transient artifact cleanup, PCM format conversion, and focused platform/device tests.
- Changes `PttForegroundService` announcement wiring from key-to-cached-PCM lookup to current text resolution and one tracked navigation operation.
- Strengthens `HostAudioCoordinator` and `ActiveStreamPcmPlayback` cancellation/completion contracts without allowing navigation to preempt capture or unrelated playback.
- Adds no third-party runtime dependency and does not change Supertonic's general synthesis or channel-capability role.
