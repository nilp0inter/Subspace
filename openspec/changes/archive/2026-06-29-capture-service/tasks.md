## 1. Capture service foundation

- [x] 1.1 Add `CaptureService` skeleton in `audio/` owning a single `AudioRecord`, a `CoroutineScope`, and the read loop; no consumers wired yet
- [x] 1.2 Define the input-source abstraction (SCO `VOICE_COMMUNICATION` vs phone `MIC`) the service starts the recorder with, replacing the per-source recorder classes
- [x] 1.3 Implement `startSession(source, sco, output)` performing: assert single-session → acquire SCO → play ready beep (cold-start priming) → start `AudioRecord` + read loop; return the running session or a typed failure
- [x] 1.4 Implement the session's live `frames: SharedFlow<ShortArray>` with `DROP_OLDEST` overflow so subscribers never backpressure the read loop
- [x] 1.5 Implement internal PCM buffering with the 60s cap and the terminal `stop(): RecordedPcm` returning the full capture
- [x] 1.6 Compute per-chunk RMS in the read loop and expose `level: StateFlow<Float>` (0..1) and `isCapturing: StateFlow<Boolean>`
- [x] 1.7 Add JVM unit tests for the service against a fake PCM source: single-session rejection, source selection, `frames` emission, terminal result (full and empty), 60s cap, level signal (input/silent/idle), and `isCapturing` transitions
- [x] 1.8 Verify `nix develop --no-write-lock-file -c gradle test assembleDebug` passes with the service present and no consumers switched

## 2. Migrate echo

- [x] 2.1 Rewrite `EchoController` to obtain capture via the service session (`frames`/`stop()`) instead of an owned `InMemoryRecorder`; remove its recorder constructor parameter
- [x] 2.2 Remove `EchoController`'s duplicated acquire/beep sequencing (now owned by `startSession`)
- [x] 2.3 Keep echo's existing JVM tests (cancellation, default/alternate timing, SCO keep-warm, max-duration) green without modifying their assertions

## 3. Migrate STT and STT↔TTS

- [x] 3.1 Rewrite `SttController` to consume the terminal capture from the service session; remove its recorder constructor parameter
- [x] 3.2 Rewrite `SttTtsController` the same way; remove its recorder constructor parameter
- [x] 3.3 Keep STT/STT↔TTS existing JVM tests green without modifying their assertions

## 4. Migrate journal and remove FileWavRecorder

- [x] 4.1 Add a journal-side incremental WAV writer that subscribes to `frames` and writes PCM to a `RandomAccessFile`, finalizing the header on session end (lift the logic verbatim from `FileWavRecorder`)
- [x] 4.2 Rewrite `JournalPttController` to start capture via the service and stream frames to the journal WAV writer; remove its private `FileWavRecorder` and its own acquire/beep sequencing
- [x] 4.3 Preserve journal post-processing unchanged: `stop()` → WAV header finalize → `WavPcmReader` → metadata → `journal.processCaptureFile`
- [x] 4.4 Keep journal artifact/metadata JVM tests green without modifying their assertions
- [x] 4.5 Delete `FileWavRecorder.kt`

## 5. Migrate telecom and phone-channel-card PTT

- [x] 5.1 Change `resolvePttAudioRoute` to return a selected input source (plus sco/output) instead of constructing an `InMemoryRecorder`
- [x] 5.2 Route OnTheRoad (telecom) capture through the service; confirm capture starts/stops under `TelecomCapturePcmOutput` gating and does not outlive the telecom session
- [x] 5.3 Route phone channel-card PTT (OnAPinch / Work fallback) through the service with the `MIC` source
- [x] 5.4 Update `PttForegroundService` PTT dispatch (`dispatchPttPressed`/`Released`) to call the service's `startSession`/`stop` and pass the running session to the active controller

## 6. Remove legacy recorders and rework audio ports

- [x] 6.1 Delete `InMemoryRecorder` and `PhoneMicRecorder`
- [x] 6.2 Rework `AudioPorts.kt`: remove `AudioRecorder`, rework `ResolvedAudioRoute` and `resolveAudioRoute` so route resolution selects an input source for the service rather than handing out a recorder
- [x] 6.3 Remove now-unused imports and constructor recorder parameters across `PttForegroundService` and controllers
- [x] 6.4 Verify full build: `nix flake check --no-write-lock-file` and `nix develop --no-write-lock-file -c gradle test assembleDebug`

## 7. Expose signals and finalize

- [x] 7.1 Expose `isCapturing: StateFlow<Boolean>` and `level: StateFlow<Float>` from `PttForegroundService` alongside `appState` so the downstream `vu-meter` change can collect them via the existing binder
- [x] 7.2 Add a unit test asserting `isCapturing`/`level` reflect a capture session lifecycle driven through the service
- [ ] 7.3 Confirm no user-visible behavior change: re-run the existing echo/STT/journal/car test suites and the manual acceptance checks in `AGENTS.md` (PTT press/release, Group mode, volume, echo routing, foreground-service notification)
- [x] 7.4 Confirm the `capture-service` spec scenarios each map to a passing test
