## Context

The `capture-service` refactor (archive `2026-06-29-capture-service`) unified
three recorders into one service and centralized the acquire-SCO → ready-beep
→ start-record sequence. It introduced five regressions, all rooted in two
structural gaps the refactor opened without closing:

1. **Split SCO ownership.** The service acquired SCO inside `startSession`
   (design D6) but the controllers stayed responsible for releasing SCO on
   the post-capture path. The service's own failure branches
   (`RecordingFailed`, `Cancelled`) released nothing; three of four
   controllers also skipped release on `RecordingFailed`; and STT / STT↔TTS
   re-threw `CancellationException` out of their transcribe jobs before
   `sco.release()` ran. Net effect: every failed mic-open and every
   interrupted transcription permanently leaked one SCO reference, so the
   headset stayed routed for the rest of the session.
2. **Split lifecycle without happens-before edges.** The recorder's old
   single synchronous lifecycle (start → read→write in one coroutine → stop)
   became three independent async lifecycles: the service's read loop, the
   frames `SharedFlow`, and (for journal) a separate collector coroutine
   writing to a `RandomAccessFile`. `stop()` cleared the service's `active`
   reference via a launched `onFinalized` callback coroutine rather than
   synchronously, opening a window in which a rapid re-press hit
   `SessionActive`. The journal's `framesJob.cancel()` did not wait for the
   collector to unwind before `writer.finalize()` closed the file, so a late
   `writeChunk` could throw `IOException` into a half-finalized WAV. And
   `JournalWavWriter` hardcoded a 16 kHz header even though
   `AndroidCaptureSource.selectSampleRate()` may negotiate 8 kHz, because the
   negotiated rate lived on `OpenedCaptureSource` and never reached the
   consumer.

The refactor also left an unfinished pattern in place:
`PcmOutput.releaseRoute()` was added to the interface and implemented
correctly for `TelecomCapturePcmOutput` (immediate release + await telecom
disconnect), but `AndroidPcmOutput.releaseRoute()` stayed the default no-op.
Three controllers therefore reached past the output and called
`route.sco.release()` directly. The telecom path did not, because its output
already owned the release. This asymmetry is the seam the design leans on.

Constraints:

- The `capture-service` spec's requirements are the contract: single active
  session, route-selected input source, live frames, terminal `RecordedPcm`,
  beep-before-record, 60 s max, live level, `isCapturing`. This change must
  not weaken any of them.
- The `sco-audio` spec's warmup / cold-start / retention semantics are
  unchanged. The release path still routes through `ScoAudioController`,
  which owns the 30 s warmup window and refcount.
- The `channel-framework` journal spec's WAV artifact and metadata invariants
  are unchanged; only the sample-rate source and the write/finalize ordering
  change.
- This is production code on the target hardware; the fixes must be
  independently revertible and must not change observable behavior beyond
  fixing the regressions.

## Goals / Non-Goals

**Goals:**

- Restore SCO ref-count balance on every PTT cycle path: failed mic-open,
  cancelled-before-beep, cancelled-during-beep, normal release, max-duration,
  transcription-cancelled-by-new-press.
- Make `PcmOutput.releaseRoute()` the single controller-facing API for route
  release, completing the pattern the refactor introduced for telecom.
  Controllers stop touching `ScoRoute` in the PTT flow.
- Make the service's `active` session reference transition synchronously on
  finalize so a rapid re-press is never rejected as `SessionActive`.
- Propagate the negotiated `sampleRate` from the opened source to the journal
  consumer so the WAV header matches the actual PCM.
- Make `JournalWavWriter` internally thread-safe and add a
  `cancelAndJoin` handoff in the journal consumer so the collector has
  unwound before the file is closed.
- Preserve every existing `capture-service`, `sco-audio`, and
  `channel-framework` requirement that is not being explicitly modified.

**Non-Goals:**

- Changing the SCO state machine (`ScoAudioController`) — warmup, priming,
  retention window, and refcounting stay as-is.
- Streaming/online STT, inbound message playback, backlog, priority-channel
  capture, or any product-vision feature work.
- Removing `ScoRoute` from `ResolvedAudioRoute` or from controller
  constructors. The `sco` parameter on controllers becomes unused by the PTT
  flow but is still referenced by `cancelAndRelease()` as a safety net and by
  `resolveAudioRoute`. Cleaning that up is a follow-up.
- Changing the `SharedFlow` frames contract (DROP_OLDEST, buffer=1) or the
  terminal `RecordedPcm` contract. Both stay.
