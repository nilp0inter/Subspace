## MODIFIED Requirements

### Requirement: Captain's Log channel type
The system SHALL provide a built-in channel type named "Journal" that streams accepted PTT captures to durable WAV/PCM capture files, records canonical entry state in JSON metadata, and derives OGG audio files and/or daily markdown journal output in a user-selected directory.

#### Scenario: Channel exists in channel list
- **WHEN** the app is launched
- **THEN** the Journal channel SHALL appear as an available channel

### Requirement: Save voice toggle
The system SHALL provide a "Save voice" toggle on the dedicated configuration screen that controls whether finalized Journal capture files are encoded and saved as derived OGG audio files. The toggle SHALL NOT control whether the canonical WAV/PCM capture file is retained for an accepted Journal entry.

#### Scenario: Save voice enabled
- **WHEN** the Journal channel receives a PTT capture and "Save voice" is enabled
- **THEN** the system SHALL encode the finalized capture file to OGG/Vorbis and write the derived OGG file in the entry directory

#### Scenario: Save voice disabled
- **WHEN** the Journal channel receives a PTT capture and "Save voice" is disabled
- **THEN** the system SHALL NOT write a derived OGG file for that capture
- **AND** the system SHALL retain the canonical WAV/PCM capture file for that entry

### Requirement: Save in log file toggle
The system SHALL provide a "Save in journal file" toggle on the dedicated configuration screen that controls whether PTT captures are transcribed and included in the generated daily markdown journal.

#### Scenario: Save text enabled
- **WHEN** the Journal channel receives a PTT capture and "Save in journal file" is enabled
- **THEN** the system SHALL transcribe the finalized capture via on-device STT
- **AND** the system SHALL store the transcript result in entry metadata
- **AND** the system SHALL regenerate the affected daily markdown journal from entry metadata

#### Scenario: Save text disabled
- **WHEN** the Journal channel receives a PTT capture and "Save in journal file" is disabled
- **THEN** the system SHALL NOT transcribe the capture
- **AND** the system SHALL NOT include that entry in the generated daily markdown journal

### Requirement: Date-structured directory output
The system SHALL create a hierarchical date-based directory structure under the user-selected base directory for all Journal output files. Each accepted capture SHALL be stored in its own entry directory under `YYYY/YYYY-MM/YYYY-MM-DD/entries/`.

#### Scenario: First capture on a new date
- **WHEN** a PTT capture occurs on a date with no existing output directory
- **THEN** the system SHALL create the directory structure `YYYY/YYYY-MM/YYYY-MM-DD/entries/`

#### Scenario: Subsequent capture on same date
- **WHEN** a PTT capture occurs on a date with an existing output directory
- **THEN** the system SHALL use the existing directory structure without creating duplicate day directories

#### Scenario: Entry directory created
- **WHEN** a PTT capture starts at `2026-06-25T14:30:00.123-03:00`
- **THEN** the system SHALL create `<base>/2026/2026-06/2026-06-25/entries/journal-entry-2026-06-25_14-30-00-123-0300/`

### Requirement: Audio file naming
The system SHALL name Journal entry artifacts using the stem `journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET`, where the timestamp reflects the capture start time and `OFFSET` is the numeric local timezone offset without a colon.

#### Scenario: Capture file written
- **WHEN** a PTT capture starts at `2026-06-25T14:30:00.123-03:00`
- **THEN** the system SHALL write the canonical capture file to `<base>/2026/2026-06/2026-06-25/entries/journal-entry-2026-06-25_14-30-00-123-0300/journal-entry-2026-06-25_14-30-00-123-0300.capture.wav`

#### Scenario: Derived OGG file written
- **WHEN** a PTT capture starts at `2026-06-25T14:30:00.123-03:00` with "Save voice" enabled
- **THEN** the system SHALL write the derived OGG file to `<base>/2026/2026-06/2026-06-25/entries/journal-entry-2026-06-25_14-30-00-123-0300/journal-entry-2026-06-25_14-30-00-123-0300.recording.ogg`

#### Scenario: Metadata file written
- **WHEN** a PTT capture starts at `2026-06-25T14:30:00.123-03:00`
- **THEN** the system SHALL write the metadata file to `<base>/2026/2026-06/2026-06-25/entries/journal-entry-2026-06-25_14-30-00-123-0300/journal-entry-2026-06-25_14-30-00-123-0300.metadata.json`

