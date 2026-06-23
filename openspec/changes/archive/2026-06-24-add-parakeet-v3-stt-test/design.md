## Context

Subspace is a Kotlin/Compose Android app that keeps B02PTT-FF01 serial monitoring in `PttForegroundService`. The connected monitor screen already exposes an echo test. The echo path acquires the Bluetooth SCO route, records mono PCM16 through `InMemoryRecorder`, and either plays the result back or reports audio status.

Parakeet v3 inference is not present in the app. The referenced Android transcription app demonstrates a working Parakeet TDT 0.6b v3 integration using a Rust/JNI layer, `transcribe-rs`, ONNX Runtime Android, int8 Parakeet ONNX assets, asset extraction into app files storage, and a process-wide loaded engine guarded by synchronization.

Current repository constraints:

- Android min SDK is 31 and target SDK is 35.
- Existing development tooling comes from the Nix flake.
- The flake currently excludes Android NDK and Rust tooling.
- Existing audio capture prefers 16 kHz mono PCM16 and falls back to 8 kHz.
- The foreground service already declares connected-device and microphone foreground service types.

## Goals / Non-Goals

**Goals:**

- Load the Parakeet v3 model after app startup without blocking initial UI rendering.
- Keep the model available as a shared local STT engine while the app process is alive.
- Add an STT test mode beside the echo test on the connected monitor/test surface.
- Enforce that echo and STT test modes are mutually exclusive.
- Reuse the existing PTT press/release semantics and SCO recording path for STT capture.
- Convert recorded PCM to Parakeet-compatible 16 kHz mono `f32` samples before inference.
- Display the latest transcript or STT status in the UI.
- Keep transcription fully on-device.

**Non-Goals:**

- Do not add Android system speech-recognition provider integration.
- Do not add live subtitles, IME voice typing, background transcription outside the current PTT service, or channel-message sending.
- Do not stream partial transcripts; the STT test transcribes one captured PTT utterance after release.
- Do not upload audio or transcripts to any network service.
- Do not persist recorded audio or transcript history beyond in-memory UI state.
- Do not change B02PTT-FF01 serial parsing or hardware mode behavior.
- Do not make STT replace the existing echo test.

## Decisions

### Use Rust/JNI `transcribe-rs` for Parakeet inference

Implement a native module based on the reference app pattern:

- Load `onnxruntime` and a Subspace native library.
- Extract Parakeet assets from APK assets to app files storage because ONNX Runtime and `transcribe-rs` require filesystem paths.
- Keep a singleton `ParakeetEngine` loaded with `ParakeetModelParams::int8()`.
- Expose JNI methods to initialize/load the model and transcribe `float[]` or direct sample buffers.
- Serialize access to the engine because inference mutates engine/model state.

Alternatives considered:

- Kotlin-only ONNX Runtime integration. Rejected for this change because it requires implementing Parakeet preprocessing, tokenizer/decoder, timestamp handling, and model orchestration in Kotlin. That duplicates the reference app's `transcribe-rs` logic and raises correctness risk.
- Embedding the reference app wholesale. Rejected because Subspace only needs a PTT test transcription backend, not voice IME, Android `RecognitionService`, live subtitles, or file transcription surfaces.

### Download model files during the Gradle build, not commit them

Add a Gradle model download task with SHA-256 verification for the int8 Parakeet ONNX files and tokenizer/config files. Use generated asset directories under `build/` and wire them into the app asset source set, so large generated binaries are not tracked in Git.

Alternatives considered:

- Commit model assets. Rejected because the model is hundreds of MB and would bloat the repository.
- Download model files at first app launch. Rejected because the feature is explicitly local/offline once installed and startup readiness would depend on network availability.
- Add a Play Asset Delivery module immediately. Deferred because the current acceptance flow uses debug APK installation; release packaging size optimization can be added later if needed.

### Extend the Nix devshell for native Android builds

Update the flake to include Android NDK, Rust, Cargo, and cargo-ndk tooling in the repository devshell. Gradle native build tasks must use the Nix-provided SDK/NDK paths and repository-local Gradle state.

