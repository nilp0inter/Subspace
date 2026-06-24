## Why

On cold SCO (no active Bluetooth audio route), the first PTT press records audio but the ready beep is inaudible. The AudioTrack routes through Android's default mixer policy instead of the SCO endpoint, and the SCO link is not yet streaming PCM packets when the beep plays. Subsequent PTT presses (within the 30s warmup window) work correctly because the SCO streaming path is already established.

Additionally, the `RecordWhileBeepPlays` timing mode lets recording start before the beep, which means the beep can be truncated at the start of recordings or not captured at all.

## What Changes

- **Fix cold-start beep**: Force AudioTrack onto the SCO communication device. Add a priming silence buffer before the beep on cold-start SCO to establish the PCM stream.
- **Remove RecordWhileBeepPlays (BREAKING)**: Recording always starts after the beep completes. Remove the `EchoTimingMode.RecordWhileBeepPlays` variant, the UI toggle, and the `startDuringBeepMode()` code path.
- **Short-press SCO pre-warming (BREAKING)**: When PTT is released during SCO acquisition (short tap), do NOT release SCO. Continue acquisition, hold SCO warm for 30s, so the next press gets instant audio. This changes the "PTT released before recording starts" scenario behavior — previously SCO was released, now it is retained.

## Capabilities

### New Capabilities
- `sco-audio`: Defines the Bluetooth SCO route lifecycle — acquisition, cold-start priming, device-level audio routing on AudioTrack, and the SCO warmup retention window.

### Modified Capabilities
- `ptt-stt-tts-test`: Remove `RecordWhileBeepPlays` timing mode from echo test. Recording always starts after the ready beep completes. Short PTT taps pre-warm SCO instead of aborting.

## Impact

- `EchoController.kt` — remove `startDuringBeepMode()`, remove `timingMode`/`setTimingMode()`, change `cancelBeforeRecording()` to retain SCO on short tap
- `SttController.kt` — change `cancelBeforeRecording()` to retain SCO on short tap
- `SttTtsController.kt` — same short-tap SCO retention
- `CaptainsLogPttController.kt` — same short-tap SCO retention; no echo-specific changes
- `ScoAudioController.kt` — no structural changes (priming done at call site)
- `AndroidAudio.kt` — `playReadyBeep()` and `playStaticPcm()` gain `preferredDevice` parameter; add priming buffer method
- `Models.kt` — remove `EchoTimingMode.RecordWhileBeepPlays` enum value
- `MonitorScreen.kt` — remove RecordWhileBeepPlays UI toggle
- `PttForegroundService.kt` — remove `setEchoTimingMode` wiring
- `AudioPorts.kt` — `PcmOutput.playReadyBeep()` may get a `coldStart` parameter
