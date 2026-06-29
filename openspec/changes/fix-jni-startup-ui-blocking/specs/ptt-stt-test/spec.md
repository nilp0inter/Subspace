## MODIFIED Requirements

### Requirement: Parakeet model loads on app startup
The system SHALL start loading the Parakeet v3 speech-to-text model after app startup without performing any JNI call, native library load, ONNX Runtime environment creation, or model asset file I/O on the Android main thread. Asset extraction, native library loading, ONNX Runtime initialization, and load-start SHALL all happen on a background thread, and the app SHALL remain responsive while loading continues.

#### Scenario: Startup begins model loading off the main thread
- **WHEN** the app process starts and the foreground service is created by the launched app
- **THEN** the system launches a background coroutine to initialize the Parakeet native bridge
- **AND** the foreground service `onCreate` returns without waiting for the initialization to complete
- **AND** the system does not call `System.loadLibrary`, `nativeInit`, or `nativeStartLoad` on the main thread
- **AND** the system does not perform model asset file I/O on the main thread
- **AND** the app remains responsive while initialization and loading continue

#### Scenario: Warm-run initialization does not block the main thread
- **WHEN** the app process starts on a device where the Parakeet model assets have already been extracted to files storage (marker matches the current asset version)
- **THEN** the foreground service `onCreate` completes without performing any blocking JNI call or file I/O on the main thread
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the STT model readiness field shows Loading until the background initialization completes
- **AND** the STT model readiness field transitions to Ready or Failed after the background initialization completes

#### Scenario: First-run asset extraction does not block the main thread
- **WHEN** the app process starts on a device where the Parakeet model assets have not yet been extracted to files storage (marker is missing or does not match the current asset version)
- **THEN** the system performs the asset extraction on a background thread
- **AND** the foreground service `onCreate` returns without waiting for the extraction to complete
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the app remains responsive (UI scrolling, navigation) while the extraction and subsequent model loading continue
- **AND** the STT model readiness field shows Loading until both the extraction and the model load complete

#### Scenario: Model becomes ready
- **WHEN** Parakeet model loading completes successfully on the background thread
- **THEN** the system marks STT model readiness as ready
- **AND** subsequent STT test recordings can be transcribed without reloading the model

#### Scenario: Model load fails
- **WHEN** Parakeet model loading fails on the background thread
- **THEN** the system marks STT model readiness as failed
- **AND** the connected monitor/test surface shows the model load error instead of silently ignoring STT requests

#### Scenario: Initialization failure leaves controllers unavailable without crashing
- **WHEN** the Parakeet native library fails to load or the ONNX Runtime initialization fails on the background thread
- **THEN** the system logs the failure without crashing the foreground service
- **AND** the STT controller is not constructed
- **AND** the STT model readiness poller is not started
- **AND** the connected monitor/test surface continues to show Loading for the STT model readiness field