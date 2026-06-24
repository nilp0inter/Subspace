## 1. STT Decoupling

- [x] 1.1 Extract `TranscriptionService` class wrapping `SttTranscriber` with a `suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String` API
- [x] 1.2 Refactor `SttTestController` to delegate transcription to `TranscriptionService` instead of calling `SttTranscriber` directly
- [x] 1.3 Verify existing STT test mode still works after refactor (existing tests pass)

## 2. OGG Encoding

- [x] 2.1 Implement `OggEncoder` class: accepts PCM (16-bit, 16kHz, mono), writes OGG/Vorbis to a `File` via bundled Rust native encoder
- [x] 2.2 Run encoding on `Dispatchers.IO`, returning a `Result<File>` to caller
- [x] 2.3 Write unit/integration test verifying OGG output is valid and non-empty for a synthetic PCM buffer
- [x] 2.4 Handle encoding failure: log error, delete partial file, return failure result

## 3. Channel Data Model and Persistence

- [x] 3.1 Define `Channel` sealed interface and `CaptainsLogChannel` data class in `model/` package
- [x] 3.2 Implement `ChannelRepository` using SharedPreferences for channel configuration persistence (serialize/deserialize `CaptainsLogChannel`)
- [x] 3.3 Enforce the illegal-state invariant: at least one of `saveVoice`/`saveText` must be true at construction time
- [x] 3.4 Write unit tests for `ChannelRepository` round-trip and illegal-state rejection

## 4. File Access and Directory Structure

- [x] 4.1 Add `MANAGE_EXTERNAL_STORAGE` permission to AndroidManifest.xml
- [x] 4.2 Implement runtime permission request flow for `MANAGE_EXTERNAL_STORAGE` (Settings intent on API 30+)
- [x] 4.3 Implement directory picker using `ACTION_OPEN_DOCUMENT_TREE` with URI-to-filesystem-path resolution
- [x] 4.4 Implement `LogDirectoryManager`: creates `YYYY/YYYY-MM/YYYY-MM-DD/recordings/` structure on demand, returns paths for audio and markdown files
- [x] 4.5 Write unit tests for `LogDirectoryManager` path generation logic (pure date-to-path mapping)

## 5. Markdown Log Writer

- [x] 5.1 Implement `MarkdownLogWriter`: creates daily log file with H1 header, appends H2 entries with timestamp, body text, and optional recording link
- [x] 5.2 Handle file creation (new day) vs append (existing day) logic
- [x] 5.3 Write unit tests for markdown output format: first entry, subsequent entry, entry with link, entry without link

## 6. Captain's Log Channel Controller

- [x] 6.1 Implement `CaptainsLogController`: receives PCM capture, orchestrates OGG encoding + STT + markdown write based on toggle configuration
- [x] 6.2 Wire `TranscriptionService` for STT when `saveText` is enabled
- [x] 6.3 Wire `OggEncoder` for audio when `saveVoice` is enabled
- [x] 6.4 Wire `MarkdownLogWriter` for log entries when `saveText` is enabled (with conditional recording link)
- [x] 6.5 Handle transcription failure: write error placeholder in markdown, preserve audio if `saveVoice` enabled
- [x] 6.6 Ensure new PTT captures can start while previous encoding is still in progress (non-blocking)

## 7. PTT Audio Router

- [x] 7.1 Implement `AudioRouter` in service layer: checks active channel vs test mode, dispatches PTT capture to the correct handler
- [x] 7.2 Enforce mutual exclusion: activating a channel cancels active test mode; starting a test mode deactivates channel routing
- [x] 7.3 Integrate `AudioRouter` into `PttForegroundService` PTT event handling, replacing direct test-controller dispatch
- [x] 7.4 Write unit tests for router dispatch logic and mutual exclusion

## 8. Dashboard UI Integration

- [x] 8.1 Replace the static `MockChannel` "Local Relay" card with a real Captain's Log card showing configuration state (directory, toggles, active/inactive)
- [x] 8.2 Add channel configuration surface: directory picker button, "Save voice" toggle, "Save in log file" toggle, activate/deactivate control
- [x] 8.3 Implement toggle interlock UI: prevent disabling the last enabled toggle
- [x] 8.4 Wire UI actions through `PttUiActions` interface to the service (configure channel, activate, deactivate)
- [x] 8.5 Show Captain's Log as inactive when a test mode is active

## 9. End-to-End Validation

- [x] 9.1 Manual test: configure Captain's Log directory, activate, PTT capture, verify OGG file written with correct name and path
- [x] 9.2 Manual test: verify markdown log created with correct H1, H2, transcription text, and relative recording link
- [x] 9.3 Manual test: second capture on same day appends to existing markdown without duplicating H1
- [x] 9.4 Manual test: disable "Save voice", verify only markdown written (no recording link)
- [x] 9.5 Manual test: verify mutual exclusion — activate Captain's Log, try starting echo test, confirm test mode is blocked and vice versa
- [x] 9.6 Manual test: verify Syncthing picks up written files from the selected directory
