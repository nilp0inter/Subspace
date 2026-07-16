## Context

`app/src/main/java/dev/nilp0inter/subspace/audio/NavigationTtsEngine.kt` currently contains 1,828 physical lines: the stateful Android `TextToSpeech` owner plus failure contracts, identifiers, result models, deterministic voice selection, and deterministic WAV normalization. The implementation already exposes useful internal seams and has focused unit coverage, but reviewing any engine change requires navigating unrelated declarations in the same file.

This change is a behavior-preserving decomposition, not a replacement engine. The existing `NavigationTtsEngine` remains the sole runtime owner of `TextToSpeech`, coroutine scopes, mutexes, request generations, pending callbacks, recovery, transient files, and teardown. Existing callers and Kotlin declarations retain their current names, package, visibility, and signatures.

The load-bearing behaviors are defined by the `native-navigation-tts` capability: initialization ordering, deterministic offline voice selection, probe behavior, latest-wins synthesis, callback epoch and generation ownership, WAV normalization, bounded recovery, cancellation, transient-file deletion, and at-most-once stop/shutdown.

## Goals / Non-Goals

**Goals:**

- Reduce `NavigationTtsEngine.kt` by moving independent contracts and deterministic policies into focused same-package files.
- Preserve source-facing declarations and all observable runtime behavior.
- Keep mutable state and lifecycle ownership centralized in `NavigationTtsEngine`.
- Establish regression barriers for deterministic PCM output and currently under-characterized Android callback/error boundaries before depending on the new file boundaries.
- Make each move independently reviewable and reversible.

**Non-Goals:**

- Rewriting or replacing `NavigationTtsEngine`.
- Extracting callback routing, request arbitration, recovery, or shutdown into new stateful components.
- Changing coroutine scopes, mutexes, dispatchers, job parentage, cancellation propagation, or timeout values.
- Changing voice eligibility or ordering, WAV decoding, resampling, PCM conversion, failure classification, log behavior, transient-file handling, or playback routing.
- Changing existing declaration names, visibility, signatures, packages, or call sites beyond imports required by compilation.
- Adding feature flags, compatibility aliases, dependency-injection frameworks, runtime dependencies, or alternate implementations.
- Refactoring adjacent `WavPcmReader`, `TtsAudio`, bootstrap, playback, or service code.

## Decisions

### 1. Use same-package declaration extraction, not runtime strangler routing

The change SHALL move existing declarations directly into focused files under `dev.nilp0inter.subspace.audio`. There will be one implementation of each declaration before and after each move; no legacy/new runtime router will be introduced.

A production dual-run would be unsafe because `TextToSpeech`, transient files, callbacks, and playback are side effects that cannot be executed twice. Same-package extraction retains Kotlin visibility and avoids permanent forwarding shims.

**Alternative considered:** Introduce interfaces and old/new implementations behind a feature flag. Rejected because it duplicates implementation and state ownership without enabling safe production shadow execution.

### 2. Preserve `NavigationTtsEngine` as the single mutable owner

The following remain in `NavigationTtsEngine.kt` for this change:

- `requestMutex`, `stateMutex`, and `recoveryMutex`.
- All coroutine scopes and jobs.
- `liveTts`, live epoch state, authoritative navigation generation, and pending-operation registry ownership.
- `prepare`, `request`, `requestPcm`, synthesis execution, recovery, listener installation, callback routing, live-instance publication, stop/shutdown, and transient-file lifecycle methods.

`PendingOperation` may move as a data declaration, but the engine remains the only owner of the mutable `pendingOps` registry and all transitions involving it.

**Alternative considered:** Extract a callback router concurrently. Rejected because listener installation, pending-operation ownership, epoch filtering, generation filtering, cleanup, recovery, and shutdown are tightly coupled. That requires a separate change with its own state-transition design.

### 3. Group declarations by stable responsibility

The target production files are:

