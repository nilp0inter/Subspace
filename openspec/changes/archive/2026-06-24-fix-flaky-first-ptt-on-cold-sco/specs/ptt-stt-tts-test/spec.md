## REMOVED Requirements

### Requirement: Echo test supports two timing modes

**Reason**: The `RecordWhileBeepPlays` timing mode is removed. Recording must always start after the ready beep completes to ensure the beep is never truncated and is captured as the first audio event in every recording.

**Migration**: Remove the `EchoTimingMode` enum values, the timing-mode toggle UI, the `setTimingMode()` wiring, and the `startDuringBeepMode()` code path. The `RecordAfterBeep` behavior becomes the only path. Keep `EchoTimingMode` removal scoped to the change. Ensure existing `EchoTimingMode.RecordAfterBeep` callers work without modification — all current production usage already defaults to this mode.

## MODIFIED Requirements

### Requirement: Echo test records audio from PTT activity

The system SHALL record audio for echo using the same PTT-controlled Bluetooth SCO recording path as the other test modes.

#### Scenario: PTT is pressed while echo is enabled
- **WHEN** echo test mode is enabled and the user presses PTT
- **THEN** the system acquires the Bluetooth SCO route
- **AND** the system plays the ready beep through the Bluetooth SCO route
- **AND** the system waits for the ready beep to finish playing
- **AND** the system starts recording mono PCM audio for echo playback
- **AND** the recording SHALL include the tail of the ready beep at its start (beep is audible in the recorded playback)

#### Scenario: PTT is released before recording starts
- **WHEN** echo test mode is enabled and the user releases PTT before recording starts (during SCO acquisition or beep playback)
- **THEN** the system cancels the pending echo session
- **AND** the system retains the SCO route warm for the warmup window
- **AND** the system does not play back any audio
- **AND** the system does not submit audio for echo playback

#### Scenario: PTT is released after audio is recorded
- **WHEN** echo test mode is enabled, recording is active, and the user releases PTT
- **THEN** the system stops recording
- **AND** the system stops the SCO warmup timer
- **AND** the system plays back the captured audio through the Bluetooth SCO route
- **AND** the system restarts the SCO warmup timer after playback completes

## ADDED Requirements

### Requirement: SCO is pre-warmed on short PTT taps

The system SHALL retain the warm SCO route when PTT is released before a recording session starts, so that the next PTT press gets instant audio path availability.

#### Scenario: Quick PTT tap in echo mode
- **WHEN** echo test mode is enabled, the user quickly presses and releases PTT before SCO acquisition completes, and SCO acquisition then completes
- **THEN** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system retains the SCO route warm for the 30-second warmup window
- **AND** the user perceives no echo session

#### Scenario: Quick PTT tap in STT mode
- **WHEN** STT test mode is enabled, the user quickly presses and releases PTT, and SCO acquisition then completes
- **THEN** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system does not submit audio to Parakeet
- **AND** the system retains the SCO route warm for the 30-second warmup window

#### Scenario: Quick PTT tap in STT↔TTS mode
- **WHEN** STT↔TTS test mode is enabled, the user quickly presses and releases PTT, and SCO acquisition then completes
- **THEN** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system does not transcribe or synthesize
- **AND** the system retains the SCO route warm for the 30-second warmup window

### Requirement: Echo status shows Warm state after short-tap cancellation

#### Scenario: Echo shows Warm on short tap
- **WHEN** echo test mode is enabled, the user taps PTT briefly, SCO acquisition completes, and the session is cancelled pre-recording
- **THEN** the echo status transitions to `EchoStatus.Warm` for the duration of the SCO warmup window
- **AND** the UI shows the warm state in the echo status area