### Requirement: Markdown log format
The system SHALL maintain a generated daily markdown journal file named `journal-day-YYYY-MM-DD.md` in the day directory. The file SHALL be rendered from non-deleted entry metadata for that day, sorted by `startedAt`, and written atomically by replacing the previous generated file.

#### Scenario: Daily markdown generated from metadata
- **WHEN** a Journal entry for `2026-06-25` has finished transcription text in metadata
- **THEN** the system SHALL render `<base>/2026/2026-06/2026-06-25/journal-day-2026-06-25.md`
- **AND** the file SHALL include an H1 header `# Journal 2026-06-25`
- **AND** the file SHALL include an H2 entry containing the entry start timestamp
- **AND** the file SHALL include the transcribed text as the entry body

#### Scenario: Entry with derived OGG recording
- **WHEN** a Journal entry has finished transcription text and a finished derived OGG recording
- **THEN** the generated daily markdown SHALL include a relative markdown link to the derived OGG recording

#### Scenario: Entry with text only
- **WHEN** a Journal entry has finished transcription text and no derived OGG recording
- **THEN** the generated daily markdown SHALL include the transcribed text without a source recording link

#### Scenario: Markdown regenerated after metadata change
- **WHEN** an entry metadata file for a day changes
- **THEN** the system SHALL regenerate that day's markdown from metadata instead of appending to the existing markdown file in place

### Requirement: STT transcription via on-device model
The system SHALL use the existing on-device Parakeet STT model for transcription. Transcription SHALL use finalized Journal capture files as input and SHALL NOT require network access.

#### Scenario: Transcription succeeds
- **WHEN** a finalized Journal capture is transcribed
- **THEN** the transcription SHALL use the Parakeet model already loaded in the service
- **AND** the system SHALL NOT perform any network I/O
- **AND** the system SHALL store the transcript text in entry metadata

#### Scenario: Transcription fails
- **WHEN** the STT model fails to produce a transcript
- **THEN** the system SHALL mark transcription as failed in entry metadata
- **AND** the system SHALL NOT discard the canonical capture file
- **AND** the generated markdown entry SHALL include an error placeholder if "Save in journal file" is enabled

## ADDED Requirements

### Requirement: Journal entry metadata source of truth
The system SHALL create a JSON metadata sidecar for every accepted Journal capture and SHALL treat that metadata as the canonical source of truth for entry identity, capture state, derived task state, transcript text, artifact paths, and deletion state.

#### Scenario: Metadata initialized before recording
- **WHEN** the Journal channel accepts a PTT press for recording
- **THEN** the system SHALL create entry metadata with `capture.state` set to `recording` before audio capture bytes are streamed

#### Scenario: Metadata updated after capture finalization
- **WHEN** the user releases PTT and the capture file is finalized successfully
- **THEN** the system SHALL update metadata with `capture.state` set to `finished`
- **AND** the system SHALL record capture duration, sample rate, channel count, encoding, and relative capture path

#### Scenario: Derived task state recorded
- **WHEN** OGG encoding or transcription is pending, running, finished, failed, or skipped
- **THEN** the system SHALL persist that task state in the entry metadata

### Requirement: Streaming capture duration
The system SHALL stream Journal capture audio to disk during recording and SHALL NOT stop an accepted Journal capture solely because it reaches a fixed 60 second duration.

#### Scenario: Capture exceeds 60 seconds
- **WHEN** the user holds PTT for longer than 60 seconds on a ready Journal channel
- **THEN** the system SHALL continue recording until PTT is released or an actual capture error occurs
- **AND** the system SHALL NOT retain the capture in an in-memory PCM buffer for the full recording duration

### Requirement: Journal recovery reconciliation
The system SHALL reconcile Journal metadata and files on Journal initialization so stale in-progress entries and derived tasks do not remain permanently active after process death.

#### Scenario: Stale recording entry found at startup
- **WHEN** Journal initialization finds metadata with `capture.state` set to `recording` and no active recorder owns that entry
- **THEN** the system SHALL mark the capture as `abandoned` or `failed` in metadata
- **AND** the system SHALL preserve any partial capture file for inspection or future recovery

#### Scenario: Stale derived task found at startup
- **WHEN** Journal initialization finds metadata with encoding or transcription state set to `running`
- **THEN** the system SHALL convert that task state to `pending` or `failed`

#### Scenario: Startup reconciliation affects markdown
- **WHEN** Journal recovery changes entry metadata for a day
- **THEN** the system SHALL regenerate that day's markdown from metadata
