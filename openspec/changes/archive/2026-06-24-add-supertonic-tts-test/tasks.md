## 1. Native Build And Model Assets

- [x] 1.1 Add a `subspace-supertonic` Rust crate to the existing `rust/` workspace with `crate-type = ["cdylib"]`, pinning the same `ort = "=2.0.0-rc.12"` (`load-dynamic`, `ndarray`, `tls-rustls-no-provider`) as `subspace-parakeet`, plus `ndarray`, `rand`/`rand_distr`, `serde`/`serde_json`, `unicode-normalization`, `regex`, `jni`, `once_cell`, `thiserror`, and `hound` (dev-only).
- [x] 1.2 Add a Gradle task that downloads Supertonic 3 assets (`duration_predictor.onnx`, `text_encoder.onnx`, `vector_estimator.onnx`, `vocoder.onnx`, `tts.json`, `unicode_indexer.json`) and all available voice styles (`F1.json`, `F2.json`, `F3.json`, `F4.json`, `F5.json`, `M1.json`, `M2.json`, `M3.json`, `M4.json`, `M5.json`) from `huggingface.co/Supertone/supertonic-3` with SHA-256 verification into generated build assets.
- [x] 1.3 Wire generated Supertonic assets into the app asset source set without committing downloaded model binaries.
- [x] 1.4 Extend the existing Gradle `preBuild` native-build wiring to compile `subspace-supertonic` for the active Android ABIs using the Nix-provided NDK and cargo-ndk, producing `libsubspace_supertonic.so` packaged into the APK.
- [x] 1.5 Verify the app ships exactly one ONNX Runtime native library (`libonnxruntime.so`) shared by both native crates.

## 2. Native Supertonic Bridge

- [x] 2.1 Implement a `SupertonicAssetExtractor` (Kotlin) that extracts Supertonic assets from APK assets to app files storage with a completion marker, mirroring `ParakeetAssetExtractor`.
- [x] 2.2 Port the reference `helper.rs` text preprocessing (`preprocess_text`, `UnicodeProcessor`, `text_to_unicode_values`), chunking (`chunk_text`, `split_sentences`), mask helpers, and noisy-latent sampling into the `subspace-supertonic` crate, adapted to the JNI surface.
- [x] 2.3 Implement a process-local singleton `SupertonicEngine` holding the four ONNX sessions, config, and Unicode processor, loaded from a model directory.
- [x] 2.4 Implement synchronized load state for idle, loading, ready, and failed states, mirroring the Parakeet engine.
- [x] 2.5 Expose JNI entry points callable from Kotlin: `nativeInit`, `nativeStartLoad`, `nativeLoadStatus`, `nativeLoadError`, and `nativeSynthesize` (accepts text + voice style path + language + total steps + speed; returns a typed outcome JSON with `f32` samples or an error).
- [x] 2.6 Implement the four-session inference flow (duration predictor, text encoder, vector estimator denoising loop, vocoder) and return 44.1 kHz mono `f32` samples.
- [x] 2.7 Ensure native TTS never performs network I/O and never persists text or synthesized audio.

## 3. Kotlin TTS Audio Core

- [x] 3.1 Add TTS state fields to `MonitorState`: `ttsEnabled`, `ttsModelStatus`, `ttsStatus`, `ttsText` (prefilled default phrase), `ttsVoiceStyle`, `ttsLang`, `ttsTotalSteps`, `ttsSpeed`, plus `sttTtsEnabled`, `sttTtsStatus`, `sttTtsTranscript`.
- [x] 3.2 Add a `TtsSynthesizer` port with a `FakeTtsSynthesizer` for unit tests and a JNI-backed `SupertonicJniSynthesizer` for Android runtime, mirroring `SttTranscriber`/`ParakeetJniTranscriber`.
- [x] 3.3 Add a `SynthesisOutcome` sealed type (success with `FloatArray` samples, model-not-ready, failure, empty-text) mirroring `TranscriptionOutcome`.
- [x] 3.4 Add a `TtsAudio` helper that converts Supertonic 44.1 kHz mono `f32` samples to PCM16 and resamples to the SCO output rate (8/16 kHz) using linear interpolation; handle empty output.
- [x] 3.5 Implement `TtsController` using `ScoRoute` and `PcmOutput`: acquire SCO, synthesize off the main thread with current text/parameters, resample, play through the headset, release SCO after a keep-warm window, and report status at each stage.
- [x] 3.6 Implement `TtsController` handling for empty text, model-not-ready, synthesis failure, playback completion, and cancellation.
- [x] 3.7 Implement `SttTtsController` composing the STT capture flow (`ScoRoute`, `AudioRecorder`, ready beep, max-duration retention) with transcription (`SttTranscriber`) then synthesis (`TtsSynthesizer`) then resample+playback.
- [x] 3.8 Implement `SttTtsController` handling for early release, empty audio, empty transcript, transcription failure, synthesis failure, model-not-ready at either stage, playback completion, and cancellation.

