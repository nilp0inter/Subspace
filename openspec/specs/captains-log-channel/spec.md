## Purpose

TBD. Defines the Captain's Log channel behavior for PTT capture persistence, transcription, and output files.

## Requirements

### Requirement: Captain's Log channel type
The system SHALL provide a built-in channel type named "Journal" that streams accepted PTT captures to durable WAV/PCM capture files, records canonical entry state in JSON metadata, and derives OGG audio files and/or daily markdown journal output in a user-selected directory.

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
The system SHALL provide a "Save voice" toggle on the dedicated configuration screen that controls whether finalized Journal capture files are encoded and saved as derived OGG audio files. The toggle SHALL NOT control whether the canonical WAV/PCM capture file is retained for an accepted Journal entry.

#### Scenario: Save voice enabled
- **WHEN** the Journal channel receives a PTT capture and "Save voice" is enabled
- **THEN** the system SHALL encode the finalized capture file to OGG/Vorbis and write the derived OGG file in the entry directory

#### Scenario: Save voice disabled
- **WHEN** the Journal channel receives a PTT capture and "Save voice" is disabled
- **THEN** the system SHALL NOT write a derived OGG file for that capture
- **AND** the system SHALL retain the canonical WAV/PCM capture file for that entry

### Requirement: Save in journal file toggle
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
The system SHALL use the existing on-device Parakeet STT model for transcription. Transcription SHALL use finalized Journal capture files as input and SHALL NOT require network access. The Journal channel SHALL bind to the real on-device transcriber once the STT off-main initialization completes, regardless of whether STT initialization finishes before or after `onCreate`, so journal captures are transcribed instead of falling back to a no-op transcriber. If STT initialization fails, the Journal channel SHALL fall back to a no-op transcriber that marks transcription as failed in entry metadata while preserving the canonical capture file.

#### Scenario: Transcription succeeds
- **WHEN** a finalized Journal capture is transcribed and the STT off-main initialization has completed successfully
- **THEN** the transcription SHALL use the Parakeet model already loaded in the service
- **AND** the system SHALL NOT perform any network I/O
- **AND** the system SHALL store the transcript text in entry metadata

#### Scenario: Transcription fails
- **WHEN** the STT model fails to produce a transcript or the STT off-main initialization has failed
- **THEN** the system SHALL mark transcription as failed in entry metadata
- **AND** the system SHALL NOT discard the canonical capture file
- **AND** the generated markdown entry SHALL include an error placeholder if "Save in journal file" is enabled

#### Scenario: Journal transcriber bound after STT init completes regardless of startup ordering
- **WHEN** the app process starts and the Journal channel is initialized at `onCreate` while the STT off-main initialization coroutine is still running
- **THEN** the system SHALL await the completion of the STT off-main initialization before constructing the Journal channel's transcriber binding
- **AND** if STT initialization succeeds, the Journal channel SHALL use the real on-device Parakeet transcriber for all subsequent captures
- **AND** the Journal channel SHALL NOT permanently bind to a no-op transcriber due to the startup ordering

#### Scenario: Journal falls back to no-op transcriber on STT init failure
- **WHEN** the STT off-main initialization fails and `sttReady` resolves to `null`
- **THEN** the Journal channel SHALL construct its controller with a no-op transcriber that fails each transcription request
- **AND** the Journal channel SHALL still accept captures and persist WAV files and metadata
- **AND** each capture's transcription state SHALL be marked as failed in entry metadata
- **AND** the generated markdown SHALL include an error placeholder if "Save in journal file" is enabled

#### Scenario: Journal recovery runs after transcriber binding
- **WHEN** the Journal channel is initialized with a previously selected base directory and the STT off-main initialization has completed
- **THEN** the system SHALL run Journal recovery reconciliation after the transcriber binding is established
- **AND** recovery SHALL reconcile stale entries against the bound transcriber, not a no-op fallback

### Requirement: Journal entry metadata source of truth
The system SHALL create a JSON metadata sidecar for every accepted Journal capture and SHALL treat that metadata as the canonical source of truth for entry identity, capture state, derived task state, transcript text, artifact paths, and deletion state. The metadata SHALL record the sample rate negotiated by the capture source for the session, not a hardcoded value.