- Migrating the telecom car path off `TelecomCapturePcmOutput`. It already
  implements `releaseRoute()` correctly; this change makes the normal path
  match it, not the reverse.

## Decisions

### D1. The output owns route release; controllers call `route.output.releaseRoute()`

The refactor added `releaseRoute()` to `PcmOutput` and implemented it for
`TelecomCapturePcmOutput` (immediate release + await telecom disconnect).
`AndroidPcmOutput.releaseRoute()` stayed the default no-op. Three controllers
therefore called `route.sco.release()` directly, reaching past the output to
touch the route.

This change completes the pattern: a new `ScopedPcmOutput` wraps a delegate
output and a `releaseRoute` lambda. `resolveAudioRoute` wraps `scoOutput`
with `{ scoRoute.release() }` and `localOutput` with `{ }`. The resolved
route's `output.releaseRoute()` performs the correct release for the route
that was selected. Controllers call `route.output.releaseRoute()` and never
touch `ScoRoute` in the PTT flow.

```
┌────────────────────────────────────────────────────────────────┐
│  PTT cycle ownership after this change                         │
├────────────────┬───────────────────────────────────────────────┤
│ Phase          │ Owner                                         │
├────────────────┼───────────────────────────────────────────────┤
│ 1. Setup       │ CaptureService.startSession                   │
│    acquire SCO │   sco.acquire()                               │
│    beep        │   output.playReadyBeep()                      │
│    open source │   source.open()                               │
│    on failure  │   sco.release()            ← FIX Bug 1       │
├────────────────┼───────────────────────────────────────────────┤
│ 2. Capture     │ CaptureService.ActiveSession                  │
│    read loop   │   emits frames, computes level                │
│    max duration│   self-finalizes, active = null synchronously │
├────────────────┼───────────────────────────────────────────────┤
│ 3. Consume     │ Controller + PcmOutput                        │
│    playback    │   route.output.play(pcm)                      │
│    transcribe  │   transcriptionService.transcribe(pcm)        │
│    write WAV   │   writer.writeChunk(chunk)                    │
│    release     │   route.output.releaseRoute()  ← THE FIX      │
│                │     normal:  sco.release()    (30 s warmup)   │
│                │     telecom: sco.releaseImmediately()         │
│                │     local:   no-op                            │
└────────────────┴───────────────────────────────────────────────┘
```

**Why over controllers calling `sco.release()` directly:** SCO release mode
(warm 30 s for the SCO route, immediate for telecom, no-op for local) is a
property of the audio output, not of capture and not of the channel
controller. Only the output knows it. The telecom path already encodes this;
this change makes the normal path match it instead of special-casing
`if (telecomOutput) ... else ...` in every consumer.

**Why over the session owning the full SCO lifecycle (the alternative
considered at length during explore):** that approach adds a
`session.releaseRoute()` API and forces the session to learn about release
modes, coupling capture to output semantics. The output already has the
`releaseRoute()` API and already knows the mode. Completing the existing
pattern is strictly smaller and keeps capture unaware of how its route is
released.

**Alternative considered: leave controllers calling `sco.release()` and just
add the missing releases.** Rejected as the minimal-pure level: it fixes the
leak but leaves the asymmetry where telecom goes through `output.releaseRoute()`
and every other path bypasses it. The next regression in the same area would
reopen the same seam.

### D2. The service releases SCO on every `startSession` branch that acquired and did not hand off a running session

`startSession` acquires SCO, plays the ready beep, and opens the source. On
`Cancelled` (PTT released during acquire or beep) and `RecordingFailed`
(source open returned null), the service now calls `sco.release()` before
returning. On `ScoUnavailable` the acquire failed so there is nothing to
release. On `SessionActive` no new acquire happened. On `Started` the
running session inherits the release responsibility (the controller will call
`route.output.releaseRoute()` after its post-capture consumer finishes).

```
┌───────────────────┬────────────────────────────────────────────┐
│ startSession      │ SCO release                                 │
│ outcome           │                                             │
├───────────────────┼────────────────────────────────────────────┤
│ ScoUnavailable    │ nobody (acquire failed)                    │
│ SessionActive     │ nobody (no new acquire)                    │
│ Cancelled         │ SERVICE (new) ← was: controller            │
│ RecordingFailed   │ SERVICE (new) ← was: nobody (leak)         │
│ Started → stop    │ controller via route.output.releaseRoute() │
│ Started → cancel  │ controller cancelAndRelease →              │
│                   │   output.releaseRoute()                    │
│ Started → max-dur │ controller release after playback          │
└───────────────────┴────────────────────────────────────────────┘
```

