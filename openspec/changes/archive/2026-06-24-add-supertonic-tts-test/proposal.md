## Why

Subspace can already validate the B02PTT-FF01 capture path with the echo test and the on-device STT test (Parakeet v3), but it cannot validate on-device speech synthesis from the same connected surface. Adding a Supertonic TTS test and an STT→TTS round-trip test verifies local text-to-speech playback routing through the headset and demonstrates an end-to-end voice-in/voice-out loop without any network dependency.

## What Changes

- Load a Supertonic 3 TTS model during app startup so it is available before the user starts a connected test, mirroring the existing Parakeet startup load.
- Add a Rust/JNI `subspace-supertonic` native bridge in the existing Rust workspace, reusing the same asset-extraction, ONNX Runtime dynamic loading, singleton-engine, load-status polling, and JSON outcome patterns as `subspace-parakeet`.
- Download Supertonic 3 ONNX assets (duration predictor, text encoder, vector estimator, vocoder, `tts.json`, `unicode_indexer.json`) and all available preset voice styles (`F1`-`F5`, `M1`-`M5`) during the Gradle build with SHA-256 verification, into generated build assets, without committing model binaries.
- Add a `TtsSynthesizer` port with a fake implementation for unit tests and a JNI-backed Supertonic implementation for Android runtime.
- Add a `TtsController` that synthesizes speech from text and plays it back through the connected Bluetooth SCO route, with status reporting and cancellation.
- Add two new test toggles on the connected monitor/test surface beside the existing echo and STT toggles.
- Make all four test modes mutually exclusive: enabling any one disables the other three, and any subset may be off.
- Add a **TTS test** toggle. Below the toggle, render an editable text box prefilled with a short default phrase, plus the Supertonic controls the integration exposes (voice style, language, total steps / quality, speed).
- Add an **STT↔TTS test** toggle. Below the toggle, render status/transcript text. No text box. The PTT is held to record (same SCO/PCM path as the STT test), the captured audio is transcribed with Parakeet, the resulting transcript is synthesized with Supertonic, and the synthesized audio plays back through the headset speaker.
- Keep all TTS synthesis local to the device. No audio, text, or transcript leaves the device.

## Capabilities

### New Capabilities
- `ptt-tts-test`: Defines the connected-device text-to-speech test mode, Supertonic model readiness, 4-way mutual exclusion with echo/STT/STT↔TTS test modes, text-input-driven synthesis, Supertonic-controllable parameters, local playback through the headset SCO route, and status display.
- `ptt-stt-tts-test`: Defines the connected-device speech-to-text-to-speech round-trip test mode, 4-way mutual exclusion with echo/STT/TTS test modes, PTT-driven recording (reusing the STT capture path), Parakeet transcription feeding Supertonic synthesis, local playback of the synthesized speech through the headset SCO route, and transcript/playback status display.

### Modified Capabilities
- `ptt-stt-test`: The echo/STT two-way mutual exclusion requirement broadens to 4-way mutual exclusion across echo, STT, TTS, and STT↔TTS test modes, and the STT test control requirement is updated to describe the expanded set of toggles on the connected monitor/test surface.

## Impact

- Android app build: adds Supertonic ONNX assets (~415 MB Hugging Face model; the four ONNX files plus `tts.json`, `unicode_indexer.json`, and all preset voice style JSONs), a second Rust JNI library (`libsubspace_supertonic.so`), and reuses the existing ONNX Runtime Android native dependency already wired for Parakeet.
- Nix devshell: no new tooling required; the existing Rust/NDK/cargo-ndk toolchain already builds `subspace-parakeet` and will build the new crate with the same `cargo-ndk` + `preBuild` Gradle wiring.
- Runtime startup: initializes Supertonic model loading in the foreground service lifecycle alongside Parakeet, without blocking first UI composition.
- Audio/service layer: reuses the existing SCO route and `AndroidPcmOutput`; adds a `TtsController` and an `SttTtsController` (round-trip) owned by `PttForegroundService`, and a `TtsSynthesizer` port with fake and JNI implementations.
- Audio playback: Supertonic outputs 44.1 kHz mono `f32` PCM; the TTS playback path resamples to the SCO output rate (8/16 kHz) before writing to the headset, because A2DP remains a non-goal and SCO is the only available headset output route.
- UI layer: extends the connected monitor/test screen and `PttUiActions` with TTS enable/disable, STT↔TTS enable/disable, a TTS text input with prefilled phrase, Supertonic parameter controls (voice, language, quality, speed), and status/transcript display.
- Service state: extends `MonitorState` with TTS and STT↔TTS enabled flags, TTS model readiness/status, TTS playback status, STT↔TTS round-trip status/transcript, and the current TTS text/parameters.
- Mutual exclusion: `PttForegroundService.setEchoEnabled`, `setSttEnabled`, and the new `setTtsEnabled`/`setSttTtsEnabled` each disable and cancel the other three modes; PTT press/release is dispatched to at most one active controller.
- Tests: adds controller/state tests for 4-way mutual exclusion, TTS synthesis + playback success/failure/cancellation, STT→TTS round-trip success/empty-audio/transcription-failure/synthesis-failure/cancellation, f32→SCO resampling, and parameter propagation.