## 4. Service Integration

- [x] 4.1 Instantiate the Supertonic synthesizer, `TtsController`, and `SttTtsController` in `PttForegroundService`, with graceful fallback when the native library is unavailable.
- [x] 4.2 Start Supertonic model loading from service startup alongside Parakeet, without blocking UI collection or readiness checks.
- [x] 4.3 Collect TTS model status, TTS controller status, and STT↔TTS controller status/transcript into `MonitorState`.
- [x] 4.4 Add `setTtsEnabled` service behavior that disables/cancels echo, STT, and STT↔TTS when TTS is enabled.
- [x] 4.5 Add `setSttTtsEnabled` service behavior that disables/cancels echo, STT, and TTS when STT↔TTS is enabled.
- [x] 4.6 Update `setEchoEnabled` and `setSttEnabled` to disable/cancel TTS and STT↔TTS in addition to each other.
- [x] 4.7 Route PTT press/release events to exactly one active test controller based on service-owned 4-way mode state; in TTS mode PTT press triggers synthesis (no recording) and PTT release is a no-op for the controller.
- [x] 4.8 Cancel active or pending TTS and STT↔TTS work on serial disconnect, service destroy, and mode disable.

## 5. UI Integration

- [x] 5.1 Extend `PttUiActions` with `setTtsEnabled`, `setSttTtsEnabled`, `setTtsText`, `setTtsVoiceStyle`, `setTtsLang`, `setTtsTotalSteps`, and `setTtsSpeed`.
- [x] 5.2 Add a TTS test toggle to `MonitorScreen` beside the echo and STT controls, with an editable text box prefilled with a short default phrase and Supertonic parameter controls (voice style, language, total steps/quality, speed) below the toggle.
- [x] 5.3 Add a "Synthesize" control below the TTS toggle that requests synthesis with the current text/parameters.
- [x] 5.4 Add an STT↔TTS test toggle to `MonitorScreen` with a status/transcript area and no text input box below the toggle.
- [x] 5.5 Render TTS status (idle/synthesizing/playing/error/empty-text) and STT↔TTS status (idle/recording/transcribing/synthesizing/playing/transcript/empty-audio/empty-transcript/error) in the respective cards.
- [x] 5.6 Ensure UI toggle state reflects service-owned 4-way mutual exclusion after any toggle changes.
- [x] 5.7 Keep existing echo timing controls and STT transcript behavior unchanged when TTS and STT↔TTS are disabled.

## 6. Tests And Validation

- [x] 6.1 Add unit tests for `TtsController` synthesis success, empty text, model-not-ready, synthesis failure, playback completion, and cancellation.
- [x] 6.2 Add unit tests for `SttTtsController` round-trip success, early release, empty audio, empty transcript, transcription failure, synthesis failure, model-not-ready, and cancellation.
- [x] 6.3 Add unit tests proving echo, STT, TTS, and STT↔TTS cannot coexist through service/controller state transitions (4-way mutual exclusion).
- [x] 6.4 Add unit tests for `TtsAudio` f32→PCM16 conversion and 44.1 kHz → 8/16 kHz resampling, including empty input.
- [x] 6.5 Add unit tests for Supertonic parameter propagation (voice, language, total steps, speed) from UI actions to the synthesizer call.
- [x] 6.6 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 6.7 Run `nix develop --no-write-lock-file -c gradle build` or document any model-download/device-resource blocker.
- [x] 6.8 Manually verify on `B02PTT-FF01` that echo and STT still work, TTS plays synthesized speech through the headset, STT↔TTS records voice on PTT and plays back synthesized speech through the headset, and enabling any toggle disables the other three.