Controllers stop calling `route.sco.release()` / `route.output.releaseRoute()`
on the `Cancelled` and `RecordingFailed` branches (the service already did
it). They keep calling `route.output.releaseRoute()` on the `Started` path's
post-capture consumer.

**Why this is safe with the SCO refcount:** `ScoAudioController.acquire()`
increments `activeClients` and `release()` decrements it. A balanced
acquire→release pair leaves the refcount unchanged and triggers the 30 s
warmup-release timer exactly as the pre-refactor code did on the same paths.
The service does not call `releaseImmediately()` on these branches — the
warmup window is preserved per the `sco-audio` spec.

### D3. STT and STT↔TTS wrap post-capture jobs in `try/finally` so release runs on cancellation

Today the transcribe / synthesize job releases SCO only on its success and
non-cancel failure branches. The `CancellationException` branch re-throws
before `sco.release()`, so a new PTT press that cancels an in-flight
transcription leaks one SCO reference.

The fix is structural: wrap the entire post-capture consumer in
`try/finally`, with `route.output.releaseRoute()` in `finally`. `finally`
runs on normal completion, failure, and cancellation. Every exit path
releases exactly once.

```kotlin
transcribeJob = scope.launch {
    try {
        val text = transcriptionService.transcribe(recording.samples, recording.sampleRate)
        _status.value = SttStatus.Transcribed(text)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        _status.value = ...
    } finally {
        route.output.releaseRoute()
    }
}
```

**Why `finally` and not a custom cancellation handler:** coroutines
guarantee `finally` runs on cancellation. A custom handler would have to
re-implement the same guarantee. `try/finally` is the language-level
expression of "release on every exit."

**Why this composes with D2:** the service has already released SCO for its
own failure branches by the time the controller sees a non-`Started` result.
The `try/finally` here only applies to the `Started` path, where the
controller is the owner of the post-capture consumer. There is no
double-release: the service releases on failure branches, the controller
releases on the post-capture path, and the two are mutually exclusive.

### D4. `CaptureSession` exposes the negotiated `sampleRate`

`AndroidCaptureSource.selectSampleRate()` negotiates 16 kHz or 8 kHz. The
negotiated rate lives on `OpenedCaptureSource.sampleRate`, which the
`ActiveSession` already holds. The interface does not expose it, so
`JournalPttController` hardcoded `SAMPLE_RATE = 16_000` when constructing
`JournalWavWriter`. On an 8 kHz device the WAV header says 16 kHz while the
PCM is 8 kHz, so `WavPcmReader.read` reports the wrong rate and playback runs
at 2× speed.

The fix is one interface property:

```kotlin
interface CaptureSession {
    val frames: SharedFlow<ShortArray>
    val completion: Deferred<CaptureCompletion>
    val sampleRate: Int          // ← new
    suspend fun stop(): RecordedPcm
}
```

`ActiveSession.sampleRate` is `opened.sampleRate`. The journal consumer
constructs the writer with `session.sampleRate` and writes the metadata
`sampleRate` from the same source.

**Why this is a session property and not a `CaptureSource` property:**
`CaptureSource` is the factory; the negotiated rate is a property of the
opened source, which the session owns. Exposing it on the source would
require the consumer to open the source to read it, which defeats the
service's single-owner invariant. The session is the consumer's handle to
everything about the running capture, including its rate.

### D5. The service clears `active` synchronously in `finalize()`

Today `ActiveSession.finalize()` clears the service's `active` reference via
a launched `onFinalized` callback coroutine:

```kotlin
onFinalized = { finalizedSession ->
    scope.launch {  // async
        mutex.withLock { if (active === finalizedSession) active = null }
    }
}
```

Between `stop()` returning and that coroutine running, `active` is still the
finalized session. A rapid re-press enters `startSession`, takes the mutex,
sees `active != null`, and returns `SessionActive`. The user's press is
silently dropped.

The fix removes the callback and clears `active` synchronously inside
`finalize()`:

```kotlin
private fun finalize(reason: CaptureCompletion): RecordedPcm {
    synchronized(finalizeLock) {
        if (finalized != null) return finalized!!
        finalized = pcm
        if (active === this) active = null  // ← synchronous
        onCaptureSignalChange(false)
        _completion.complete(reason)
    }
    readJob.cancel()
    return pcm
}
```

**Why this is safe without the service mutex:** `active` is a reference
field; JVM reference writes are atomic. The only writers are
`startSession` (under `mutex.withLock`) and `finalize()` (under
`finalizeLock`). The `active === this` identity check in `finalize()`
ensures it only clears its own session. The cases:

