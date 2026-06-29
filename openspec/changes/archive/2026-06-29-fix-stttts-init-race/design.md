## Context

`fix-jni-startup-ui-blocking` moved Parakeet (STT) and Supertonic (TTS) initialization off the Android main thread into two parallel `serviceScope.launch(Dispatchers.IO)` coroutines launched from `PttForegroundService.onCreate`. Each coroutine, on success, dispatches a post-init block back to `serviceScope` (Main.immediate) that constructs that engine's controller, model-status poller, and status collector.

`SttTtsController` is the one controller that depends on **both** engines. It is constructed inside the **TTS** post-init block with a one-shot, non-awaiting peek at the `sttTranscriber` field:

```
TTS post-init block:  ...build ttsController, poller, collector...
                      val sttTransc = sttTranscriber      // peek — no wait
                      if (sttTransc != null) { build sttTtsController + collector }
```

If the TTS coroutine completes before the STT coroutine has assigned `sttTranscriber`, the peek reads `null` and `SttTtsController` is skipped permanently — there is no retry, and the STT post-init block does not touch it. STT↔TTS test mode is then unavailable until the app is force-stopped and relaunched.

This is not a rare warm-run fluke. With the current asset versions the Parakeet int8 extraction (~670 MB, dominated by the 622 MB `encoder-model.int8.onnx`) is **larger** than the Supertonic extraction (~398 MB), so on first run TTS completes first and the race loses by default. The prior change accepted this on the assumption STT finishes first; that assumption is inverted for the current assets.

`PttForegroundService` already uses `CompletableDeferred` for cross-producer coordination (`telecomDisconnected`), and `CaptureService` uses it for completion signaling (`_completion`).

## Goals / Non-Goals

**Goals:**
- `SttTtsController` is constructed exactly once **both** engines have finished initializing, in **either** completion order, with no app relaunch required.
- Keep the two engine inits fully parallel; do not re-serialize them.
- Preserve the existing failure semantics: if either engine's init fails, `SttTtsController` is not constructed.
- Introduce no new main-thread work and no new per-request cost.

**Non-Goals:**
- Fixing the `transcriptionService` / `initializeJournal` cousin, where journal STT falls back to the no-op `PcmTranscriber` because `initializeJournal` reads `transcriptionService` synchronously at `onCreate` before the STT coroutine assigns it. This is the same family of "startup reads an async-produced field synchronously" defect but is deferred to its own change.
- Making `SttTtsController` (or any controller) lazily/on-demand constructed.
- Changing the Rust crates, the JNI bridge `init` blocks, the asset extractors, or the Kotlin `SttTranscriber` / `TtsSynthesizer` ports.
- Re-architecting the startup sequence beyond the single join point.

## Decisions

### Decision 1: Join the two inits with a `CompletableDeferred<SttTranscriber?>`

**Choice:** Add one service field `sttReady = CompletableDeferred<SttTranscriber?>()`. The STT off-main coroutine completes it exactly once — with the transcriber on success, with `null` on failure. The TTS post-init block, in place of the one-shot `if (sttTranscriber != null)` peek, launches `serviceScope.launch { val transcriber = sttReady.await(); if (transcriber != null) { construct SttTtsController + start its status collector } }`.

The announcer, TTS controller, TTS model-status poller, and TTS status collector remain unconditional on TTS success and unchanged.

**Alternatives considered:**

- **Serialize the inits (STT awaits before TTS, or TTS awaits before constructing `SttTtsController` inline):** rejected. Re-serializes two multi-hundred-MB first-run extractions, which is exactly the ANR-adjacent main-thread-adjacent cost the prior change removed. Also rejects the prior change's explicit Decision 1 (parallel init).
- **A separate symmetric rendezvous launch that awaits both a `sttReady` and a `ttsReady` deferred, then builds `SttTtsController`:** rejected as over-built. The cross-engine dependency is asymmetric — only `SttTtsController` needs both, and it is already owned by the TTS post-init context. One deferred and one await suffices; a second deferred and a third launch site would be speculative scaffolding for a second cross-engine controller that does not exist.
- **Lazy / on-demand construction of `SttTtsController` (build on first STT↔TTS PTT):** rejected. It would eliminate the startup race entirely but flips the controller's lifecycle from startup-built to build-on-first-use, requiring a once-only guard (Mutex / `compareAndSet`) and leaving the STT↔TTS status collector dormant until first use. More plumbing than the rendezvous, for no net benefit.
- **Poll `sttTranscriber` until non-null with a delay loop:** rejected. Polling is an anti-pattern when `await` on a `Deferred` expresses the dependency directly and is already idiomatic in this file.

### Decision 2: Complete with `null` on STT failure, never leave the deferred pending

**Choice:** The STT coroutine's `complete(...)` call runs on **both** the success and failure paths (the existing `try { ... } catch { null }` already yields a nullable result), so `sttReady.await()` always resolves. On failure it resolves to `null`, the awaiting launch skips construction, and the coroutine ends — no hanging await.

**Why not leave it pending on failure:** a pending deferred would leave the awaiting launch suspended forever. It is harmless while the service lives (the coroutine is lightweight and `onDestroy` cancels it), but resolving with `null` makes the failure path explicit and deterministic instead of relying on cancellation to clean it up.

### Decision 3: Keep the join inside the TTS post-init block

**Choice:** The `await` lives in a `serviceScope.launch` started from within the TTS post-init block (which only runs on TTS success), rather than in a freestanding join coroutine.

**Why:** the join only matters when TTS succeeded. Embedding it in the TTS success path makes that precondition local and obvious, and keeps the diff to one field + one `complete` + one `launch{await}`. The asymmetry mirrors the real asymmetry of the dependency.

## Risks / Trade-offs

- **[Risk] `sttReady.await()` could hang if `complete` is missed on some path** → `complete(transcriber)` is placed after the existing `try/catch` that already produces a nullable result, so it executes on every exit path of the STT coroutine's init. The catch path produces `null`, guaranteeing resolution.
- **[Risk] `SttTtsController` is constructed slightly later than today when STT finishes after TTS** → this is the intended fix, not a regression. During the window before construction, `dispatchPttPressed`/`dispatchPttReleased` for STT↔TTS mode see a `null` controller and no-op — identical to today's behavior for any not-yet-constructed controller. The window is bounded by the slower engine's init (seconds on first run, sub-second on warm run).
- **[Risk] The `serviceScope` is cancelled while the await is suspended** → the `serviceScope.cancel()` added in `onDestroy` (by the prior change) cancels the awaiting launch; no dangling coroutine, no controller posted to a destroyed service.
- **[Risk] The deferred is per-service-instance and `onCreate` could run again** → `Service.onCreate` runs exactly once per service instance; the field is initialized once. No re-completion concern.
- **[Trade-off] Testability** → the join lives inside `PttForegroundService`, which the fakes-based unit tests do not exercise (this is why the prior change's 5.1 tests passed unchanged). Extracting the rendezvous into a testable seam (e.g. an `InitCoordinator`) would enable a "built in either order; not built on either failure" unit test, but would expand the diff and introduce a new abstraction for a single use site. This change keeps the join inline and relies on the manual acceptance check; a seam can be added later if a second cross-engine controller appears.

## Migration Plan

No migration. This is an internal concurrency fix with no data, API, persistence, or UI-surface change. Deployment is a normal release; rollback is reverting the commit (the only behavioral difference is that STT↔TTS mode reverts to "unavailable on the first-run TTS-first ordering until relaunch").

## Open Questions

- None blocking. The journal `transcriptionService` cousin (Non-Goals) is the natural follow-up change but is explicitly deferred here to keep this change tight.