- `NavigationTtsContracts.kt`: failure hierarchy, configuration, preparation and synthesis results, state-loss callback, `TextToSpeechFactory`, and its default implementation.
- `NavigationTtsIdentity.kt`: engine epoch, navigation generation, attempt token, utterance identity, callback terminal result, and pending-operation data.
- `NavigationVoiceSelector.kt`: voice-selection result and deterministic offline English voice selection.
- `NavigationWavNormalizer.kt`: WAV decode failure mapping, normalization result, and WAV-to-navigation-PCM normalization.
- `NavigationTtsEngine.kt`: the stateful engine owner and its private lifecycle helpers.

Declarations SHALL retain their current package, names, visibility, annotations, defaults, and documentation relevant to their contracts. Imports may change only as required by the moves.

**Alternative considered:** One file per type. Rejected because it would fragment cohesive contract groups and increase navigation overhead.

### 4. Establish characterization before moving each deterministic boundary

Before moving voice selection or WAV normalization, focused tests SHALL pin their current deterministic outputs. WAV normalization coverage SHALL compare exact sample values and sample rate for representative supported formats rather than only asserting non-empty output.

Before relying on the extracted contracts, focused engine tests SHALL characterize:

- factory construction failure propagation/classification,
- late callbacks after shutdown,
- typed WAV failure mapping through the normalization boundary,
- `requestPcm` overlap with active synthesis, probe, or recovery where supported by the current harness.

Tests SHALL record current intended behavior; the extraction itself SHALL not change production branches to make a new expectation pass.

**Alternative considered:** Rely only on compilation and the existing test suite. Rejected because existing tests do not fully pin byte-level normalization or several callback/error edges.

### 5. Perform clean declaration moves with no compatibility layer

Each declaration will exist in exactly one source file. Because package and symbol identity remain unchanged, callers should not require migration. Temporary type aliases, forwarding functions, deprecated declarations, and re-exports are prohibited.

Rollback is source-level reversion of the relevant declaration move. There is no data or deployment migration.

### 6. Separate correctness changes from structural moves

If characterization exposes an existing defect or ambiguous behavior, implementation SHALL stop that declaration move and record the defect as separate scope. The decomposition must not silently fix, suppress, or redefine behavior.

Formatting changes SHALL be limited to the project formatter's necessary output on touched declarations. Unrelated documentation, naming, and style changes are excluded.

## Risks / Trade-offs

- **Risk: Kotlin visibility or annotation changes during movement** → Preserve package, modifiers, annotations, defaults, and declaration text; compile after each responsibility group moves.
- **Risk: Top-level private references no longer resolve** → Keep engine-private constants and helpers with their only owner, or move the complete deterministic dependency group together; do not widen visibility solely to make a split compile.
- **Risk: Deterministic policies change through incidental rewriting** → Move declarations without rewriting expressions, then verify exact voice-selection and PCM fixtures.
- **Risk: Tests accidentally specify new behavior** → Capture the current implementation first; treat any desired behavior correction as separate scope.
- **Risk: Large comments become stale after relocation** → Move contract documentation with the declarations it describes while preserving cross-references.
- **Risk: More files increase navigation overhead** → Use four cohesive responsibility files rather than one file per declaration.
- **Trade-off: The engine class remains large after this change** → Accepted. This change removes low-risk responsibilities first; stateful callback/recovery decomposition is intentionally deferred.
- **Trade-off: No production shadow mode** → Accepted. Side-effectful duplicate execution would be less safe than an atomic same-package cutover backed by deterministic and event-level tests.

## Migration Plan

1. Add missing characterization coverage against the current single-file implementation.
2. Move the contracts group and verify compilation and focused tests.
3. Move identity declarations and verify compilation and focused tests.
4. Move voice-selection declarations and verify deterministic selection tests.
5. Move WAV-normalization declarations and verify exact PCM and failure-mapping tests.
6. Run the focused navigation TTS, WAV reader, and TTS audio test set, then build the affected Android module.
7. If any step changes an observable result, callback sequence, failure type, or resource action, revert that declaration group rather than adding a compatibility path.

No persisted data, manifest, runtime rollout, or user migration is required.

## Open Questions

None. Stateful callback-router or recovery extraction requires a separate proposal after this behavior-preserving decomposition is complete.
