## ADDED Requirements

### Requirement: STT↔TTS test mode becomes available after both engines initialize, regardless of completion order
The system SHALL construct the STT↔TTS round-trip controller once both the Parakeet (STT) and Supertonic (TTS) engines have finished initializing, regardless of which engine's initialization completes first, so that STT↔TTS test mode is usable on first run without restarting the app. If either engine's initialization fails, the system SHALL NOT construct the STT↔TTS controller.

#### Scenario: STT initializes first, then TTS
- **WHEN** the app starts, the Parakeet engine finishes initializing, and then the Supertonic engine finishes initializing
- **THEN** the system constructs the STT↔TTS controller
- **AND** STT↔TTS test mode is usable without restarting the app

#### Scenario: TTS initializes first, then STT
- **WHEN** the app starts, the Supertonic engine finishes initializing, and then the Parakeet engine finishes initializing
- **THEN** the system constructs the STT↔TTS controller once the Parakeet engine finishes
- **AND** STT↔TTS test mode is usable without restarting the app

#### Scenario: STT initialization fails
- **WHEN** the Parakeet engine initialization fails, regardless of whether Supertonic initialization succeeds or fails
- **THEN** the system does not construct the STT↔TTS controller
- **AND** the system does not crash
- **AND** the other test modes that do not require the Parakeet engine remain available

#### Scenario: TTS initialization fails
- **WHEN** the Supertonic engine initialization fails, regardless of whether Parakeet initialization succeeds or fails
- **THEN** the system does not construct the STT↔TTS controller
- **AND** the system does not crash
- **AND** the other test modes that do not require the Supertonic engine remain available

#### Scenario: STT↔TTS used before both engines are ready
- **WHEN** STT↔TTS test mode is active and the user triggers PTT before both engines have finished initializing
- **THEN** the system does not crash
- **AND** the system does not block the UI thread waiting for engine readiness