- `finalize` runs before `startSession` enters the mutex: `active = null`,
  `startSession` sees `null`, proceeds. ✓
- `finalize` runs while `startSession` holds the mutex setting
  `active = newSession`: `finalize` checks `active === this` (old session)
  → false → does not clear. ✓
- `startSession` enters the mutex, sees `active` = old session, returns
  `SessionActive`: this can only happen if `finalize` has not yet run;
  once `finalize` runs, `active` is `null` before the next `startSession`
  can observe it. ✓

The `finalizeLock` ensures the `finalized` check and `active = null` happen
atomically. `startSession`'s mutex ensures its `active != null` check and
`active = session` assignment are atomic. Cross-lock safety comes from the
identity check.

**Why `cancelActiveSession()` is unaffected:** it already clears `active`
synchronously under the mutex. The fix makes `stop()` / `cancelSession()`
match it.

### D6. `JournalWavWriter` is internally thread-safe; the consumer joins the collector before finalizing

Two independent coroutines touch the same `RandomAccessFile`: the frames
collector (on `Dispatchers.Main.immediate`) writes via `writeChunk`, and
`finishSession` (on `Dispatchers.IO`) closes the file via `finalize`.
`framesJob.cancel()` does not wait for an in-progress `writeChunk` to
finish, so `finalize()` can close the file while a write is in flight.

**Layer 1 — internal safety:** `JournalWavWriter` gets a `synchronized(lock)`
around both `writeChunk` and `finalize`, plus a `@Volatile closed` guard. A
late `writeChunk` after `finalize` is a no-op rather than an `IOException`.
This alone closes the race.

```kotlin
class JournalWavWriter(targetFile: File, private val sampleRate: Int) {
    private val lock = Any()
    @Volatile private var closed = false
    private val raf = RandomAccessFile(targetFile, "rw").also {
        writeWavHeader(it, sampleRate, dataSize = 0)
    }

    fun writeChunk(chunk: ShortArray) {
        synchronized(lock) {
            if (closed) return
            // ... existing write logic ...
        }
    }

    fun finalize() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // ... existing finalize logic (seek, write sizes, close) ...
        }
    }
}
```

**Layer 2 — structural handoff:** the consumer calls
`framesJob?.cancelAndJoin()` before `writer.finalize()`. `cancelAndJoin`
gives a clean happens-before edge: no collector is running when `finalize`
executes. The thread-safe writer is the safety net; `cancelAndJoin` is the
structural fix.

```kotlin
withContext(Dispatchers.IO) {
    framesJob?.cancelAndJoin()  // ← wait for collector to unwind
    framesJob = null
    session.stop()
    writer.finalize()
    ...
}
```

**Why both layers:** `cancelAndJoin` alone is sufficient in the common case,
but the thread-safe writer is defense-in-depth against any future code path
that finalizes without joining (e.g. `cancelAndRelease()` in error paths).
The old `FileWavRecorder` was a single coroutine so read and write could not
interleave; the split-lifecycle refactor removed that guarantee, and these
two layers restore it without re-merging the lifecycles.

### D7. `ScopedPcmOutput` is delegation-only and has no state of its own

```kotlin
class ScopedPcmOutput(
    private val delegate: PcmOutput,
    private val releaseRoute: suspend () -> Unit,
) : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) = delegate.playReadyBeep(coldStart)
    override suspend fun playErrorBeep(coldStart: Boolean) = delegate.playErrorBeep(coldStart)
    override suspend fun play(recording: RecordedPcm) = delegate.play(recording)
    override suspend fun releaseRoute() = releaseRoute()
}
```

`TelecomCapturePcmOutput` already follows this shape (it wraps
`captureOutput` and `mediaResponsePlayer` and overrides `play` /
`releaseRoute`). `ScopedPcmOutput` is the minimal version for the normal
path: delegate everything, supply the release lambda. No new state, no new
threading, no new lifecycle.

**Why not just make `AndroidPcmOutput.releaseRoute()` call `sco.release()`:**
`AndroidPcmOutput` does not know which `ScoRoute` it is paired with — it is
constructed once in `PttForegroundService.onCreate` and reused across routes
that may have different SCO controllers (or `NoopScoRoute` for the local
fallback). `ScopedPcmOutput` captures the specific route's release lambda at
resolution time, so the output-route pairing is explicit and per-PTT-cycle.

## Risks / Trade-offs

- **[Risk] Controllers still hold a `sco` constructor parameter that becomes
  unused by the PTT flow.** It is still referenced by `cancelAndRelease()`
  as a safety net. → Mitigation: leave it for this change; a follow-up can
  remove it once `output.releaseRoute()` is trusted as always-available.
  Document the residual in the design and in a code comment.
