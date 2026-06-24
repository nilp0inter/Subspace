## 1. Entry Schema And Paths

- [x] 1.1 Add Journal entry path generation for `YYYY/YYYY-MM/YYYY-MM-DD/entries/<stem>/` using capture start time and `journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET` stems.
- [x] 1.2 Add Journal metadata data classes for schema version, entry identity, channel snapshot, capture state, encoding state, transcription state, artifact paths, errors, and `deletedAt`.
- [x] 1.3 Add JSON metadata read/write support with temp-file replacement for atomic updates.
- [x] 1.4 Add helpers to discover entry metadata files for a day and across the configured Journal base directory.

## 2. Streaming WAV Capture

- [x] 2.1 Add a file-backed WAV recorder that streams mono PCM16 microphone audio to a target `.capture.wav` file and finalizes the WAV header on stop.
- [x] 2.2 Add a WAV PCM reader that returns sample rate, channel count, duration, byte count, and PCM samples for finalized capture files.
- [x] 2.3 Update Journal PTT start flow to snapshot the current Journal channel, validate readiness, create entry paths, initialize metadata, and start file-backed capture.
- [x] 2.4 Update Journal PTT release flow to finalize the capture file, persist finished or failed capture metadata, release SCO, and enqueue derived Journal work.
- [x] 2.5 Remove the Journal-specific 60 second timeout and avoid full-duration in-memory buffering for Journal captures.
- [x] 2.6 Keep existing in-memory recorder behavior unchanged for Debug, Echo, STT, TTS, and STT+TTS controllers.

## 3. Derived Journal Work

- [x] 3.1 Change Journal OGG encoding orchestration to accept finalized capture files and write `.recording.ogg` in the entry directory when `saveVoice` is enabled.
- [x] 3.2 Adapt the current native OGG encoder path to read WAV PCM input before invoking the existing JNI encoder.
- [x] 3.3 Change Journal transcription orchestration to transcribe finalized capture files when `saveText` is enabled.
- [x] 3.4 Persist encoding and transcription state transitions as `pending`, `running`, `finished`, `failed`, or `skipped` in metadata.
- [x] 3.5 Preserve canonical `.capture.wav` files regardless of derived OGG or transcription failures.

## 4. Markdown Rendering And Recovery

- [x] 4.1 Replace append-only Journal markdown writing with a renderer that reads day metadata, filters deleted entries, sorts by `startedAt`, and writes `journal-day-YYYY-MM-DD.md` atomically.
- [x] 4.2 Render transcript text, transcription failure placeholders, and relative OGG links according to metadata state.
- [x] 4.3 Add Journal startup reconciliation that marks stale `capture.state == recording` entries as `abandoned` or `failed` when no active recorder owns them.
- [x] 4.4 Add Journal startup reconciliation that converts stale derived `running` states to `pending` or `failed` and regenerates affected markdown files.
- [x] 4.5 Leave legacy `recordings/journal-*.ogg` and `journal-YYYY-MM-DD.md` files untouched.

## 5. UI And State Integration

- [x] 5.1 Update Journal configuration/status UI copy as needed so `Save voice` describes derived OGG output while canonical WAV capture remains retained.
- [x] 5.2 Wire Journal initialization in `PttForegroundService` to run recovery after channel configuration is loaded and when the configured base directory changes.
- [x] 5.3 Ensure active-channel PTT routing still emits the existing error beep when the Journal channel is not ready.

## 6. Tests And Verification

- [x] 6.1 Add unit tests for entry path generation, timezone offset formatting, and artifact filenames.
- [x] 6.2 Add unit tests for metadata serialization, state transitions, and atomic rewrite behavior where practical.
- [x] 6.3 Add unit tests for markdown regeneration from multiple metadata files, skipped derived tasks, failed transcription, failed encoding, and deleted entries.
- [x] 6.4 Add unit tests for startup recovery of stale recording and stale derived running states.
- [x] 6.5 Add or update Journal controller tests with fake recorder, fake encoder, and fake transcriber coverage for `saveVoice` and `saveText` combinations.
- [x] 6.6 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 6.7 Run `nix develop --no-write-lock-file -c gradle build`.
- [ ] 6.8 Manually verify on the target Android device that a Journal hold longer than 60 seconds records until release and produces metadata, capture WAV, derived OGG when enabled, transcript metadata, and regenerated markdown.
