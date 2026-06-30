## Context

`fix-jni-startup-ui-blocking` moved Parakeet (STT) and Supertonic (TTS) initialization off the Android main thread into parallel `serviceScope.launch(Dispatchers.IO)` coroutines launched from `PttForegroundService.onCreate`. Each coroutine assigns the model instance to a service field and, on success, dispatches a post-init block back to `serviceScope` (Main.immediate) that constructs the engine's controller, model-status poller, and status collector.

`initializeJournal` is also called from `onCreate`, synchronously, immediately after `initializeStt` and `initializeTts` are *launched* (not completed). It reads `transcriptionService` at that synchronous moment:

```
onCreate (main thread)
├─ initializeStt(audioManager)            // launches IO coroutine, returns
├─ initializeTts(audioManager)           // launches IO coroutine, returns
└─ initializeJournal(audioManager)       // runs NOW
     ├─ val transcriber = transcriptionService ?: <no-op>   // ALWAYS null
     ├─ JournalController(transcriber = <no-op>)
     └─ JournalPttController(… journal = journalController)  // captured forever
```

The STT IO coroutine has not yet finished asset extraction, native library load, and ONNX Runtime init, so `transcriptionService` (assigned at line 260, inside the coroutine) is still `null`. The no-op `PcmTranscriber` (throws `IllegalStateException("STT transcriber unavailable")`) is captured into the `JournalController` and never replaced. Every journal capture forever after transcribes through the throwing no-op, and the markdown renderer writes `[Transcription failed: STT transcriber unavailable]`.

The debug-channel `SttController` does not hit this because it is constructed *inside* the STT post-init block (line 261), after `transcriptionService` is assigned (line 260).

`fix-stttts-init-race` introduced a `sttReady = CompletableDeferred<SttTranscriber?>()` field, completed on both the success path (the transcriber) and the failure path (`null`) of the STT init coroutine. It is already `await`ed in the TTS post-init block before constructing `SttTtsController`. That change's design doc explicitly named the `transcriptionService` / `initializeJournal` cousin as a non-goal and deferred it to this change.

## Goals / Non-Goals

**Goals:**
- The Journal channel's `JournalController` and `JournalPttController` are constructed with the real on-device Parakeet transcriber once the STT off-main init completes, regardless of whether STT init finishes before or after `onCreate`, with no app relaunch required.
- Reuse the existing `sttReady` `CompletableDeferred<SttTranscriber?>` rendezvous; introduce no new fields.
- Preserve the existing failure semantics: if STT init fails (`sttReady` resolves to `null`), the no-op `PcmTranscriber` is used so captures still persist as WAV files and metadata with a failed transcription state.
- Keep the STT init coroutine unchanged; only the consumer (`initializeJournal`) changes.

**Non-Goals:**
- Making `JournalController` or `JournalPttController` lazily/on-demand constructed (build on first PTT).
- Changing the STT init coroutine, the Rust crates, the JNI bridge, the asset extractors, or the `PcmTranscriber` / `TranscriptionService` ports.
- Re-architecting the startup sequence beyond reusing the existing `sttReady` rendezvous.
- Changing the debug-channel `SttController` path (which already works).

## Decisions

### Decision 1: Await `sttReady` inside a `serviceScope.launch` before constructing the journal controllers

**Choice:** Wrap the entire body of `initializeJournal` in `serviceScope.launch { val transcriber = sttReady.await(); …build controllers… }`. The `await` resolves with the transcriber on success or `null` on failure. On success, construct `TranscriptionService(transcriber)` and pass it to `JournalController`. On failure (`null`), use the existing no-op `PcmTranscriber` so the failure semantics are preserved.

```
initializeJournal (proposed)
└─ serviceScope.launch {
     val transcriber = sttReady.await()          // resolves on both paths
     val real = transcriber
         ? TranscriptionService(transcriber)     // success → real transcriber
         : <no-op PcmTranscriber>                // failure → no-op (unchanged semantics)
     JournalController(transcriber = real)
     JournalPttController(… journal = journalController)
     runRecovery(baseDir) if already selected
     // base-dir collector launched here too
   }
```

**Alternatives considered:**

