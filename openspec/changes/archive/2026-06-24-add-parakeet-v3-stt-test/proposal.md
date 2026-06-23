## Why

Subspace can already validate the B02PTT-FF01 microphone path with the echo test, but it does not validate on-device speech recognition from the same PTT capture path. Adding a Parakeet v3 STT test makes the connected-device flow verify capture, headset routing, local model readiness, and transcription output without sending audio off-device.

## What Changes

- Load a Parakeet TDT 0.6b v3 speech-to-text model during app startup so it is available before the user starts the connected test flow.
- Add a speech-to-text test toggle on the connected monitor/test surface that currently contains the echo test toggle.
- Make echo test and STT test mutually exclusive: enabling one disables the other, and both may be off.
- When STT test is active, handle PTT press/release with the same SCO setup and PCM recording path used by echo test, then transcribe the captured audio with Parakeet instead of playing it back.
- Show the latest STT test transcript in a text box below the STT toggle, including non-transcribed states such as loading, recording, transcribing, empty audio, and errors.
- Keep all transcription local to the device.

## Capabilities

### New Capabilities
- `ptt-stt-test`: Defines the connected-device speech-to-text test mode, Parakeet model readiness, mutual exclusion with echo test, PTT-driven recording, local transcription, and transcript display.

### Modified Capabilities

## Impact

- Android app build: adds Parakeet model assets, ONNX Runtime Android/native dependencies, and any native build tooling needed by the selected Parakeet integration.
- Nix devshell: must include any additional Android NDK/Rust/cargo tooling required to build the native transcription layer.
- Runtime startup: initializes model loading in the foreground service lifecycle without blocking first UI composition.
- Audio/service layer: reuses the existing SCO route and in-memory PCM recorder, adds an STT controller/transcriber port, and enforces mutual exclusion with echo.
- UI layer: extends the connected monitor/test screen and `PttUiActions` with STT enable/disable state and transcript display.
- Tests: adds controller/state tests for mutual exclusion, PTT transcription flow, empty-audio handling, and error status propagation.
