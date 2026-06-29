## Why

The `capture-service` refactor unified three recorders into one service and
centralized the acquire-SCO → ready-beep → start-record sequence, but it
introduced five regressions by splitting ownership of the SCO lifecycle and the
WAV write lifecycle across multiple components without establishing
happens-before edges between them. The headset gets stuck routed after a mic-
open failure or an interrupted transcription; the journal can produce a
malformed WAV on 8 kHz devices or when the frames collector races the
finalizer; and a rapid PTT re-press is intermittently rejected as
`SessionActive` because the service clears its `active` reference
asynchronously. These are correctness bugs against already-shipped spec
scenarios, not edge cases.

## What Changes

- Complete the `PcmOutput.releaseRoute()` pattern that the refactor introduced
  for `TelecomCapturePcmOutput` but left unfinished for the normal SCO and
  local output paths. A new `ScopedPcmOutput` wraps a delegate output and owns
  its route's release semantics (warm 30 s release for SCO, no-op for local).
  Controllers stop calling `route.sco.release()` and call
  `route.output.releaseRoute()` instead, so SCO release mode (warm vs
  immediate) becomes a property of the output implementation rather than a
  per-controller decision.
- The capture service releases SCO on its own failure branches
  (`Cancelled`, `RecordingFailed`) inside `startSession`, so a failed
  acquire→beep→open sequence does not leak a SCO reference. Controllers no
  longer release SCO on those branches (the service already did).
- STT and STT↔TTS controllers wrap their post-capture transcription /
  synthesis jobs in `try/finally` so `route.output.releaseRoute()` runs on
  cancellation, normal completion, and failure. This closes the SCO ref-count
  leak when the user presses PTT while a previous capture's transcription is
  still running.
- `CaptureSession` exposes the negotiated `sampleRate` so the journal
  consumer writes the WAV header with the rate the source actually negotiated
  (16 kHz or 8 kHz), not a hardcoded 16 kHz.
- `CaptureService.startSession` / `finalize()` clears the `active` session
  reference synchronously inside the `finalizeLock` block instead of via a
  launched `onFinalized` callback coroutine, so a rapid re-press after
  `stop()` / `cancelSession()` does not hit `SessionActive`.
- `JournalWavWriter` becomes internally thread-safe (`synchronized` around
  `writeChunk` and `finalize`, `@Volatile closed` guard) and the journal
  consumer calls `framesJob?.cancelAndJoin()` before `writer.finalize()` so
  the collector has unwound before the file is closed.

## Capabilities

### New Capabilities

None. This change fixes regressions in already-shipped capabilities; it does
not introduce a new capability.

### Modified Capabilities

- `capture-service`: Service releases SCO on `Cancelled` and `RecordingFailed`
  branches of `startSession`; the `active` session reference transitions to
  null synchronously on finalize; `CaptureSession` exposes `sampleRate`.
- `sco-audio`: SCO release ownership moves from controllers to the
  `PcmOutput` implementation via `releaseRoute()`; the controller no longer
  touches `ScoRoute` directly in the PTT flow. (No change to the SCO state
  machine or warmup window semantics.)
- `captains-log-channel`: Journal pipeline reads the session's negotiated
  `sampleRate` for WAV header writing; the journal-side WAV writer is
  thread-safe and the frames collector is joined before finalization.

## Impact

- **`app/src/main/java/dev/nilp0inter/subspace/audio/AudioPorts.kt`**:
  `resolveAudioRoute` wraps `scoOutput` and `localOutput` in
  `ScopedPcmOutput` so the resolved route's `output.releaseRoute()` performs
  the correct release. New `ScopedPcmOutput` class (small, delegation-only).
- **`app/src/main/java/dev/nilp0inter/subspace/audio/CaptureService.kt`**:
  `startSession` releases SCO on `Cancelled` and `RecordingFailed`.
  `ActiveSession.finalize` clears the service-level `active` reference
  synchronously; the `onFinalized` callback is removed.
  `CaptureSession` interface gains `val sampleRate: Int`.
- **`app/src/main/java/dev/nilp0inter/subspace/audio/EchoController.kt`**,
  **`SttController.kt`**, **`SttTtsController.kt`**,
  **`app/src/main/java/dev/nilp0inter/subspace/channel/JournalPttController.kt`**:
  every `route.sco.release()` in the PTT flow becomes
  `route.output.releaseRoute()`. STT and STT↔TTS wrap their transcribe /
  synthesize jobs in `try/finally` so release runs on cancellation.
  `cancelAndRelease()` calls `output.releaseRoute()` instead of `sco.release()`.
- **`app/src/main/java/dev/nilp0inter/subspace/audio/JournalWavWriter.kt`**:
  `synchronized(lock)` around `writeChunk` and `finalize`; `@Volatile closed`
  guard so a late `writeChunk` after `finalize` is a no-op rather than an
  `IOException`.
- **`app/src/main/java/dev/nilp0inter/subspace/channel/JournalPttController.kt`**:
  `framesJob?.cancelAndJoin()` before `writer.finalize()`; WAV writer is
  constructed with `session.sampleRate` instead of the hardcoded
  `SAMPLE_RATE = 16_000`.
- **Tests**: new unit tests cover (a) SCO release on `RecordingFailed` and
  `Cancelled`, (b) SCO ref-count balance across a transcribe-interrupted PTT
  cycle, (c) `sampleRate` propagation to a journal-side writer on an 8 kHz
  source, (d) rapid re-press after `stop()` accepted without `SessionActive`,
  (e) `JournalWavWriter` thread safety under a concurrent `finalize` during a
  write, (f) journal `cancelAndJoin` ordering. Existing controller tests are
  updated to assert against `output.releaseRoute()` calls instead of
  `sco.release()` calls.
- **No user-visible behavior change**. The fixes restore the observable
  behavior the pre-refactor code had: SCO releases on every PTT cycle, journal
  audio plays at the correct speed on 8 kHz devices, and rapid PTT presses are
  never silently dropped.