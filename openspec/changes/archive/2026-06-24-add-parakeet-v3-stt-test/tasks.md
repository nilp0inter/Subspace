## 1. Native Build And Model Assets

- [x] 1.1 Update the Nix flake devshell to provide Android NDK, Rust, Cargo, and cargo-ndk from Nix.
- [x] 1.2 Add a Rust native library workspace for the Subspace Parakeet bridge.
- [x] 1.3 Add native dependencies for JNI, ONNX Runtime dynamic loading, synchronization, logging, and `transcribe-rs` Parakeet support.
- [x] 1.4 Add the Android ONNX Runtime dependency and native `.so` packaging configuration to the app Gradle module.
- [x] 1.5 Add a Gradle task that downloads Parakeet v3 int8 ONNX, vocab, and config assets with SHA-256 verification into generated build assets.
- [x] 1.6 Wire generated Parakeet assets into the app asset source set without committing downloaded model binaries.
- [x] 1.7 Wire a Gradle native build task into `preBuild` using the Nix-provided NDK and ONNX Runtime headers/libs.

## 2. Native Parakeet Bridge

- [x] 2.1 Implement asset extraction from APK assets to app files storage with a completion marker.
- [x] 2.2 Implement a process-local singleton Parakeet engine loaded with int8 model parameters.
- [x] 2.3 Implement synchronized model load state for idle, loading, ready, and failed states.
- [x] 2.4 Expose JNI initialization and startup load entry points callable from Kotlin.
- [x] 2.5 Expose a JNI transcription entry point that accepts normalized 16 kHz mono samples and returns text or a typed error.
- [x] 2.6 Ensure native transcription never performs network I/O and never persists captured audio.

## 3. Kotlin STT Audio Core

- [x] 3.1 Add STT state fields to app model state for enabled state, model readiness/status, active recording/transcribing status, latest transcript, and errors.
- [x] 3.2 Add a `SttTranscriber` port with a fake implementation for unit tests and a JNI-backed Parakeet implementation for Android runtime.
- [x] 3.3 Add PCM normalization from signed PCM16 to `f32` samples in `[-1.0, 1.0]`.
- [x] 3.4 Add 16 kHz resampling for recordings whose sample rate is not 16 kHz.
- [x] 3.5 Implement `SttController` using the existing `ScoRoute`, `AudioRecorder`, and ready-beep output flow.
- [x] 3.6 Implement STT controller handling for early release, empty audio, max-duration retention, transcription success, transcription failure, and cancellation.

## 4. Service Integration

- [x] 4.1 Instantiate the Parakeet transcriber and STT controller in `PttForegroundService`.
- [x] 4.2 Start Parakeet model loading from service startup without blocking UI collection or readiness checks.
- [x] 4.3 Collect STT controller status into `MonitorState`.
- [x] 4.4 Add `setSttEnabled` service behavior that disables/cancels echo when STT is enabled.
- [x] 4.5 Update `setEchoEnabled` service behavior so enabling echo disables/cancels STT.
- [x] 4.6 Route PTT press/release events to exactly one active test controller based on service-owned mode state.
- [x] 4.7 Cancel active or pending STT recording work on serial disconnect, service destroy, and STT mode disable.

## 5. UI Integration

- [x] 5.1 Extend `PttUiActions` with `setSttEnabled`.
- [x] 5.2 Add an STT test toggle to the connected monitor/test screen beside the echo test controls.
- [x] 5.3 Render the latest STT transcript or idle/loading/recording/transcribing/error state in a text box below the STT toggle.
- [x] 5.4 Ensure UI toggle state reflects service-owned mutual exclusion after either toggle changes.
- [x] 5.5 Keep existing echo timing controls and audio status behavior unchanged when STT is disabled.

## 6. Tests And Validation

- [x] 6.1 Add unit tests for STT controller PTT success, early release, empty audio, max-duration retention, transcriber failure, and cancellation.
- [x] 6.2 Add unit tests proving echo and STT cannot both be enabled through service/controller state transitions.
- [x] 6.3 Add unit tests for PCM16 normalization and non-16 kHz resampling.
- [x] 6.4 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 6.5 Run `nix develop --no-write-lock-file -c gradle build` or document any model-download/device-resource blocker.
- [x] 6.6 Manually verify on `B02PTT-FF01` that echo still works, STT records on PTT, transcription appears after release, and enabling either toggle disables the other.
