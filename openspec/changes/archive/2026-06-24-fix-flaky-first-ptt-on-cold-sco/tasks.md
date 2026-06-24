## 1. Audio Infrastructure — SCO route & device routing

- [x] 1.1 Add `coldStart: Boolean` property to `ScoRoute` interface in `AudioPorts.kt` with default `false`
- [x] 1.2 Override `coldStart` in `ScoAudioController.kt`: return `true` when `acquire()` went through full acquisition path (was `Inactive`), `false` when it returned via the warm-path shortcut
- [x] 1.3 Add `preferredDevice: AudioDeviceInfo?` parameter to `playStaticPcm()` in `AndroidAudio.kt`; call `track.setPreferredDevice(preferredDevice)` when non-null
- [x] 1.4 Add priming buffer to `playReadyBeep()` in `AndroidAudio.kt`: when `coldStart` is `true`, prepend 100ms of silence (zeros) to the beep samples array; pass `audioManager.communicationDevice` as `preferredDevice`

## 2. Remove RecordWhileBeepPlays

- [x] 2.1 Remove `EchoTimingMode.RecordWhileBeepPlays` from `EchoTimingMode` enum in `Models.kt`; keep only `RecordAfterBeep` (rename to `EchoTimingMode` and remove the variant)
- [x] 2.2 In `EchoController.kt`: remove `timingMode` property, `setTimingMode()` function, `startDuringBeepMode()` method; rename `startAfterBeepMode()` to `startRecording()`; fold its body inline into the `when` branch (which becomes unconditional)
- [x] 2.3 In `MonitorScreen.kt`: remove the `RecordWhileBeepPlays` toggle buttons from the echo control section
- [x] 2.4 In `PttForegroundService.kt`: remove any wiring to `setEchoTimingMode` (if present)
- [x] 2.5 Remove `EchoTimingMode` references from `MonitorState` defaults in `Models.kt`

## 3. Short-tap SCO warmup — all controllers

- [x] 3.1 In `EchoController.kt`: change `cancelBeforeRecording()` to not call `sco.release()`; instead call `releaseScoAfterWarmup()`; rename to `cancelSession()`
- [x] 3.2 In `SttController.kt`: same change — `cancelBeforeRecording()` becomes `cancelSession()` with warmup retention
- [x] 3.3 In `SttTtsController.kt`: same change
- [x] 3.4 In `CaptainsLogPttController.kt`: add `releaseScoAfterWarmup()` mechanism (mirror pattern from test controllers); change session-end and cancellation paths to retain SCO warm instead of immediate release
- [x] 3.5 In `SttController.kt` and `SttTtsController.kt`: ensure `EmptyAudio` / `EmptyTranscript` error paths also retain SCO warm (currently they call `sco.release()` directly)

## 4. Captain's Log warmup

- [x] 4.1 Add `SCO_WARMUP_MS = 30_000L` constant to `CaptainsLogPttController`
- [x] 4.2 Add `closeScoJob: Job?` field and `releaseScoAfterWarmup()` private function
- [x] 4.3 In `finishSessionIfNeeded()`: replace `sco.release()` with `releaseScoAfterWarmup()`
- [x] 4.4 In `cancelAndRelease()`: cancel `closeScoJob` but do NOT call `sco.release()` (warmup is cancelled, but caller may still want release — keep explicit release for full teardown)

## 5. Cleanup & verification

- [x] 5.1 Run `nix develop -c gradle build` to verify compilation
- [x] 5.2 Run `nix develop -c gradle test` to run existing unit tests
- [x] 5.3 Manual acceptance: cold-start PTT → beep audible; short-tap PTT → next press instant; echo recording always shows beep in playback
