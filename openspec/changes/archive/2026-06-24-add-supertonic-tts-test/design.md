## Context

Subspace is a Kotlin/Compose Android app that keeps B02PTT-FF01 serial monitoring in `PttForegroundService`. The connected monitor screen (`MonitorScreen.kt`) already exposes two mutually exclusive test toggles: an echo test (`EchoController`) and an STT test (`SttController` backed by the `subspace-parakeet` Rust/JNI bridge). The service owns all controllers, enforces mutual exclusion in service-owned state, and routes PTT press/release to at most one active controller (`PttForegroundService.kt:308-395`).

On-device STT inference is already implemented via a Rust/JNI bridge in `rust/subspace-parakeet/`:
- `lib.rs` exposes JNI entry points (`nativeInit`, `nativeStartLoad`, `nativeLoadStatus`, `nativeLoadError`, `nativeTranscribe`).
- A process-local singleton engine is guarded by `Lazy<Mutex<OnceLock<ParakeetEngine>>>`.
- ONNX Runtime is dynamically loaded from `libonnxruntime.so` in the app's `nativeLibraryDir`.
- Parakeet int8 ONNX/tokenizer assets are extracted from APK assets to app files storage by `ParakeetAssetExtractor` and verified by a completion marker.
- A Gradle task downloads model assets with SHA-256 verification into generated build assets; generated binaries are not committed to Git.
- The Nix devshell provides Android NDK, Rust, Cargo, and cargo-ndk, and Gradle native build tasks are wired into `preBuild` using Nix-provided paths.
- Kotlin side: `SttTranscriber` port with `FakeSttTranscriber` (tests) and `ParakeetJniTranscriber` (runtime); `SttController` mirrors `EchoController` lifecycle (SCO acquire, ready beep, `InMemoryRecorder`, max-duration retention, cancellation) and transcribes instead of playing back.

Supertonic 3 is a 99M-parameter on-device multilingual TTS system running via ONNX Runtime. The reference repository (`supertone-inc/supertonic`) ships both Java and Rust inference examples. The Rust example (`rust/src/helper.rs`) uses the `ort` crate (same family as the existing Parakeet bridge), `ndarray`, `rand`/`rand_distr`, `hound`, `serde_json`, `unicode-normalization`, and `regex`. Inference runs four ONNX sessions (duration predictor, text encoder, vector estimator, vocoder) plus a Unicode text processor and a multi-step denoising loop, and outputs 44.1 kHz mono `f32` PCM. The Java example uses `com.microsoft.onnxruntime:onnxruntime` 1.23.1, Jackson, and JTransforms, allocates/frees `OnnxTensor` objects inside the denoising loop, and would introduce a second ONNX Runtime native distribution into the app.

Current repository constraints:
- Android min SDK 31, target SDK 35.
- All development tooling comes from the Nix flake; no global installs.
- The flake already provides Android NDK, Rust, Cargo, and cargo-ndk, and `cargo-ndk` is wired into Gradle `preBuild` for `subspace-parakeet`.
- Audio capture/playback prefers 16 kHz mono PCM16 and can fall back to 8 kHz over Bluetooth SCO.
- A2DP is a documented non-goal; SCO is the only available headset output route.
- The foreground service already declares connected-device and microphone foreground-service types.

## Goals / Non-Goals

**Goals:**

- Load the Supertonic 3 model after app startup without blocking initial UI rendering, alongside Parakeet.
- Keep the Supertonic engine available as a shared local TTS engine while the app process is alive.
- Add a TTS test mode on the connected monitor/test surface that synthesizes speech from an editable text box and plays it through the headset SCO route.
- Expose the Supertonic parameters the integration supports (all available preset voice styles, language, total steps/quality, speed) below the TTS toggle.
- Add an STT↔TTS test mode that records PTT audio, transcribes it with Parakeet, synthesizes speech from the transcript with Supertonic, and plays it back through the headset SCO route.
- Enforce 4-way mutual exclusion across echo, STT, TTS, and STT↔TTS test modes in service-owned state.
- Reuse the existing SCO route, `InMemoryRecorder`, `AndroidPcmOutput`, and PTT press/release dispatch.
- Keep TTS synthesis fully on-device.

