## Why

Journal capture is currently bounded by in-memory PCM buffering and a fixed 60 second cap, so longer captures risk either forced truncation or excessive heap use. The Journal channel needs durable capture-first behavior where audio is streamed to disk before encoding, transcription, and markdown generation run as derived work.

## What Changes

- Replace in-memory Journal capture with streaming PCM/WAV capture written directly under the selected Journal output tree.
- **BREAKING**: Store each Journal entry in its own directory with a compact timestamp stem that includes date, time, milliseconds, and timezone offset, replacing the previous flat `recordings/journal-*.ogg` output layout for new captures.
- Add a JSON sidecar metadata file as the canonical entry state for capture, encoding, transcription, deletion, and markdown regeneration.
- **BREAKING**: Treat WAV capture as the canonical retained recording for accepted captures; `saveVoice` controls only whether a derived OGG recording is produced.
- Treat OGG audio, transcript text, and daily markdown as derived artifacts produced from the durable capture and metadata.
- Regenerate the daily markdown journal from entry metadata instead of appending/editing markdown in place.
- Add startup/task recovery behavior for stale in-progress entries and derived work.
- Remove the Journal-specific 60 second capture limit introduced by the in-memory implementation.

## Capabilities

### New Capabilities

*(None)*

### Modified Capabilities

- `captains-log-channel`: Journal output layout, capture lifecycle, metadata source of truth, markdown generation, and long-capture behavior change.
- `ogg-encoding`: OGG encoding source changes from an in-memory PCM buffer to a finalized PCM/WAV capture file while retaining asynchronous encoding semantics.

## Impact

- **Storage format**: Journal output gains per-entry directories, `.metadata.json` sidecars, durable `.capture.wav` files, derived `.recording.ogg` files, and generated daily markdown.
- **Capture pipeline**: Journal PTT handling changes from memory-buffered recording to streaming file recording.
- **Task model**: Encoding, transcription, and markdown rendering become resumable/retryable derived tasks driven by metadata state.
- **Compatibility**: Existing Journal output files are not migrated by this change unless explicitly added during implementation.
- **Affected code**: `channel/`, `audio/`, `service/`, Journal configuration/UI state, OGG encoding integration, STT invocation, and file output logic.
