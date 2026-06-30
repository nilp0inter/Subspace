## Why

The Journal channel (Captain's Log) always transcribes through a throwing no-op `PcmTranscriber` that emits `[Transcription failed: STT transcriber unavailable]` into the generated daily markdown. `initializeJournal` reads the `transcriptionService` field synchronously inside `PttForegroundService.onCreate` before the STT off-main init coroutine has finished assigning it, so the field is always `null` at the read site and the no-op fallback is permanently captured into the `JournalController`. The debug-channel `SttController` does not hit this because it is constructed inside the STT post-init block after the transcriber is assigned. This is the same "startup reads an async-produced field synchronously" family as the `SttTtsController` race fixed in `2026-06-29-fix-stttts-init-race`; that change documented this exact cousin as a non-goal and deferred it to this change.

## What Changes

- `initializeJournal` stops reading `transcriptionService` synchronously at `onCreate`. It instead launches a `serviceScope.launch` coroutine that awaits the existing `sttReady` `CompletableDeferred<SttTranscriber?>` rendezvous, then constructs `TranscriptionService`, `JournalController`, and `JournalPttController` with the real transcriber (or, on `null`, the no-op fallback preserving the failure semantics).
- The `runRecovery(baseDirectory)` call and the base-directory-change collector currently launched inside `initializeJournal` move into the awaiting coroutine so they execute after the controller exists and with the real transcriber wired.
- `journalPttController` construction moves from synchronous-at-`onCreate` to post-await. During the window before construction, PTT dispatch to `journalPttController?.onPttPressed/onPttReleased` sees a `null` controller and no-ops — identical to today's behavior for any not-yet-constructed controller, and the Journal channel is not PTT-usable until a base directory is selected anyway.
- No new `CompletableDeferred` is introduced; `sttReady` already exists and is completed on both success and failure paths of the STT init coroutine.

## Capabilities

### New Capabilities
<!-- None. This change fixes a startup-availability defect in an existing capability; it does not introduce one. -->

### Modified Capabilities
- `captains-log-channel`: Add a requirement that the Journal channel's STT transcription becomes wired to the real on-device Parakeet transcriber once the STT off-main init completes, regardless of whether STT init finishes before or after `onCreate`, so journal captures are transcribed instead of falling back to the no-op transcriber. Add scenarios for the STT-succeeds path and the STT-fails path.

## Impact

- **Affected code**: `PttForegroundService.initializeJournal` (the sole edit site). The Rust crates, the JNI bridge, the asset extractors, `JournalController`, `JournalPttController`, and the `PcmTranscriber`/`TranscriptionService` ports are unchanged. The `sttReady` field and its `complete(...)` call in `initializeStt` are unchanged.
- **No API changes**: `JournalController` and `JournalPttController` constructors are unchanged; only the **timing** of their construction changes (post-await instead of synchronous at `onCreate`).
- **Behavioral change**: On every launch, Journal STT becomes usable once the Parakeet off-main init completes (seconds on first run, sub-second on warm run) instead of being permanently unavailable until relaunch. During the construction window the Journal channel behaves as if the controller is not yet built (no-op PTT dispatch), which is already the pre-readiness behavior since the channel requires a selected base directory before accepting PTT.
- **Lifecycle**: The awaiting launch runs on `serviceScope`, so the existing `serviceScope.cancel()` in `onDestroy` cancels it if the service dies mid-await — no dangling coroutine, no controller posted to a destroyed service. `onDestroy`'s `journalPttController?.cancelAndRelease()` remains safe because the field is still `null` until the await resolves.
- **Out of scope**: Making `JournalController` lazily/on-demand constructed, changing the STT init coroutine, re-architecting the startup sequence beyond reusing the existing `sttReady` rendezvous, and any change to the debug-channel `SttController` path (which already works).
- **Dependencies**: None added or removed.