**Non-Goals:**

- Do not add streaming TTS, partial audio chunking to the speaker, or realtime low-latency voice conversion.
- Do not add voice cloning, custom voice upload, or Supertone Play/API integration.
- Do not replace the existing echo or STT test modes.
- Do not route TTS audio through A2DP or the phone speaker; playback stays on SCO.
- Do not persist synthesized audio, transcripts, or text history beyond in-memory UI state.
- Do not commit large generated model binaries to Git.
- Do not add Android system TTS-engine integration or IME voice output.
- Do not change B02PTT-FF01 serial parsing or hardware mode behavior.
- Do not expose every Supertonic parameter; only the set the integration chooses (preset voice style, language, total steps, speed). Expression tags and batch mode are not exposed in this change.

## Decisions

### Use Rust/JNI for Supertonic inference, not the Java example

Implement a new `subspace-supertonic` Rust crate in the existing `rust/` workspace, following the same JNI bridge architecture as `subspace-parakeet`:
- Load `onnxruntime` and `libsubspace_supertonic.so`; extract Supertonic assets from APK assets to app files storage.
- Keep a singleton `SupertonicEngine` guarded by `Lazy<Mutex<OnceLock<SupertonicEngine>>>`, loaded with the four ONNX sessions, `tts.json`, and `unicode_indexer.json`.
- Expose JNI methods for init, startup load, load-status polling, load-error reporting, and synthesis. Synthesis accepts text + parameters (voice style path/selection, language, total steps, speed) and returns a typed outcome (success with `f32` samples, model-not-ready, failure, empty-text).
- Serialize access to the engine because the denoising loop mutates session/model state.
- Port the reference `helper.rs` text preprocessing (`preprocess_text`, `UnicodeProcessor`), chunking (`chunk_text`), noisy-latent sampling, and the four-session inference flow into the crate, adapted to the Subspace JNI surface.

Alternatives considered:

- **Kotlin/Java ONNX Runtime integration** (the supertonic `java/ExampleONNX.java` + `Helper.java` path, using `com.microsoft.onnxruntime:onnxruntime` 1.23.1). Rejected for the same reasons the STT change rejected Kotlin-only ONNX: it duplicates reference logic in a second language, forces per-step `OnnxTensor` create/close churn inside the denoising loop, and would add a second ONNX Runtime native distribution (`onnxruntime` AAR ships its own native libs) alongside the `libonnxruntime.so` already used by Parakeet — risking symbol/version clash and doubled runtime memory. It also breaks the established single-language native bridge convention.
- **Embedding the reference Rust example wholesale.** Rejected because Subspace only needs a TTS synthesis backend callable from Kotlin via JNI, not a CLI, WAV writer, file transcription surface, or batch harness.

### Reuse the existing ONNX Runtime native dependency and Nix toolchain

The `subspace-parakeet` crate already pins `ort = "=2.0.0-rc.12"` with `load-dynamic` and dynamically loads the same `libonnxruntime.so` that the app packages. The new `subspace-supertonic` crate pins the same `ort` version and loads the same shared library, so the app ships exactly one ONNX Runtime native library. The existing Nix devshell (Android NDK, Rust, Cargo, cargo-ndk) and the existing Gradle `preBuild` native-build wiring are reused unchanged; only the crate list and JNI class name change.

Alternatives considered:

- **Pinning the example's `ort = "2.0.0-rc.7"`.** Rejected because two `ort` versions in one workspace would force separate ONNX Runtime symbols; keeping one pinned version preserves a single shared `libonnxruntime.so`.
- **Adding a second ONNX Runtime binary for TTS.** Rejected for the same symbol/memory reasons as above.

### Download Supertonic assets during the Gradle build, not commit them

