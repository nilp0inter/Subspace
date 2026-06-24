## MODIFIED Requirements

### Requirement: Captain's Log channel type
The system SHALL provide a built-in channel type named "Journal" that records PTT captures as OGG audio files and/or appends transcriptions to a daily markdown journal file in a user-selected directory.

#### Scenario: Channel exists in channel list
- **WHEN** the app is launched
- **THEN** the Journal channel SHALL appear as an available channel

### Requirement: Directory selection before readiness
The system SHALL require the user to select a base output directory before the Journal channel is considered ready. The channel MAY be set as the active channel without a directory, but it SHALL NOT accept PTT captures and SHALL emit an error beep if PTT is pressed while not ready.

#### Scenario: No directory selected
- **WHEN** the user attempts to use PTT on an active Journal channel without a configured directory
- **THEN** the system SHALL emit a two-tone error beep over the headset and SHALL NOT process the capture

#### Scenario: Directory selected
- **WHEN** the user selects a valid directory via the dedicated configuration screen
- **THEN** the channel's readiness state SHALL become true, allowing PTT captures

### Requirement: Save voice toggle
The system SHALL provide a "Save voice" toggle on the dedicated configuration screen that controls whether PTT captures are encoded and saved as OGG audio files.

#### Scenario: Save voice enabled
- **WHEN** the Journal channel receives a PTT capture and "Save voice" is enabled
- **THEN** the system SHALL encode the capture to OGG/Vorbis and write it to the recordings directory

#### Scenario: Save voice disabled
- **WHEN** the Journal channel receives a PTT capture and "Save voice" is disabled
- **THEN** the system SHALL NOT write an audio file for that capture

### Requirement: Save in log file toggle
The system SHALL provide a "Save in journal file" toggle on the dedicated configuration screen that controls whether PTT captures are transcribed and appended to the daily markdown journal.

#### Scenario: Save text enabled
- **WHEN** the Journal channel receives a PTT capture and "Save in journal file" is enabled
- **THEN** the system SHALL transcribe the capture via on-device STT and append an entry to the daily markdown journal

#### Scenario: Save text disabled
- **WHEN** the Journal channel receives a PTT capture and "Save in journal file" is disabled
- **THEN** the system SHALL NOT transcribe the capture or write to the markdown journal

### Requirement: Audio file naming
The system SHALL name audio recording files using the pattern `journal-YYYY-MM-DD_HH-MM-SS.ogg` where the timestamp reflects the moment PTT was released (capture end).

#### Scenario: Audio file written
- **WHEN** a PTT capture completes at 14:30:00 on 2026-06-24 with "Save voice" enabled
- **THEN** the system SHALL write the file to `<base>/2026/2026-06/2026-06-24/recordings/journal-2026-06-24_14-30-00.ogg`

### Requirement: Markdown log format
The system SHALL maintain a daily markdown journal file named `journal-YYYY-MM-DD.md` in the day directory. The file SHALL contain:
- An H1 header `# Journal YYYY-MM-DD` (written once when file is created)
- An H2 entry `## Entry HH-MM-SS` for each PTT capture
- The transcribed text as a body paragraph under the H2
- A relative markdown link `[Source recording](recordings/journal-YYYY-MM-DD_HH-MM-SS.ogg)` if "Save voice" is also enabled

#### Scenario: First entry of the day with both toggles enabled
- **WHEN** the first PTT capture of the day completes with both "Save voice" and "Save in journal file" enabled
- **THEN** the system SHALL create `journal-YYYY-MM-DD.md`, write the H1 header, then append an H2 entry with transcribed text and a relative link to the recording

#### Scenario: Entry with only text enabled
- **WHEN** a PTT capture completes with "Save in journal file" enabled and "Save voice" disabled
- **THEN** the system SHALL append an H2 entry with transcribed text and no source recording link

#### Scenario: Subsequent entry appended
- **WHEN** a PTT capture completes and the daily journal file already exists
- **THEN** the system SHALL append the new H2 entry at the end of the file without modifying existing content