Alternatives considered:

- Require developers to install Rust/NDK globally. Rejected by repository policy.
- Prebuild and commit `.so` files. Rejected because it makes native sources non-authoritative and complicates reproducible builds.

### Add an STT controller instead of rewriting echo

Create a focused `SttController` that uses the existing `ScoRoute` and `AudioRecorder` ports. `PttForegroundService` owns both controllers and enforces the active test mode when toggles change.

Controller behavior:

- On PTT press while enabled, acquire SCO and start recording with the same timing as the default echo path: ready beep first, then recording.
- On PTT release, stop recording, normalize samples, invoke the transcriber off the main thread, and update STT status/transcript.
- On cancellation, serial disconnect, or service destroy, stop recording and release resources.

Alternatives considered:

- Refactor echo and STT into a generic audio-test state machine first. Deferred to keep implementation smaller; shared abstractions can be extracted after both modes exist and tests identify stable common behavior.
- Reuse `EchoController` by adding branches for STT. Rejected because playback and transcription lifecycles have different statuses and dependencies.

### Represent mutual exclusion in service-owned state

Keep UI toggles as commands, not authority. `PttForegroundService.setEchoEnabled(true)` disables STT and clears/cancels active STT work. `setSttEnabled(true)` disables echo and clears/cancels active echo work. Turning either off leaves both modes off if the other is already off.

Alternatives considered:

- Enforce mutual exclusion only in Compose. Rejected because serial PTT events are handled in the service and must never dispatch to both controllers.
- Replace booleans with only a single enum in UI state. Viable, but retaining existing `echoEnabled` and adding `sttEnabled` minimizes UI churn while service logic prevents invalid combinations.

### Normalize audio before native inference

Convert `RecordedPcm.samples` from signed 16-bit PCM to normalized `f32` samples in `[-1.0, 1.0]`. If the recorder falls back to 8 kHz or any non-16 kHz rate, resample to 16 kHz before calling Parakeet. Empty recordings do not call inference.

Alternatives considered:

- Require 16 kHz and fail on any other rate. Rejected because the existing recorder explicitly supports fallback rates.
- Move all recording into Rust. Rejected because Subspace already has Android audio routing through SCO and `AudioRecord` integrated with PTT events.

## Risks / Trade-offs

- Large model assets increase APK size and build time -> Use int8 assets, Gradle checksum caching, and keep files generated outside Git.
- Native build complexity increases -> Keep native surface small, use the Nix devshell as the only supported toolchain, and verify with `nix develop -c gradle build`.
- Startup model loading can consume CPU/RAM -> Load on a background dispatcher/native thread, expose loading status, and avoid blocking initial UI.
- Parakeet inference may outlive an activity bind/unbind -> Own model and STT state in `PttForegroundService`, not in Compose state.
- SCO audio can be unavailable or disconnected during capture -> Reuse existing readiness and SCO acquisition failures, show STT error status, and release/cancel on serial disconnect.
- Concurrent PTT sessions or rapid toggle changes can race -> Serialize controller operations and let service-owned mode decide which controller receives each PTT event.
- Transcription accuracy depends on headset SCO bandwidth -> Treat this feature as a test/diagnostic surface, not production dictation.

## Migration Plan

1. Add native build and model asset plumbing while keeping the existing app behavior unchanged.
2. Add the STT transcriber port and native implementation with unit-testable fake transcriber support.
3. Add STT state and controller behavior behind a disabled-by-default toggle.
4. Wire service PTT dispatch and mutual exclusion.
5. Add UI controls and transcript display.
6. Validate build and unit tests through the Nix devshell.

Rollback is removing the STT toggle/controller/native build wiring. Existing echo and serial behavior remains structurally separate and should continue to work if the STT path is not enabled.

## Open Questions

- Release packaging strategy for model delivery is not finalized. The implementation should prioritize debug APK/device testing first and avoid committing large generated model files.
