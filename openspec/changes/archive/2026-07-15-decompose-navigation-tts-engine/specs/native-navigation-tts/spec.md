## ADDED Requirements

### Requirement: Behavior-preserving navigation TTS decomposition
The system SHALL separate navigation TTS contracts, identity declarations, deterministic offline voice selection, and WAV normalization from the stateful `NavigationTtsEngine` implementation without changing declaration names, package membership, visibility, signatures, defaults, result shapes, or observable runtime behavior. `NavigationTtsEngine` SHALL remain the sole mutable owner of Android `TextToSpeech` instances, coroutine scopes and jobs, mutex-protected state, navigation generations, pending-operation registry transitions, recovery, transient files, and stop/shutdown sequencing.

#### Scenario: Existing caller uses decomposed contracts
- **WHEN** an existing production or test caller constructs `NavigationTtsEngine`, supplies `TextToSpeechFactory` or `StateLossCallback`, or consumes preparation or synthesis results after decomposition
- **THEN** the caller SHALL use the same package-qualified declaration names and signatures as before the decomposition
- **AND** no compatibility alias, forwarding declaration, feature flag, or alternate engine implementation SHALL be required

#### Scenario: Stateful request behavior remains unchanged
- **WHEN** prepare, navigation synthesis, direct PCM playback, supersession, cancellation, recovery, or shutdown executes after the declarations are decomposed
- **THEN** callback acceptance, callback rejection, result classification, state-loss notification, cancellation propagation, and resource-action ordering SHALL match the pre-decomposition behavior
- **AND** each `TextToSpeech` instance, transient file, pending operation, playback job, and recovery attempt SHALL retain exactly one authoritative owner

#### Scenario: Late callback remains non-authoritative
- **WHEN** a callback arrives for an unregistered operation, an old engine epoch, a superseded navigation generation, or an engine that has already been shut down
- **THEN** the decomposed implementation SHALL reject it under the same ownership rules as the pre-decomposition implementation
- **AND** it SHALL NOT deliver stale PCM or create a second terminal side effect

#### Scenario: Factory construction failure remains equivalent
- **WHEN** the injected `TextToSpeechFactory` fails while constructing an engine instance
- **THEN** the resulting failure propagation or classification and cleanup actions SHALL match the characterized pre-decomposition behavior
- **AND** the structural extraction SHALL NOT introduce a fallback engine or retry path

### Requirement: Deterministic policy equivalence
Extracted offline voice selection and WAV normalization SHALL produce results equivalent to the pre-decomposition implementation for the same inputs. Equivalence SHALL include selected voice identity and selection failure category; normalized sample rate and exact PCM sample values; typed unsupported-format, unsupported-channel, empty-PCM, malformed-input, and file-I/O failures; and all externally visible `RecordedPcm` fields.

#### Scenario: Offline voice candidates are evaluated after extraction
- **WHEN** the extracted selector receives a fixed installed-voice set and fixed `isLanguageAvailable` and `setVoice` results
- **THEN** it SHALL accept and reject the same candidates as the pre-decomposition selector
- **AND** it SHALL apply latency, quality, locale-tag, and voice-name ordering identically
- **AND** it SHALL return the same selected voice or typed failure

#### Scenario: Supported WAV fixture is normalized after extraction
- **WHEN** the extracted normalizer receives a fixed supported mono or stereo PCM8, PCM16, or PCM-float WAV fixture
- **THEN** it SHALL produce a non-empty mono `RecordedPcm` at 16 kHz with the exact sample values produced before decomposition
- **AND** it SHALL preserve the existing downmix, linear-resampling, clamping, scaling, and rounding behavior

#### Scenario: Invalid WAV fixture is classified after extraction
- **WHEN** the extracted normalizer receives unsupported encoding, unsupported channel count, empty PCM, malformed input, or a file-I/O failure
- **THEN** it SHALL return the same typed renderer-infrastructure failure as the pre-decomposition implementation
- **AND** it SHALL NOT emit playable PCM

#### Scenario: Direct PCM request overlaps stateful engine work
- **WHEN** `requestPcm` overlaps an active synthesis, probe, recovery, supersession, cancellation, or shutdown boundary represented by the existing engine harness
- **THEN** job ownership, terminal result, playback delivery, and cleanup ordering SHALL match the characterized pre-decomposition behavior
- **AND** extracted deterministic policies SHALL NOT acquire independent coroutine or lifecycle ownership
