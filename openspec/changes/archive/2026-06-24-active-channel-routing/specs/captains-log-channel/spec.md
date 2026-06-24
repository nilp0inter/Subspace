## MODIFIED Requirements

### Requirement: Directory selection before readiness
The system SHALL require the user to select a base output directory before the Captain's Log channel is considered ready. The channel MAY be set as the active channel without a directory, but it SHALL NOT accept PTT captures and SHALL emit an error beep if PTT is pressed while not ready.

#### Scenario: No directory selected
- **WHEN** the user attempts to use PTT on an active Captain's Log channel without a configured directory
- **THEN** the system SHALL emit a two-tone error beep over the headset and SHALL NOT process the capture

#### Scenario: Directory selected
- **WHEN** the user selects a valid directory via the dedicated configuration screen
- **THEN** the channel's readiness state SHALL become true, allowing PTT captures

### Requirement: Save voice toggle
The system SHALL provide a "Save voice" toggle on the dedicated configuration screen that controls whether PTT captures are encoded and saved as OGG audio files.

#### Scenario: Save voice enabled
- **WHEN** the Captain's Log channel receives a PTT capture and "Save voice" is enabled
- **THEN** the system SHALL encode the capture to OGG/Vorbis and write it to the recordings directory

#### Scenario: Save voice disabled
- **WHEN** the Captain's Log channel receives a PTT capture and "Save voice" is disabled
- **THEN** the system SHALL NOT write an audio file for that capture

### Requirement: Save in log file toggle
The system SHALL provide a "Save in log file" toggle on the dedicated configuration screen that controls whether PTT captures are transcribed and appended to the daily markdown log.

#### Scenario: Save text enabled
- **WHEN** the Captain's Log channel receives a PTT capture and "Save in log file" is enabled
- **THEN** the system SHALL transcribe the capture via on-device STT and append an entry to the daily markdown log

#### Scenario: Save text disabled
- **WHEN** the Captain's Log channel receives a PTT capture and "Save in log file" is disabled
- **THEN** the system SHALL NOT transcribe the capture or write to the markdown log