#### Scenario: Metadata initialized before recording
- **WHEN** the Journal channel accepts a PTT press for recording
- **THEN** the system SHALL create entry metadata with `capture.state` set to `recording` before audio capture bytes are streamed

#### Scenario: Metadata updated after capture finalization
- **WHEN** the user releases PTT and the capture file is finalized successfully
- **THEN** the system SHALL update metadata with `capture.state` set to `finished`
- **AND** the system SHALL record capture duration, sample rate (read from the capture session), channel count, encoding, and relative capture path

#### Scenario: 8 kHz capture records 8000 sample rate
- **WHEN** the capture source negotiates an 8 kHz sample rate and the Journal capture finalizes
- **THEN** the metadata `capture.sampleRate` SHALL be 8000
- **AND** the WAV header in the capture file SHALL be written with sample rate 8000

#### Scenario: 16 kHz capture records 16000 sample rate
- **WHEN** the capture source negotiates a 16 kHz sample rate and the Journal capture finalizes
- **THEN** the metadata `capture.sampleRate` SHALL be 16000
- **AND** the WAV header in the capture file SHALL be written with sample rate 16000

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

### Requirement: Journal WAV writer thread safety

The journal-side WAV writer SHALL be internally thread-safe so a
`writeChunk` call that is in flight when `finalize` is called from another
coroutine does not throw `IOException` or corrupt the file. `finalize` SHALL
be idempotent. A `writeChunk` call that arrives after `finalize` SHALL be a
no-op.

#### Scenario: Finalize during an in-flight write
- **WHEN** the frames collector is inside `writeChunk` and `finalize` is called from another coroutine
- **THEN** the writer SHALL serialize the two operations
- **AND** neither call SHALL throw `IOException`
- **AND** the resulting WAV file SHALL be well-formed

#### Scenario: Late writeChunk after finalize is a no-op
- **WHEN** `finalize` has completed and a `writeChunk` call arrives from a collector that has not yet observed cancellation
- **THEN** the writer SHALL return without writing
- **AND** the writer SHALL NOT throw

### Requirement: Journal frames collector joined before finalization

The Journal pipeline SHALL join the frames collector coroutine before
finalizing the WAV writer, so the collector has fully unwound before the file
is closed. This establishes a happens-before edge between the split
lifecycles introduced by the capture-service refactor (the service's read
loop and the journal's frames collector).

#### Scenario: Frames collector unwound before finalize
- **WHEN** the user releases PTT and the Journal pipeline finalizes the capture
- **THEN** the system SHALL `cancelAndJoin` the frames collector before calling `writer.finalize()`
- **AND** no collector coroutine SHALL be running when `finalize` executes

#### Scenario: Cancel-and-release joins the collector before finalizing
- **WHEN** the Journal pipeline is cancelled mid-capture (mode switch, service teardown)
- **THEN** the system SHALL join the frames collector before finalizing the writer
- **AND** the writer's `finalize` SHALL not race an in-flight `writeChunk`

### Requirement: Normal car-call release finalizes accepted Journal captures
An accepted Journal capture that terminates because its active On-the-road Telecom call is hung up SHALL follow Journal's normal terminal-recording path. It SHALL not remain in `recording` state after the audio input subsystem has a terminal recording.

#### Scenario: Journal car capture ends on hang-up
- **WHEN** an accepted Journal target receives terminal PCM after an active car call is hung up
- **THEN** it SHALL finalize the WAV writer after joining the frames collector
- **AND** write `endedAt`, final capture state, duration, sample rate, channel count, encoding, and capture path to metadata
- **AND** await configured OGG encoding and transcription processing to complete
- **AND** regenerate the affected daily Markdown journal from updated metadata before terminal channel handling returns

#### Scenario: Car capture is cancelled before terminal recording
- **WHEN** a car session is cancelled before terminal PCM is available
- **THEN** Journal SHALL finalize any partial writer without marking the entry finished
- **AND** startup recovery remains responsible for reconciling the interrupted metadata
