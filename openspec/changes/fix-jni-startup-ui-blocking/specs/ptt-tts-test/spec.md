## MODIFIED Requirements

### Requirement: Supertonic model loads on app startup
The system SHALL start loading the Supertonic 3 text-to-speech model after app startup without performing any JNI call, native library load, ONNX Runtime environment creation, or model asset file I/O on the Android main thread. Asset extraction, native library loading, ONNX Runtime initialization, and load-start SHALL all happen on a background thread, and the app SHALL remain responsive while loading continues, alongside the existing Parakeet model load.

#### Scenario: Startup begins model loading off the main thread
- **WHEN** the app process starts and the foreground service is created by the launched app
- **THEN** the system launches a background coroutine to initialize the Supertonic native bridge
- **AND** the foreground service `onCreate` returns without waiting for the initialization to complete
- **AND** the system does not call `System.loadLibrary`, `nativeInit`, or `nativeStartLoad` on the main thread
- **AND** the system does not perform model asset file I/O on the main thread
- **AND** the app remains responsive while initialization and loading continue

#### Scenario: Warm-run initialization does not block the main thread
- **WHEN** the app process starts on a device where the Supertonic model assets have already been extracted to files storage (marker matches the current asset version)
- **THEN** the foreground service `onCreate` completes without performing any blocking JNI call or file I/O on the main thread
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the TTS model readiness field shows Loading until the background initialization completes
- **AND** the TTS model readiness field transitions to Ready or Failed after the background initialization completes

#### Scenario: First-run asset extraction does not block the main thread
- **WHEN** the app process starts on a device where the Supertonic model assets have not yet been extracted to files storage (marker is missing or does not match the current asset version)
- **THEN** the system performs the asset extraction on a background thread
- **AND** the foreground service `onCreate` returns without waiting for the extraction to complete
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the app remains responsive (UI scrolling, navigation) while the extraction and subsequent model loading continue
- **AND** the TTS model readiness field shows Loading until both the extraction and the model load complete

#### Scenario: Model becomes ready
- **WHEN** Supertonic model loading completes successfully on the background thread
- **THEN** the system marks TTS model readiness as ready
- **AND** subsequent TTS test syntheses can run without reloading the model

#### Scenario: Model load fails
- **WHEN** Supertonic model loading fails on the background thread
- **THEN** the system marks TTS model readiness as failed
- **AND** the connected monitor/test surface shows the model load error instead of silently ignoring TTS requests

#### Scenario: Initialization failure leaves controllers unavailable without crashing
- **WHEN** the Supertonic native library fails to load or the ONNX Runtime initialization fails on the background thread
- **THEN** the system logs the failure without crashing the foreground service
- **AND** the TTS controller, STT↔TTS controller, and SystemAnnouncer are not constructed
- **AND** the TTS model readiness poller is not started
- **AND** the connected monitor/test surface continues to show Loading for the TTS model readiness field

## ADDED Requirements

### Requirement: TTS synthesis request does not perform asset file I/O on the main thread
The system SHALL resolve the Supertonic voice style file path from the model directory captured during the background initialization, without re-invoking the asset extractor on the main thread on each synthesis request.

#### Scenario: Synthesis request reuses cached model directory
- **WHEN** the user requests TTS synthesis (by tapping the synthesize control or pressing PTT) and the Supertonic model has finished initializing
- **THEN** the system resolves the voice style file path from the model directory stored during the background initialization
- **AND** the system does not read the asset version marker file on the main thread
- **AND** the system does not perform any asset extraction file I/O on the main thread

#### Scenario: Synthesis request before initialization completes
- **WHEN** the user requests TTS synthesis before the Supertonic model has finished initializing
- **THEN** the system ignores the request without performing asset file I/O on the main thread
- **AND** the TTS controller remains unavailable until the background initialization completes