- **Read `transcriptionService` after the STT coroutine via a busy-wait / delay loop:** rejected. Polling is an anti-pattern; `await` on a `Deferred` expresses the dependency directly and is already idiomatic in this file (the `SttTtsController` fix uses it).
- **Construct `JournalController` synchronously with a lazy transcriber wrapper that defers the `sttReady.await()` to first `transcribe` call:** rejected. `JournalController` takes a `PcmTranscriber` (synchronous interface); introducing a lazy wrapper would add a new class and change the transcriber contract for a single consumer. More plumbing than the rendezvous, for no net benefit.
- **Move `initializeJournal` *into* the STT post-init block so it runs after `transcriptionService` is assigned:** rejected. Couples journal construction to STT success and re-serializes it behind STT init. The journal should be constructable even when STT fails (using the no-op), and it should not be gated on STT success — only the real transcriber should be.
- **Introduce a separate `transcriptionReady` deferred for the `TranscriptionService`:** rejected as over-built. `sttReady` already resolves to `SttTranscriber?`, and `TranscriptionService` is a thin wrapper around it. One deferred and one await suffices; a second deferred would be speculative scaffolding.

### Decision 2: Move `runRecovery` and the base-directory collector into the awaiting coroutine

**Choice:** The `runRecovery(baseDir)` call (currently synchronous in `initializeJournal`) and the `_appState.collect { … runRecovery(currentDir) }` collector (currently launched from `initializeJournal`) both move inside the `serviceScope.launch { sttReady.await(); … }` block, so they execute after the controller exists and with the real transcriber wired.

**Why:** `runRecovery` reconciles stale entries and can re-derive transcription state. Running it before the transcriber is wired would reconcile against the no-op and miss transcription re-derivation. Moving it after the await ensures recovery uses the real transcriber. The base-directory collector has the same dependency: it triggers `runRecovery`, so it must also run after the controller is constructed.

**Trade-off:** If the user selects a base directory before STT init completes, `runRecovery` is delayed until the await resolves (seconds on first run, sub-second on warm run). This is acceptable because recovery is a startup housekeeping task, not a user-facing latency, and the journal channel is not PTT-usable until a directory is selected anyway.

### Decision 3: Keep the no-op fallback only for the genuine STT-failure case

**Choice:** The no-op `PcmTranscriber` is preserved as the `null` branch of the `sttReady.await()` — used only when STT init genuinely fails. It is no longer the default path (which was the bug).

**Why not remove the no-op entirely:** the `captains-log-channel` spec's "Transcription fails" scenario requires that a failed STT model marks the entry as failed in metadata without discarding the capture file. The no-op transcriber throws, `JournalController` catches it, marks the entry `failed`, and the capture file is preserved. Removing the no-op would require `JournalController` to handle a `null` transcriber, which is a larger contract change than the null branch.

## Risks / Trade-offs

- **[Risk] `sttReady.await()` could hang if `complete` is missed on some path** → already mitigated by the `fix-stttts-init-race` change: `complete(transcriber)` runs after the `try/catch` that produces a nullable result on both paths, so `await()` always resolves. No new `complete` call is added by this change.
- **[Risk] `journalPttController` is `null` during the construction window; PTT dispatch no-ops** → identical to today's behavior for any not-yet-constructed controller. The Journal channel requires a selected base directory before accepting PTT, so the user cannot trigger a capture during the window anyway. The `?.onPttPressed` / `?.onPttReleased` / `?.cancelAndRelease` call sites are already null-safe.
- **[Risk] `runRecovery` is delayed until the await resolves** → acceptable; recovery is startup housekeeping, not user-facing. The delay is bounded by the slower engine's init (seconds on first run, sub-second on warm run), and the journal channel is not usable until a base directory is selected.
- **[Risk] `serviceScope` is cancelled while the await is suspended** → `serviceScope.cancel()` in `onDestroy` cancels the awaiting launch; no dangling coroutine, no controller posted to a destroyed service. `onDestroy`'s `journalPttController?.cancelAndRelease()` is safe because the field is still `null` until the await resolves.
- **[Trade-off] Testability** → the await lives inside `PttForegroundService`, which the fakes-based unit tests do not exercise. `JournalControllerTest` tests the controller with a fake transcriber directly and is unaffected. The change relies on the manual acceptance check; the rendezvous seam can be extracted later if a second consumer appears.
- **[Risk] The base-directory collector race** → if the user selects a base directory during the await window, the collector (now inside the awaiting coroutine) will see it on first emission after construction. No directory change is lost because `_appState.collect` replays the current value on collection start.