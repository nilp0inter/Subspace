## Purpose

TBD. Defines the Captain's Log channel behavior for PTT capture persistence, transcription, and output files.

## Requirements

### Requirement: Captain's Log channel type
The system SHALL provide a built-in channel type named "Captain's Log" that records PTT captures as OGG audio files and/or appends transcriptions to a daily markdown log file in a user-selected directory.

#### Scenario: Channel exists in channel list
- **WHEN** the app is launched
- **THEN** the Captain's Log channel SHALL appear as an available channel

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

### Requirement: At least one toggle must be enabled
The system SHALL enforce that at least one of "Save voice" or "Save in log file" is enabled at all times. Both toggles disabled is an illegal state.

#### Scenario: User disables the last active toggle
- **WHEN** one toggle is enabled and the user attempts to disable it
- **THEN** the system SHALL prevent the action and keep the toggle enabled

### Requirement: Date-structured directory output
The system SHALL create a hierarchical date-based directory structure under the user-selected base directory for all output files.

#### Scenario: First capture on a new date
- **WHEN** a PTT capture occurs on a date with no existing output directory
- **THEN** the system SHALL create the directory structure `YYYY/YYYY-MM/YYYY-MM-DD/` and a `recordings/` subdirectory within it

#### Scenario: Subsequent capture on same date
- **WHEN** a PTT capture occurs on a date with an existing output directory
- **THEN** the system SHALL use the existing directory structure without creating duplicates

### Requirement: Audio file naming
The system SHALL name audio recording files using the pattern `log-YYYY-MM-DD_HH-MM-SS.ogg` where the timestamp reflects the moment PTT was released (capture end).

#### Scenario: Audio file written
- **WHEN** a PTT capture completes at 14:30:00 on 2026-06-24 with "Save voice" enabled
- **THEN** the system SHALL write the file to `<base>/2026/2026-06/2026-06-24/recordings/log-2026-06-24_14-30-00.ogg`

### Requirement: Markdown log format
The system SHALL maintain a daily markdown log file named `log-YYYY-MM-DD.md` in the day directory. The file SHALL contain:
- An H1 header `# Log YYYY-MM-DD` (written once when file is created)
- An H2 entry `## Entry HH-MM-SS` for each PTT capture
- The transcribed text as a body paragraph under the H2
- A relative markdown link `[Source recording](recordings/log-YYYY-MM-DD_HH-MM-SS.ogg)` if "Save voice" is also enabled

#### Scenario: First entry of the day with both toggles enabled
- **WHEN** the first PTT capture of the day completes with both "Save voice" and "Save in log file" enabled
- **THEN** the system SHALL create `log-YYYY-MM-DD.md`, write the H1 header, then append an H2 entry with transcribed text and a relative link to the recording

#### Scenario: Entry with only text enabled
- **WHEN** a PTT capture completes with "Save in log file" enabled and "Save voice" disabled
- **THEN** the system SHALL append an H2 entry with transcribed text and no source recording link

#### Scenario: Subsequent entry appended
- **WHEN** a PTT capture completes and the daily log file already exists
- **THEN** the system SHALL append the new H2 entry at the end of the file without modifying existing content

### Requirement: STT transcription via on-device model
The system SHALL use the existing on-device Parakeet STT model for transcription. Transcription SHALL NOT require network access.

#### Scenario: Transcription succeeds
- **WHEN** a PTT capture is transcribed
- **THEN** the transcription SHALL use the Parakeet model already loaded in the service and SHALL NOT perform any network I/O

#### Scenario: Transcription fails
- **WHEN** the STT model fails to produce a transcript
- **THEN** the system SHALL write the entry with an error placeholder text and SHALL NOT discard the audio recording if "Save voice" is enabled
