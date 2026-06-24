## Context

The app currently has a `PttForegroundService` that owns all state and dispatches PTT events to mutually-exclusive test controllers (Echo, STT, TTS, STT+TTS). There is no channel concept in code — the dashboard shows three static `MockChannel` entries with no wired behavior. STT transcription is coupled to `SttTestController` and cannot be invoked independently.

The next milestone is to replace mock channels with the first real channel behavior, establishing the routing and persistence patterns all future channels will inherit.

## Goals / Non-Goals

**Goals:**
- Establish a channel data model and configuration persistence layer.
- Build a PTT audio router that dispatches captures to either the active channel or test mode (mutually exclusive).
- Implement the Captain's Log channel: OGG recording + markdown transcription to a user-selected directory.
- Decouple `SttTranscriber` into a reusable stateless transform.
- Use `MANAGE_EXTERNAL_STORAGE` with real filesystem paths for Syncthing/Nextcloud compatibility.

**Non-Goals:**
- Multiple simultaneous channels (only Captain's Log for now).
- Channel switching via hardware controls (future work).
- Inbound messages, autoplay, or backlog.
- Network-backed channels.
- Channel history mode or replay via hardware.
- Play Store compliance (sideload/F-Droid only).

## Decisions

### D1: PTT routing architecture

**Decision**: Introduce an `AudioRouter` layer between `ButtonStateMachine` and the existing test controllers. The router checks: if an active channel is configured and enabled, dispatch to channel controller; otherwise, dispatch to the active test controller. The two paths are mutually exclusive — activating a channel disables test mode and vice versa.

**Rationale**: Keeps test mode available for hardware diagnostics without conflicting with real channel behavior. Avoids coupling the channel framework to the test controller lifecycle.

**Alternatives considered**:
- Replace test controllers entirely with channels: Premature — test modes are valuable for development and have no channel semantics (no history, no persistence).
- Allow both simultaneously: Creates ambiguity about where PTT audio goes.

### D2: Channel data model

**Decision**: A channel is a sealed interface `Channel` with type-specific subtypes. The Captain's Log is one concrete implementation. Channel configuration is stored as a serialized data class in `SharedPreferences` (or DataStore) keyed by channel ID.

```
sealed interface Channel {
    val id: String
    val name: String
    val enabled: Boolean
}

data class CaptainsLogChannel(
    override val id: String = "captains-log",
    override val name: String = "Captain's Log",
    override val enabled: Boolean = false,
    val baseDirectory: String? = null,
    val saveVoice: Boolean = true,
    val saveText: Boolean = true,
) : Channel
```

**Rationale**: Sealed interface allows exhaustive when-expressions. SharedPreferences is simple and sufficient for a single channel; DataStore is overkill at this stage.

**Alternatives considered**:
- Room/SQLite: Overkill for one channel's configuration.
- File-based JSON config: No benefit over SharedPreferences for structured key-value data.

### D3: OGG encoding via bundled Rust native encoder

**Decision**: After PTT release, encode the captured PCM buffer to OGG/Vorbis synchronously using a bundled Rust native encoder built through the existing cargo-ndk pipeline. The encoding runs on a coroutine dispatcher (IO). Expected latency remains acceptable for a typical PTT burst and is isolated from the capture path.

**Rationale**: The target device does not expose `MediaCodec.createEncoderByType("audio/vorbis")`, returning `NAME_NOT_FOUND`, and available FFmpegKit/mobile-ffmpeg Maven metadata is not resolvable from normal Gradle repositories. A project-owned Rust native encoder makes OGG output device-independent and preserves the required file format without relying on stale Android AAR artifacts. Synchronous post-capture encoding is simpler than streaming and matches the "one PTT press = one atomic message" model.

**Alternatives considered**:
- Streaming encode during capture: Complex buffer management, risk of partial files on cancellation.
- Platform `MediaCodec` + `MediaMuxer`: Not viable on the target hardware because no Vorbis encoder is exposed.
- Android FFmpeg dependency: Preferred for speed, but the available Maven metadata is stale/unresolvable from Gradle in this repository.
- WAV output: Simpler but large files, user explicitly wants OGG.

### D4: File access via MANAGE_EXTERNAL_STORAGE

**Decision**: Request `MANAGE_EXTERNAL_STORAGE` at runtime. Use SAF (`ACTION_OPEN_DOCUMENT_TREE`) for the user gesture to pick a directory, then resolve the content URI to a real filesystem path. All subsequent I/O uses `java.io.File` APIs on the resolved path.

**Rationale**: Real filesystem paths are required for Syncthing/Nextcloud compatibility — those tools watch real paths, not content URIs. `MANAGE_EXTERNAL_STORAGE` grants broad file access which supports any directory the user picks. App is sideloaded, so Play Store policy is not a constraint.

**Alternatives considered**:
- Pure SAF with DocumentFile: Content URIs are incompatible with sync tools.
- App-private external storage: User cannot freely choose the path; sync tool access is limited.

### D5: STT decoupling

**Decision**: Extract a `TranscriptionService` class that wraps the existing `SttTranscriber` port and provides a suspend function: `suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String`. The `SttTestController` continues to exist but delegates to `TranscriptionService`. The Captain's Log channel also invokes `TranscriptionService` directly.

**Rationale**: The `SttTranscriber` interface is already a clean port. The only coupling is lifecycle (model loading). By wrapping it in a service-scoped singleton, both test mode and channels share the same loaded model without conflicting.

**Alternatives considered**:
- Pass `SttTranscriber` directly to channels: Works but leaves lifecycle management scattered.
- Load separate model instances per consumer: Wasteful — Parakeet model is ~100MB in memory.

### D6: Directory structure and naming

**Decision**:
```
<base>/YYYY/YYYY-MM/YYYY-MM-DD/recordings/log-YYYY-MM-DD_HH-MM-SS.ogg
<base>/YYYY/YYYY-MM/YYYY-MM-DD/log-YYYY-MM-DD.md
```

Timestamps use hyphens (not colons) for cross-platform filesystem safety. Directories are created on-demand when the first entry for that date is written.

**Rationale**: Hierarchical date structure keeps directories small and browsable. Relative paths in markdown (`recordings/log-...ogg`) make the entire tree portable and self-contained.

### D7: Markdown format

**Decision**:
```markdown
# Log 2026-06-24

## Entry 14-30-00

Transcribed text of the PTT capture goes here.

[Source recording](recordings/log-2026-06-24_14-30-00.ogg)
```

- H1 is the daily header, written once at file creation.
- H2 per entry, with timestamp.
- Transcribed text as body paragraph.
- Relative link to recording only if "Save voice" is enabled.
- New entries appended at end of file.

**Rationale**: Standard markdown readable by any tool. Relative links preserve portability when synced.

### D8: Toggle validation (illegal state prevention)

**Decision**: The channel configuration UI enforces that at least one of "Save voice" / "Save in log file" is enabled. If the user disables the last active toggle, the UI re-enables it or blocks the action. The channel data model asserts this invariant at construction time.

**Rationale**: Both toggles off means PTT does nothing, which is confusing and wasteful. Making it an illegal state keeps the system predictable.

## Risks / Trade-offs

- **[Native encoder maintenance]** → Mitigation: keep the JNI boundary narrow (`ShortArray` + path + sample rate), use existing cargo-ndk build wiring, and keep the Kotlin `AudioEncoder` interface so the implementation can be replaced later.
- **[MANAGE_EXTERNAL_STORAGE rejection on future Android versions]** → Mitigation: Not a concern for sideload. If needed later, can migrate to SAF-only with path resolution heuristics.
- **[URI-to-path resolution fragility]** → Mitigation: Use `Environment.getExternalStorageDirectory()` as base plus the relative tree from SAF. Fall back to `DocumentFile` if resolution fails. Test on target device (Android 12).
- **[STT model load time blocking channel activation]** → Mitigation: Model is already loaded at service startup (current behavior). Channel activation does not trigger a new load.
- **[Large OGG files from long PTT holds]** → Mitigation: Existing 60s max capture applies. A 60s mono 16kHz Vorbis file is ~500KB, acceptable.
