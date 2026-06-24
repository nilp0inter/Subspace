## Context

Journal PTT currently captures into `InMemoryRecorder`, stops automatically after 60 seconds in `JournalPttController`, and passes a complete `ShortArray` to `JournalController`. `JournalController` then encodes that memory buffer to OGG, optionally transcribes the same memory buffer, and appends directly to the daily markdown file.

This works for short bursts but makes capture durability depend on heap residency and couples primary capture, derived audio, transcription, and markdown mutation into one post-release operation. The new model makes the capture file and JSON metadata the durable source of truth, then derives OGG, transcript state, and markdown from that source.

## Goals / Non-Goals

**Goals:**

- Stream accepted Journal PTT audio directly to a durable `.capture.wav` file under the selected Journal output tree.
- Create one directory per Journal entry with a compact timestamp stem used by every artifact filename.
- Store canonical entry state in `.metadata.json` so derived work can be listed, retried, recovered, edited, removed, and rendered later.
- Encode `.recording.ogg`, transcribe text, and regenerate daily markdown as derived tasks after capture finalization.
- Remove the Journal-specific 60 second limit without introducing unbounded in-memory PCM buffering.
- Keep all work local and continue using the existing on-device Parakeet STT model and bundled OGG encoder.

**Non-Goals:**

- Migrating existing `recordings/journal-*.ogg` files or existing `journal-YYYY-MM-DD.md` files.
- Implementing Journal UI listing, edit, delete, or retry screens in this change.
- Implementing chunked or streaming transcription in this change.
- Implementing streaming OGG encoding in this change.
- Changing Debug channel, Echo, STT, TTS, or STT+TTS test recorder behavior.
- Guaranteeing cross-device merge conflict resolution for two phones writing to the same synced directory.

## Decisions

### D1: Capture-first file pipeline

**Decision**: Journal PTT will create the entry directory and metadata before recording starts, then stream microphone PCM to `<stem>.capture.wav` until PTT release. Finalization updates the WAV header and metadata. Derived tasks run only after the capture reaches a finalized state.

**Rationale**: Capture is the user action that must not be lost. A WAV container keeps the canonical capture self-describing while still storing the exact PCM that STT and OGG encoding need.

**Alternatives considered**: Keeping `ShortArray` capture preserves current code shape but retains heap growth and the 60 second cap. Streaming directly to OGG would reduce disk usage but makes partial-file recovery and STT input harder. Raw PCM sidecar files are smaller to write than WAV but require metadata to interpret sample rate, channel count, and encoding.

### D2: Entry layout and naming

**Decision**: Each accepted Journal capture gets one entry directory under the local day directory.

```text
<base>/YYYY/YYYY-MM/YYYY-MM-DD/entries/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET/
<base>/YYYY/YYYY-MM/YYYY-MM-DD/entries/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET.metadata.json
<base>/YYYY/YYYY-MM/YYYY-MM-DD/entries/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET.capture.wav
<base>/YYYY/YYYY-MM/YYYY-MM-DD/entries/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET/journal-entry-YYYY-MM-DD_HH-MM-SS-mmm-OFFSET.recording.ogg
<base>/YYYY/YYYY-MM/YYYY-MM-DD/journal-day-YYYY-MM-DD.md
```

The stem timestamp is based on capture start time. `OFFSET` is the numeric local offset without a colon, for example `-0300`. The day directory is derived from the same local start time.

**Rationale**: The directory groups current and future per-entry artifacts. Repeating the full context in filenames keeps files meaningful when copied outside the directory. Start time is stable at capture creation and supports metadata being written before audio bytes arrive.

**Alternatives considered**: Keeping a shared `recordings/` directory is simpler but does not group metadata, capture, OGG, transcript, and future artifacts. Using release time mirrors current behavior but prevents deterministic path creation before recording starts. Using ISO timestamps with colons is less portable across filesystems and sync tooling.

### D3: JSON metadata as source of truth

**Decision**: The metadata sidecar is canonical for entry identity, task state, user-visible transcript text, artifact paths, and soft deletion state. Metadata writes use a temp file plus atomic rename where the filesystem supports it.

```json
{
  "schemaVersion": 1,
  "entryId": "journal-entry-2026-06-25_14-30-00-123-0300",
  "startedAt": "2026-06-25T14:30:00.123-03:00",
  "endedAt": "2026-06-25T14:31:12.456-03:00",
  "timezoneOffset": "-0300",
  "channel": {
    "id": "journal",
    "saveVoice": true,
    "saveText": true
  },
  "capture": {
    "state": "finished",
    "path": "journal-entry-2026-06-25_14-30-00-123-0300.capture.wav",
    "sampleRate": 16000,
    "channels": 1,
    "encoding": "pcm_s16le",
    "durationMs": 72456,
    "bytes": 2318592,
    "error": null
  },
  "encoding": {
    "state": "finished",
    "path": "journal-entry-2026-06-25_14-30-00-123-0300.recording.ogg",
    "error": null
  },
  "transcription": {
    "state": "finished",
    "text": "Transcribed text.",
    "error": null
  },
  "deletedAt": null
}
```