Add a Gradle task that downloads the Supertonic 3 ONNX files (`duration_predictor.onnx`, `text_encoder.onnx`, `vector_estimator.onnx`, `vocoder.onnx`), `tts.json`, `unicode_indexer.json`, and all available preset voice styles (`F1`-`F5`, `M1`-`M5`) from `huggingface.co/Supertone/supertonic-3` with SHA-256 verification into generated build assets, and wire them into the app asset source set. Large generated binaries are not tracked in Git. This mirrors the Parakeet asset-download task already in the build.

Alternatives considered:

- Commit model assets. Rejected: the four ONNX files plus config are hundreds of MB and would bloat the repository.
- Download at first launch. Rejected: the feature is explicitly local/offline once installed and readiness would depend on network availability.
- Play Asset Delivery module. Deferred; the current acceptance flow uses debug APK installation.

### Add a TtsController and an SttTtsController instead of branching existing controllers

Create a focused `TtsController` that uses the existing `ScoRoute` and `AndroidPcmOutput`. It does not record; it synthesizes on demand and plays back. Create a separate `SttTtsController` that composes the existing STT capture flow (`ScoRoute`, `AudioRecorder`, ready beep, max-duration retention, cancellation) with a transcription step (reusing `SttTranscriber`) followed by a synthesis step (using `TtsSynthesizer`) and playback. `PttForegroundService` owns all controllers and enforces the active test mode when toggles change. PTT press/release is dispatched to exactly one active controller.

`TtsController` behavior:
- On synthesis request while enabled: acquire SCO, synthesize off the main thread with the current text/parameters, resample 44.1 kHz → SCO rate, play through `PcmOutput`, release SCO after a keep-warm window, report status at each stage.
- On cancellation, serial disconnect, or service destroy: stop synthesis/playback and release SCO.

`SttTtsController` behavior:
- On PTT press while enabled: acquire SCO, ready beep, start recording (same timing as STT).
- On PTT release: stop recording, normalize samples, transcribe via `SttTranscriber` off the main thread; on success, synthesize via `TtsSynthesizer` off the main thread using the transcript as text; on success, resample and play through the headset; report status at each stage.
- On cancellation, serial disconnect, or service destroy: stop recording/transcription/synthesis/playback and release SCO.

Alternatives considered:

- Refactor echo/STT/TTS/STT↔TTS into a single generic audio-test state machine first. Deferred to keep this change smaller; shared abstractions can be extracted after all four modes exist and tests identify stable common behavior.
- Reuse `SttController` by adding TTS playback branches. Rejected because transcription-only and transcribe-then-synthesize lifecycles have different statuses, dependencies, and cancellation surfaces.
- Drive TTS test via PTT-only. Rejected because the TTS test is text-input-driven and should not require a PTT press; PTT may be used as one trigger, but a synthesize control is also exposed.

### Represent 4-way mutual exclusion in service-owned state

Keep UI toggles as commands, not authority. Add `setTtsEnabled` and `setSttTtsEnabled` to the service and `PttUiActions`. Each `set*Enabled(true)` disables and cancels the other three modes and clears/cancels their active work. The service remains the single authority for which controller receives PTT events. Existing `echoEnabled`/`sttEnabled` booleans are retained; new `ttsEnabled`/`sttTtsEnabled` booleans are added to `MonitorState`.

Alternatives considered:

- Replace the four booleans with a single enum in UI state. Viable and tidier, but it churns the existing echo/STT UI and service code more than adding two booleans. The enum refactor is left as a future cleanup once all four modes are stable.
- Enforce mutual exclusion only in Compose. Rejected because serial PTT events are handled in the service and must never dispatch to more than one controller.

### Resample Supertonic output to the SCO rate before playback

Supertonic outputs 44.1 kHz mono `f32`. The headset SCO route runs at 8 or 16 kHz PCM16. Convert the `f32` output to PCM16, then resample 44.1 kHz → target SCO rate (linear interpolation is acceptable for a test/diagnostic surface) before writing to `PcmOutput`. Empty synthesis output does not play.

Alternatives considered:

- Play 44.1 kHz through the phone speaker. Rejected: A2DP and phone-speaker fallback are non-goals; the test must route through the headset to validate the same playback path the product will use.
- Force SCO to 44.1 kHz. Rejected: Bluetooth SCO does not support 44.1 kHz.
- High-quality polyphase resampler. Deferred; the test surface does not require studio-grade quality and linear interpolation is sufficient to validate routing.

### Trigger TTS synthesis via PTT or a synthesize control

The TTS test is text-input-driven. Expose a "Synthesize" control below the TTS toggle that requests synthesis using the current text/parameters. PTT press while TTS is enabled also triggers synthesis (so the hardware path can be validated), but PTT does not record — it is repurposed as a synthesis trigger in TTS mode. PTT release in TTS mode is a no-op for the controller (playback runs to completion unless cancelled).

Alternatives considered:

- PTT-press-and-hold gates playback duration. Rejected: Supertonic synthesis is not a live microphone path; gating playback on PTT hold would truncate output and confuse the test.
- PTT-only trigger with no on-screen button. Rejected because the text box is already on screen and a visible control makes the test discoverable.

## Risks / Trade-offs

- Large model assets increase APK size and build time → Use Gradle checksum caching, keep files generated outside Git, and ship the available preset voice styles as small JSON assets while keeping model binaries generated.
- Two native crates share one ONNX Runtime → Pin both crates to the same `ort` version and load the same `libonnxruntime.so`; serialize engine access per crate.
- Startup model loading for two models consumes CPU/RAM → Load both on background dispatchers/native threads, expose independent load statuses, and avoid blocking initial UI.
- 44.1 kHz → SCO resampling reduces audio quality → Acceptable for a test/diagnostic surface; document that this is not production TTS playback quality.
- SCO audio can be unavailable or disconnect during synthesis/playback → Reuse existing readiness and SCO acquisition failures, show TTS error status, and release/cancel on serial disconnect.
- Concurrent PTT sessions or rapid toggle changes can race → Serialize controller operations and let service-owned mode decide which controller receives each PTT event.
- Supertonic inference may outlive an activity bind/unbind → Own model and TTS state in `PttForegroundService`, not in Compose state.
- STT↔TTS round-trip latency (record + transcribe + synthesize + resample + play) is high → Treat this as a diagnostic surface, not realtime voice conversion; show status at each stage so the user sees progress.
- Voice style selection includes the currently available preset JSONs (`F1`-`F5`, `M1`-`M5`); additional voice style JSONs can be added later without changing the bridge contract.

## Migration Plan

1. Add the `subspace-supertonic` Rust crate and Gradle asset-download task while keeping existing app behavior unchanged.
2. Add the `TtsSynthesizer` port with fake and JNI implementations, and a `TtsAudio` helper for f32→PCM16 + 44.1 kHz→SCO resampling.
3. Add `TtsController` behind a disabled-by-default toggle.
4. Add `SttTtsController` behind a disabled-by-default toggle, reusing `SttTranscriber` and `TtsSynthesizer`.
5. Wire service PTT dispatch and 4-way mutual exclusion.
6. Add UI controls: TTS toggle + text box + Supertonic parameter controls; STT↔TTS toggle + status/transcript area.
7. Validate build and unit tests through the Nix devshell.
8. Manually verify on `B02PTT-FF01` that echo and STT still work, TTS plays through the headset, and STT↔TTS round-trips voice through the headset.

Rollback is removing the TTS and STT↔TTS toggles/controllers/native crate. Existing echo and STT behavior remains structurally separate and should continue to work if the TTS path is not enabled.

## Open Questions

- Exact on-screen trigger UX for the TTS test (dedicated "Synthesize" button vs. PTT-only vs. both) is finalized in this design as "both, with PTT repurposed as a non-recording trigger". Confirm during implementation that PTT-as-trigger does not confuse users expecting record semantics.
- Release packaging strategy for the additional Supertonic model assets is not finalized; prioritize debug APK/device testing first and avoid committing large generated model files.
- Additional voice styles beyond the currently available preset JSONs are deferred.