- **[Risk] `synchronized` in `JournalWavWriter` introduces a blocking mutex
  on a coroutine that may run on `Dispatchers.Main.immediate`.** →
  Mitigation: the critical section is a single `RandomAccessFile.write` of a
  chunk (typically 256–1024 bytes); hold time is sub-millisecond. The frames
  collector is the only writer. `finalize` is called from `Dispatchers.IO`.
  Blocking risk is bounded and matches the existing I/O pattern.
- **[Risk] `cancelAndJoin` in the journal consumer blocks the PTT release
  path until the collector unwinds.** → Mitigation: the collector's only
  suspension point is `SharedFlow.collect`, which checks cancellation between
  emissions. `cancelAndJoin` returns within one chunk read. If the collector
  is mid-`writeChunk`, the synchronized block completes before cancellation
  is observed. Worst case is one chunk-write latency (~1 ms).
- **[Risk] Changing `Cancelled` and `RecordingFailed` to release SCO inside
  the service changes the controller contract.** A controller that still
  calls `route.sco.release()` on those branches would double-release.
  → Mitigation: the migration is mechanical and covered by tests; every
  controller's `Cancelled` / `RecordingFailed` branch is updated in the same
  change. `ScoAudioController.release()` is idempotent-ish (decrements
  refcount, floors at 0), so a double-release is a refcount off-by-one that
  shortens the warmup window rather than crashing — but the tests assert
  exact release counts to catch it.
- **[Trade-off] `ScopedPcmOutput` adds one allocation per PTT cycle.**
  Justified: it is a small delegation wrapper, allocated once per press, and
  it removes the per-controller `if (telecomOutput) ... else ...` branching
  that the journal consumer currently carries.

## Migration Plan

Each step is independently revertible. Steps 1–5 can land in any order; step
6 is the integration point.

1. **`JournalWavWriter` thread safety (D6 layer 1).** Add `synchronized` +
   `@Volatile closed`. No behavior change; existing journal tests stay green.
2. **`CaptureSession.sampleRate` (D4).** Add the interface property and the
   `ActiveSession` implementation. No consumer change yet.
3. **Service SCO release on failure branches (D2).** Add `sco.release()` to
   `Cancelled` and `RecordingFailed` in `startSession`. Remove the
   `onFinalized` callback and clear `active` synchronously in `finalize()`
   (D5). Existing `CaptureServiceTest` scenarios for `Cancelled` and
   `RecordingFailed` are updated to assert the service release.
4. **`ScopedPcmOutput` + `resolveAudioRoute` wiring (D1).** Introduce the
   class and wrap both branches of `resolveAudioRoute`. No controller change
   yet; `route.output.releaseRoute()` now works for every route.
5. **Controller migration (D1, D3).** In each of `EchoController`,
   `SttController`, `SttTtsController`, `JournalPttController`: replace
   `route.sco.release()` with `route.output.releaseRoute()` on the
   post-capture path and in `cancelAndRelease()`. Drop the
   `route.sco.release()` / `route.output.releaseRoute()` calls on the
   `Cancelled` and `RecordingFailed` branches (the service releases now).
   Wrap STT / STT↔TTS transcribe jobs in `try/finally`. Update
   `JournalPttController` to use `session.sampleRate` and
   `framesJob?.cancelAndJoin()`.
6. **Test updates and new regression tests.** Existing controller tests
   assert against `output.releaseRoute()` instead of `sco.release()`. New
   tests: SCO ref-count balance across a transcribe-interrupted PTT cycle;
   8 kHz source produces a correct WAV; rapid re-press after `stop()` is
   accepted; `JournalWavWriter` thread safety under concurrent finalize;
   journal `cancelAndJoin` ordering.

Rollback: each step is a separate commit. Step 5 is the only step that
changes controller behavior; reverting it restores the old release calls
without reverting the service-side fixes (which are strictly additive).

## Open Questions

- **`cancelAndRelease()` in controllers still calls `sco.release()` as a
  safety net.** Should this change remove the `sco` parameter from
  controllers entirely and rely on `output.releaseRoute()` alone, or leave
  the safety net for this change and clean it up in a follow-up? Lean: leave
  it; the safety net is cheap and the follow-up is mechanical.
- **Should `ScopedPcmOutput` also wrap the `NoopScoRoute` local fallback,
  or should the local output's `releaseRoute()` stay the default no-op?**
  The proposal wraps it with an empty lambda for symmetry. Lean: wrap it, so
  every route's output has a non-default `releaseRoute()` and the contract
  is uniform.