Allowed task states are `recording`, `pending`, `running`, `finished`, `failed`, `skipped`, and `abandoned` where applicable. Capture uses `recording`, `finished`, `failed`, or `abandoned`. Encoding and transcription use `pending`, `running`, `finished`, `failed`, or `skipped`.

**Rationale**: Markdown is a projection, not a safe async state store. JSON metadata gives future UI flows a stable model for listing, editing transcripts, deleting entries, showing task failures, and retrying derived work.

**Alternatives considered**: Embedding task markers in markdown is brittle and hard to update safely. SQLite would support queries but makes Syncthing-friendly external data less inspectable. A single day-level JSON file increases write contention and makes entry-level recovery harder.

### D4: Derived task semantics

**Decision**: The `.capture.wav` file is always created for accepted Journal captures. `saveVoice` controls whether `.recording.ogg` is derived. `saveText` controls whether transcription text and markdown entry text are derived. If a derived artifact is disabled, its metadata state is `skipped`.

**Rationale**: Durable capture must exist before STT can run safely and before failures can be recovered. The OGG file remains the user-facing compressed recording controlled by `saveVoice`.

**Alternatives considered**: Deleting the WAV after transcription when `saveVoice` is disabled preserves the old "no audio retained" interpretation but removes the canonical capture and prevents retry. Keeping only OGG when `saveVoice` is enabled saves disk but makes transcription retry depend on decoding a lossy derived artifact.

### D5: Markdown regeneration

**Decision**: Daily markdown is generated from all non-deleted metadata files for the day. Rendering sorts entries by `startedAt`, writes `journal-day-YYYY-MM-DD.md.tmp`, then renames it to `journal-day-YYYY-MM-DD.md`.

**Rationale**: Regeneration makes markdown deterministic and allows transcript edits, deletion, and task retries to be reflected without fragile append-time mutation. Atomic replacement avoids partially written markdown after crashes.

**Alternatives considered**: Appending remains simple but cannot remove, edit, or reorder entries safely. Per-entry markdown files avoid whole-day rewrites but make the user-facing daily journal fragmented.

### D6: Recovery model

**Decision**: On Journal initialization, scan known entry metadata under the selected base directory. Convert stale `running` derived tasks to `pending` or `failed`, mark stale `capture.state == recording` entries as `abandoned` if no active recorder owns them, validate referenced files, and regenerate affected daily markdown files.

**Rationale**: The app can be killed while recording or while derived tasks are running. Metadata reconciliation keeps the external tree coherent without relying on in-memory jobs surviving process death.

**Alternatives considered**: Ignoring stale state leaves permanent `running` entries. Deleting incomplete directories risks losing recoverable audio. Running all derived work unconditionally at startup wastes battery and CPU.

### D7: OGG and STT input handling

**Decision**: OGG encoding and STT consume finalized capture files. The initial implementation may read the WAV PCM payload into memory for the existing native encoder and Parakeet transcriber, because capture itself no longer accumulates heap. The API boundary should accept a capture file so future chunked implementations do not change Journal orchestration.

**Rationale**: This minimizes native encoder and STT churn while removing the capture-time memory hazard. Long-file derived processing can be improved later without changing metadata or output layout.

**Alternatives considered**: Rewriting native OGG encoding for streaming now increases scope. Reworking Parakeet STT for chunked transcription now increases model integration risk and is not required for capture durability.

## Risks / Trade-offs

- **[Save voice semantic change]** -> Mitigation: Treat this as a documented behavior change. The toggle controls derived OGG output, not canonical WAV retention.
- **[Large WAV files]** -> Mitigation: WAV is required for durable lossless capture. OGG remains available as the compressed user-facing recording.
- **[Whole-file derived processing can still use large memory]** -> Mitigation: Capture no longer uses unbounded heap. Future chunked OGG/STT can use the same file-based API and metadata states.
- **[Crash during metadata or markdown write]** -> Mitigation: Use temp-file writes and atomic rename where possible. Recovery reconciles metadata and files at startup.
- **[Crash during recording]** -> Mitigation: Leave the partial WAV in the entry directory, mark metadata `abandoned`, and exclude unfinished entries from normal markdown unless a later recovery flow promotes them.
- **[Synced directory conflicts]** -> Mitigation: Timestamped per-entry directories reduce collision probability. Cross-device conflict resolution is out of scope.

## Migration Plan

- Deploy the new layout for new captures only.
- Leave existing `journal-YYYY-MM-DD.md` and `recordings/journal-*.ogg` files untouched.
- If rollback is needed, disable the new Journal pipeline and restore the previous memory-buffered pipeline; new entry directories remain inert external files.

## Open Questions

- None for this proposal. UI listing, entry deletion controls, transcript editing controls, and chunked transcription are explicitly future work.
