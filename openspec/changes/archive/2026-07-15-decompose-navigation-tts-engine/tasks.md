## 1. Characterize Existing Behavior

- [x] 1.1 Add exact-sample WAV normalization tests covering representative mono and stereo PCM8, PCM16, and PCM-float fixtures at native and resampled rates.
- [x] 1.2 Add normalization failure-mapping tests for unsupported encoding, unsupported channel count, empty PCM, malformed WAV input, and file-I/O failure.
- [x] 1.3 Add `NavigationTtsEngine` tests that pin current factory-construction failure handling and late-callback behavior after shutdown.
- [x] 1.4 Add focused `requestPcm` overlap tests for active synthesis, probe or recovery where supported by the existing engine harness, supersession, cancellation, and shutdown.
- [x] 1.5 Run the modified navigation TTS tests against the current single-file implementation and record a passing behavioral baseline before moving production declarations.

## 2. Extract Contracts and Identities

- [x] 2.1 Create `NavigationTtsContracts.kt` and move the failure hierarchy, configuration, preparation and synthesis results, state-loss callback, `TextToSpeechFactory`, and default factory without changing declaration text or behavior.
- [x] 2.2 Run the focused `NavigationTtsEngineTest` set and compile the affected Kotlin source after the contracts move.
- [x] 2.3 Create `NavigationTtsIdentity.kt` and move engine epoch, navigation generation, attempt token, utterance identity, callback terminal result, and pending-operation declarations without moving pending-registry ownership.
- [x] 2.4 Run the focused `NavigationTtsEngineTest` set and compile the affected Kotlin source after the identity move.

## 3. Extract Deterministic Policies

- [x] 3.1 Create `NavigationVoiceSelector.kt` and move `VoiceSelectionResult` and `selectOfflineEnglishVoice` with the existing validation, ordering, selection, and failure mapping unchanged.
- [x] 3.2 Run the deterministic voice-selection tests and focused engine tests after the selector move.
- [x] 3.3 Create `NavigationWavNormalizer.kt` and move WAV failure mapping, `NormalizeResult`, and `normalizeWavToScoPcm` with decoding, downmixing, resampling, PCM conversion, and typed failures unchanged.
- [x] 3.4 Run the exact-sample normalization tests, WAV failure-mapping tests, and focused engine tests after the normalizer move.

## 4. Verify Behavioral Equivalence

- [x] 4.1 Confirm `NavigationTtsEngine.kt` still owns every mutex, coroutine scope and job, live `TextToSpeech` instance, navigation generation, pending-operation registry transition, recovery transition, transient-file action, and stop/shutdown action listed in the design.
- [x] 4.2 Confirm every moved declaration retains its original package, name, visibility, annotations, defaults, signatures, and one-and-only-one definition with no alias, forwarding declaration, feature flag, or alternate engine.
- [x] 4.3 Run the focused unit suites for `NavigationTtsEngine`, `WavPcmReader`, and `TtsAudio`, then compile the debug application source.
- [x] 4.4 On `B02PTT-FF01`, verify bootstrap with the installed offline voice plus menu-entry, channel-selection, boundary-beep, confirmation, supersession, and teardown behavior through the existing exact target-RSM playback path.
