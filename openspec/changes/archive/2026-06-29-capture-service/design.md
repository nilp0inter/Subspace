## Context

Subspace captures PTT audio through three recorders that should be one:

- `InMemoryRecorder` (`AndroidAudio.kt`) — `VOICE_COMMUNICATION` source,
  accumulates PCM in a `mutableListOf<Short>` capped at 60s, returns
  `RecordedPcm` at stop.
- `PhoneMicRecorder` (`PhoneLocalAudio.kt`) — `MIC` source, ~95% identical clone
  of the above; the sole difference is one `AudioSource` enum.
- `FileWavRecorder` (`FileWavRecorder.kt`) — `VOICE_COMMUNICATION` source, same
  capture mechanics, but writes incrementally to a `RandomAccessFile` and does
  NOT implement `AudioRecorder` or return `RecordedPcm`. An island.

Today capture is **owned by channel controllers**, not a shared service:

- `PttForegroundService.resolvePttAudioRoute(mode)` constructs a fresh
  `InMemoryRecorder` on every PTT press and threads it through
  `ResolvedAudioRoute.recorder`.
- `EchoController` / `SttController` / `SttTtsController` receive the route and
  run their own **acquire SCO → play ready beep → recorder.start() → on release
  recorder.stopIfActiveOrEmpty() → consume `RecordedPcm`** sequence.
- `JournalPttController` ignores `route.recorder` (`NoopRecorder`), creates its
  own `FileWavRecorder`, and runs the same acquire/beep/record sequence
  independently.
- `activePttSession != null` enforces a single session at the dispatch layer, but
  nothing enforces it at the recorder layer.

There is **no** `isCapturing` signal and **no** live audio-level computation
anywhere (no `getMaxAmplitude`, no `Visualizer`, no RMS in any read loop). The
only state exposed is per-controller status enums (`EchoStatus`, `SttStatus`, …)
folded into `AppState.monitor`.

Constraints:

