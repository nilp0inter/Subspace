## Why

Subspace has three PTT audio recorders that should be one. `InMemoryRecorder`
and `PhoneMicRecorder` are ~95% identical clones differing only in
`AudioSource` (one enum), and `FileWavRecorder` is an island with the same
capture mechanics but an incompatible contract. Worse, capture is owned by each
channel controller (`EchoController`, `SttController`, `SttTtsController`,
`JournalPttController`, telecom capture) rather than by a shared service, so
there is no single source of truth for "audio is being captured right now" and
no place to compute a live audio level.

This blocks two things at once: the `PRODUCT_VISION.md` channel model (which
treats capture as a discrete, composable pipeline step that any number of
programmable channels subscribe to), and the VU meter / transmit indicator the
operator UI needs. The codebase is production code, so paying down this debt
now — rather than taping a level tap onto three read loops — is the correct
move. The VU meter ships as a separate, downstream change that consumes this
service.

## What Changes

- Introduce a unified **capture service** that owns the single active
  `AudioRecord` for the whole app. Only one capture session may be active at a
  time (half-duplex PTT invariant), enforced by the service rather than by
  per-controller coincidence.
- Capture input source is selected from the active audio route (SCO
  `VOICE_COMMUNICATION` vs phone `MIC`) — collapsing the two cloned recorders
  into one recorder parameterized by source.
- The capture service emits captured PCM as a stream that channel pipelines
  subscribe to, instead of each controller owning a recorder that hands back a
  terminal `RecordedPcm`. Output format (in-memory vs file) stops being a
  recorder concern and becomes a property of the consuming pipeline.
- The service exposes two new observables that do not exist today:
  `isCapturing` (a single transmit-state signal across all talk modes) and a
  live audio `level` signal (RMS/peak) computed in the one read loop. These
  unblock the downstream VU-meter change and any future transmit indicator.
- Migrate every existing talk path — echo, STT, STT↔TTS, journal WAV capture,
  telecom car capture, phone-channel-card PTT — off their own recorders and onto
  the capture stream as subscribers.
- Delete `InMemoryRecorder`, `PhoneMicRecorder`, and `FileWavRecorder`. The
  journal pipeline writes the stream to WAV itself; the in-memory consumers
  (echo, STT) buffer the stream themselves.

## Capabilities

### New Capabilities

- `capture-service`: Unified PTT audio capture — single active `AudioRecord`
  owned by a service, input source selected by active route, PCM emitted as a
  stream for channel pipelines to subscribe to, plus `isCapturing` and live
  audio-level signals.

### Modified Capabilities

None at the requirement level. This is a behavior-preserving refactor: the
observable capture behavior of every existing talk path (SCO route priming, ready
beep before recording, 60s max capture, WAV artifacts for journal) is unchanged.
Existing channel specs keep their requirements; they are reimplemented against
the capture stream rather than against per-controller recorders.

## Impact

- **Audio package** (`app/src/main/java/dev/nilp0inter/subspace/audio/`):
  `AndroidAudio.kt`, `PhoneLocalAudio.kt`, `FileWavRecorder.kt` lose their
  recorder classes; `AudioPorts.kt` (`AudioRecorder` interface,
  `ResolvedAudioRoute`, `resolveAudioRoute`) is reworked so route resolution
  selects an input source for the capture service rather than handing out a
  recorder instance.
- **Channel controllers** (`EchoController`, `SttController`, `SttTtsController`,
  `JournalPttController`, telecom capture path): rewritten to subscribe to the
  capture stream instead of owning a recorder.
- **`PttForegroundService`**: becomes the lifecycle owner of the single capture
  service; PTT dispatch starts/stops one capture session and routes the stream
  to the active channel controller.
- **State exposure**: `PttForegroundService` exposes the new `isCapturing` and
  level signals (alongside `appState`) so `MainActivity` and the downstream
  VU-meter change can collect them.
- **Tests**: existing JVM unit tests for echo/STT/journal timing and the car
  skip/metadata projections are preserved; new unit tests cover the capture
  service's single-session invariant, input-source selection, and level
  computation against a fake PCM source.
- **No user-visible behavior change** in this change alone. The VU meter itself
  lands in the separate `vu-meter` change that depends on this one.
