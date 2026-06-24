## Purpose

Defines the Bluetooth SCO (Synchronous Connection Oriented) audio route lifecycle — acquisition from inactive state, cold-start priming for reliable first-playback audio, device-level AudioTrack routing, and the warmup retention window that keeps the SCO path active between PTT sessions.

## ADDED Requirements

### Requirement: SCO route is acquired on PTT press

The system SHALL acquire the Bluetooth SCO communication route when the user presses PTT while any audio-capturing mode is enabled.

#### Scenario: SCO is inactive on PTT press
- **WHEN** SCO is inactive, an audio-capturing mode is enabled, and the user presses PTT
- **THEN** the system sets the audio manager mode to `MODE_IN_COMMUNICATION`
- **AND** the system sets the SCO device as the communication device
- **AND** the system polls for the communication device type to become `TYPE_BLUETOOTH_SCO`
- **AND** the system reports SCO state transitions: `Inactive → Starting → Active`

#### Scenario: SCO is already active on PTT press
- **WHEN** SCO is already active (warm from a previous session) and the user presses PTT
- **THEN** the system returns immediately without re-acquisition
- **AND** the system reports SCO state `Active`

#### Scenario: SCO acquisition times out
- **WHEN** the system polls for 5 seconds and the SCO route does not become active
- **THEN** the system reports SCO state `Failed("Timed out waiting for SCO route")`
- **AND** the system returns acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: SCO device is not found
- **WHEN** no Bluetooth SCO device is available during acquisition
- **THEN** the system reports SCO state `Failed("Bluetooth SCO headset not available")`
- **AND** the system returns acquisition failure

### Requirement: Ready beep plays at SCO activation instant with reliable audible output

The system SHALL play the ready beep at the exact instant the SCO route becomes active, and SHALL ensure the beep is audibly routed through the SCO headset.

#### Scenario: Cold-start SCO — first beep after inactive SCO
- **WHEN** SCO was inactive, the system acquires SCO successfully, and the SCO route just became active
- **THEN** the system SHALL route the AudioTrack explicitly to the SCO communication device via `setPreferredDevice`
- **AND** the system SHALL send a priming PCM buffer (short silence, 100ms) through the SCO route before the beep waveform to establish the link-layer SCO packet stream
- **AND** the system SHALL play the ready beep (880 Hz sine, 150ms) immediately after priming
- **AND** the user SHALL hear the beep through the Bluetooth headset

#### Scenario: Warm-start SCO — subsequent beeps within warmup window
- **WHEN** SCO is already active from a previous session (warm) and PTT is pressed
- **THEN** the system SHALL play the ready beep through the SCO device route without priming
- **AND** the user SHALL hear the beep through the Bluetooth headset

### Requirement: Recording always starts after ready beep completes

The system SHALL start audio recording only after the ready beep playback has fully completed.

#### Scenario: Beep completes before recording starts
- **WHEN** the ready beep finishes playing and PTT is still held
- **THEN** the system SHALL start recording mono PCM audio from the Bluetooth SCO microphone source
- **AND** the system SHALL report status transition: `Beeping → Recording`

#### Scenario: PTT is released during beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system SHALL NOT start recording
- **AND** the system SHALL cancel the session
- **AND** the system SHALL retain the warm SCO route (see SCO warmup requirement)

#### Scenario: PTT is released during SCO acquisition (short tap)
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system SHALL continue SCO acquisition to completion
- **AND** the system SHALL NOT play the ready beep
- **AND** the system SHALL NOT start recording
- **AND** the system SHALL retain the SCO route warm for 30 seconds

### Requirement: SCO warmup retention

The system SHALL keep the SCO route active for 30 seconds after the last PTT session ends, so that subsequent PTT presses get instant SCO acquisition and reliable beep playback.

#### Scenario: Session ends — SCO retained warm
- **WHEN** a PTT recording session finishes (PTT released) or is cancelled via short tap
- **THEN** the system SHALL keep the SCO communication device set for 30 seconds
- **AND** the system SHALL report SCO state as `Active` during the warmup window

#### Scenario: Warmup expires
- **WHEN** 30 seconds pass with no new PTT press
- **THEN** the system SHALL clear the communication device
- **AND** the system SHALL set audio manager mode back to `MODE_NORMAL`
- **AND** the system SHALL report SCO state transition: `Active → Closing → Inactive`

#### Scenario: PTT press during warmup
- **WHEN** a warmup delay is active and the user presses PTT
- **THEN** the system SHALL cancel the warmup delay
- **AND** the system SHALL proceed with beep and recording immediately without re-acquisition

### Requirement: AudioTrack routes through SCO device

The system SHALL explicitly route all `PcmOutput` AudioTrack instances through the active SCO communication device to guarantee the audio reaches the Bluetooth headset.

#### Scenario: AudioTrack is created with SCO device preference
- **WHEN** the system creates an AudioTrack for beep playback or audio playback
- **THEN** the system SHALL set the AudioTrack's preferred device to the currently active SCO communication device
- **AND** the system SHALL fall back to the default routing if no SCO device is active (Audio track's default behavior)