- `PRODUCT_VISION.md` treats capture as a discrete, composable pipeline step any
  number of programmable channels subscribe to ("capture, transcription,
  transformation, routing, storage, webhook, and response steps").
- `sco-audio` spec requires recording to start only after the ready beep
  completes, SCO cold-start priming, and the 30s warmup retention window. These
  behaviors MUST be preserved.
- This is production code; the three cloned read loops and the controller-owned
  capture pattern are the debt being paid down.
- The downstream `vu-meter` change consumes this service's level + `isCapturing`
  signals; this change must expose them but does NOT render any UI.

## Goals / Non-Goals

**Goals:**

- One capture service owns the single active `AudioRecord` for the whole app.
- Capture input source is selected by the active route (SCO `VOICE_COMMUNICATION`
  vs phone `MIC`), collapsing the two cloned recorders.
- Captured PCM is emitted as a stream that channel pipelines subscribe to;
  output format (in-memory vs WAV file) becomes the consumer's concern.
- The service exposes `isCapturing` and a live audio `level` signal (RMS),
  computed in the one read loop — the contract the `vu-meter` change depends on.
- Every existing talk path (echo, STT, STT↔TTS, journal, telecom car, phone
  channel-card PTT) is migrated onto the stream; the three recorders are deleted.
- Observable capture behavior is preserved (SCO priming, beep-before-record, 60s
  max, journal WAV artifacts).

**Non-Goals:**

- Rendering any VU meter, transmit glow, or waveform UI — that is the separate
  `vu-meter` change.
- Inbound message playback, backlog, replay, or priority-channel capture.
- Streaming/online STT. STT continues to transcribe the complete capture; it just
  receives the PCM from the stream instead of a terminal `RecordedPcm`.
- Changing the SCO route state machine itself (`ScoAudioController`). The service
  consumes a ready SCO route; it does not redefine warmup/priming semantics.
- New channel types (webhook, external, advanced pipeline). This change enables
  them by making capture a shared step; it does not ship them.

## Decisions

### D1. One capture service owns the single `AudioRecord`

The service holds the one `AudioRecord` instance and the one read loop. This
replaces per-session `InMemoryRecorder` construction, the shared
`PhoneMicRecorder`, and `JournalPttController`'s private `FileWavRecorder`.

**Why over per-controller recorders:** the duplication is gratuitous (two clones
differ only by `AudioSource`), the island breaks the contract, and there is no
single place to compute a level or assert "capturing right now." A service is the
smallest structure that makes the vision's "capture step" literal.

**Alternative considered:** collapse the two clones into one
`AudioRecorder`-implementing class parameterized by source, leave
`FileWavRecorder` and controller ownership in place. Rejected: it dedups the
clones but leaves the disease (controller-owned capture, no level signal, no
pipeline composition) untreated.

### D2. Capture emits a PCM stream; consumers subscribe; a terminal result is still available

The capture session exposes both:

- `frames: SharedFlow<ShortArray>` — live PCM chunks as read from the loop, for
  level computation and future streaming consumers.
- `stop(): RecordedPcm` — returns the full buffered PCM at session end (replaces
  `InMemoryRecorder.stopIfActiveOrEmpty()`), so echo/STT migrate with minimal
  change.

The session buffers internally (same 60s cap as today), so `stop()` is cheap and
the live `frames` flow is additional, not load-bearing for existing consumers.

**Why both:** a pure hot-stream model forces every consumer (including STT, which
transcribes a complete capture) to re-implement accumulation; a pure terminal
model gives no live signal for the VU meter. Offering both is the
behavior-preserving path that still unblocks the meter.

### D3. Input source is a route-selected parameter

Route resolution returns an input source (`SCO` → `VOICE_COMMUNICATION`,
phone fallback → `MIC`), not a recorder instance. The capture service starts the
`AudioRecord` with the selected source. This is what collapses the two clones:
`AudioSource` becomes a parameter, not a class.

### D4. The service exposes `isCapturing` and a live `level` signal

- `isCapturing: StateFlow<Boolean>` — true while a capture session is active.
  This is the single transmit-state signal across all talk modes (the contract
  for the VU meter's visibility and any future transmit glow).
- `level: StateFlow<Float>` — normalized 0..1 RMS, updated per chunk read in the
  one read loop. The downstream VU meter applies ballistics (rise/fall, peak
  hold); the service emits raw per-chunk levels.

**Why RMS in the read loop:** it is the single natural tap point, computed from
the same `ShortArray` already being read. No `Visualizer`, no
`getMaxAmplitude`, no second capture path.

### D5. Output format is the consumer's concern

`FileWavRecorder` is deleted. The journal pipeline subscribes to `frames` and
writes WAV chunks itself (the WAV header finalization logic moves into a small
journal-side writer). This removes the in-memory-vs-file axis from capture
entirely — capture captures; the pipeline persists.

**Alternative considered:** capture service offers a "write to file" mode.
Rejected: it re-couples output format to capture and re-creates the
`FileWavRecorder` island under a new name.

### D6. Acquire SCO → ready beep → start capture is centralized into the session start

The acquire/beep/record sequence is currently duplicated across Echo, STT,
SttTts, and Journal. The capture service hosts a `startSession(...)` that
performs: assert single-session → acquire SCO route (via the existing
`ScoAudioController`) → play ready beep (cold-start priming per `sco-audio`) →
start the `AudioRecord` and read loop. Controllers call `startSession` and
receive the running session (with its `frames`/`stop()`); they no longer touch
SCO acquisition or the beep.

**Why centralize:** it removes four copies of the same sequencing, makes the
beep-before-record invariant (sco-audio spec) a single enforced point, and keeps
the short-tap-during-beep cancellation logic in one place.

**Alternative considered:** leave acquire/beep in controllers, have them call
`captureService.start()` after. Rejected as a half-measure that preserves the
duplication this change exists to remove.

### D7. Single-session invariant enforced by the service

`startSession` rejects a second concurrent start (returns failure / null session)
rather than relying on the dispatch-layer `activePttSession != null` guard alone.
The half-duplex PTT invariant becomes a property of capture, not of dispatch
coincidence.

### D8. Journal persistence model

Journal subscribes to `frames` and streams chunks to a `RandomAccessFile` WAV
(using the existing header-finalization technique from `FileWavRecorder`). On
release, the service's `stop()` finalizes capture; journal finalizes the WAV
header and runs the existing `WavPcmReader` → metadata → `journal.processCaptureFile`
post-processing unchanged.

This preserves journal's incremental-to-disk behavior (no whole-capture held in
memory) while removing the private recorder.

## Risks / Trade-offs

- **[Risk] Migrating five talk paths in one change is large.**
  → Mitigation: migrate one controller at a time behind the new service, keep the
  old recorders compiling until all consumers switch, delete them in the final
  task. Each migration step keeps its existing JVM timing/state tests green.
- **[Risk] Centralizing acquire/beep/record changes the cancellation surface.**
  (Short tap during SCO acquisition, release during beep.) → Mitigation: the
  sco-audio spec scenarios for these are reproduced as capture-service spec
  scenarios; the existing echo/journal tests that cover them must pass unchanged.
- **[Risk] `SharedFlow` frame emission at 16kHz chunk rate could be hot.**
  → Mitigation: level is computed per chunk and emitted via a separate
  `StateFlow` (conflated); `frames` uses `extraBufferCapacity = 0` and
  `onBufferOverflow = DROP_OLDEST` so a slow subscriber never backpressures the
  read loop. STT/echo use the buffered terminal result, not live `frames`, so
  they are insensitive to subscriber speed.
- **[Risk] Journal streaming-to-disk regression vs. the old `FileWavRecorder`.**
  → Mitigation: the WAV writer logic is lifted verbatim from `FileWavRecorder`
  into a journal-side writer; the existing journal artifact tests cover it.
- **[Trade-off] Two observables (`frames` + `level`) are added to the service
  contract even though only `level`/`isCapturing` are consumed this change.**
  Justified: `frames` is the vision's pipeline subscription point and `level` is
  the VU meter's contract; both are cheap and define the service's purpose.

## Migration Plan

1. Add the capture service + its unit tests, with the three old recorders still
   present and still wired. No behavior change yet.
2. Switch echo to the service. Echo's existing tests stay green.
3. Switch STT, then STT↔TTS.
4. Switch journal to the stream + journal-side WAV writer; delete
   `FileWavRecorder`.
5. Switch telecom car capture + phone channel-card PTT (these flow through
   `resolvePttAudioRoute`, which now returns an input source, not a recorder).
6. Delete `InMemoryRecorder` and `PhoneMicRecorder`; rework `AudioPorts.kt`
   (`AudioRecorder`, `ResolvedAudioRoute`, `resolveAudioRoute`) so route
   resolution selects an input source for the service.
7. Wire `isCapturing` and `level` out of `PttForegroundService` so the downstream
   `vu-meter` change can collect them.

Rollback: each step is independently revertible. The service can land without any
consumer switched; consumers can be switched back to the old recorders until the
deletion step.

## Open Questions

- **Level emission cadence:** per-chunk (tied to `minBufferSize` read size) is
  simplest but may be tens of Hz. Acceptable for the VU meter, or should the
  service throttle to a fixed ~30Hz? Lean: per-chunk, let the meter conflate.
- **`OnTheRoad` (telecom) capture routing:** today it builds a
  `TelecomCapturePcmOutput` gated on telecom disconnect. Confirm the capture
  service starts/stops cleanly under that gating (capture should not outlive the
  telecom session). To be validated in the telecom migration step.
