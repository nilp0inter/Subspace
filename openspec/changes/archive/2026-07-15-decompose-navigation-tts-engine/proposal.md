## Why

`NavigationTtsEngine.kt` combines public and internal contracts, identity types, deterministic voice and WAV policies, and the stateful Android TTS lifecycle in one 1,828-line file. Separating the behavior-free and deterministic responsibilities first will reduce review and maintenance risk while preserving the existing navigation TTS behavior, callback ownership, coroutine topology, resource lifecycle, and call sites.

## What Changes

- Move navigation TTS failure, configuration, result, callback, and factory contracts into a focused same-package contracts file without changing their names, visibility, signatures, or semantics.
- Move engine epoch, navigation generation, attempt, utterance, callback-terminal, and pending-operation declarations into a focused same-package identity file without changing identity generation or comparison behavior.
- Move deterministic offline English voice selection into a focused same-package voice-selection file while preserving candidate validation, ordering, selection, and failure classification exactly.
- Move WAV failure mapping and normalization into a focused same-package normalizer file while preserving accepted formats, downmixing, resampling, PCM conversion, empty-output rejection, and typed failures exactly.
- Keep all mutable engine state, mutexes, coroutine scopes, request arbitration, callback routing, recovery, transient-file ownership, and stop/shutdown behavior in `NavigationTtsEngine`.
- Add focused regression coverage for deterministic PCM output, factory-construction failure, late callbacks after shutdown, WAV failure mapping, and `requestPcm` overlap behavior before relying on the extracted boundaries.
- Make no product behavior, API, persistence, dependency, manifest, audio-routing, or user-interface changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `native-navigation-tts`: Add structural-equivalence requirements for decomposing contracts and deterministic policies without changing the capability's initialization, synthesis, normalization, callback ownership, recovery, cancellation, cleanup, or failure behavior.

## Impact

- Primary production file: `app/src/main/java/dev/nilp0inter/subspace/audio/NavigationTtsEngine.kt`.
- New focused Kotlin files under `app/src/main/java/dev/nilp0inter/subspace/audio/` for contracts, identities, voice selection, and WAV normalization.
- Focused tests under `app/src/test/java/dev/nilp0inter/subspace/audio/`, primarily `NavigationTtsEngineTest.kt` and existing WAV/audio tests.
- Existing callers, including `PttForegroundService`, continue using the same `NavigationTtsEngine` and result contracts.
- No new runtime dependencies, Android permissions, manifest entries, storage formats, feature flags, compatibility shims, or alternate TTS implementation.
