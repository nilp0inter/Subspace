## Why

The dashboard shows static mock channels with no real behavior. The next product milestone is replacing mocks with a functional channel that validates the full channel pipeline: PTT capture, audio encoding, STT transformation, persistence, and file-system output. A "Captain's Log" channel — an on-device voice journal that writes OGG recordings and markdown transcripts to a user-selected directory — exercises every layer a channel needs without introducing network dependencies. The resulting files are Syncthing/Nextcloud-friendly, giving the user off-device access for free.

## What Changes

- Introduce the channel framework: channel data model, channel configuration, channel persistence, and PTT audio routing to the active channel.
- Implement a "Captain's Log" channel type that:
  - Records each PTT capture as an OGG/Vorbis file in a date-structured directory.
  - Transcribes each capture via Parakeet STT and appends to a daily markdown log.
  - Is configurable with two toggles: "Save voice" and "Save in log file" (at least one must be on).
  - Requires the user to select a base directory before activation.
- Add a PTT audio router that dispatches PTT captures to either the active channel or the legacy test controllers (mutually exclusive).
- Decouple `SttTranscriber` from `SttTestController` so channels can invoke transcription as a stateless transform.
- Request `MANAGE_EXTERNAL_STORAGE` permission for real filesystem path access (Syncthing compatibility).
- Encode captured PCM to OGG/Vorbis via a bundled Rust/libvorbis native encoder post-PTT-release.
- Create date-structured output: `YYYY/YYYY-MM/YYYY-MM-DD/{recordings/,log-YYYY-MM-DD.md}`.

## Capabilities

### New Capabilities
- `channel-framework`: Channel data model, active channel selection, PTT routing to active channel vs test mode (mutually exclusive), channel configuration persistence.
- `captains-log-channel`: The Captain's Log channel type — OGG recording, STT transcription, markdown log writing, directory selection, toggle configuration, date-structured output.
- `ogg-encoding`: PCM-to-OGG/Vorbis encoding via MediaCodec after PTT release.

### Modified Capabilities
- `main-device-dashboard`: Dashboard transitions from static mock channels to showing the real Captain's Log channel with its configuration state and active/inactive status.

## Impact

- **New permission**: `MANAGE_EXTERNAL_STORAGE` added to manifest; runtime permission flow added.
- **Service architecture**: `PttForegroundService` gains a router layer that selects between channel dispatch and test-mode dispatch.
- **STT decoupling**: `SttTranscriber` becomes a reusable port independent of `SttTestController` lifecycle.
- **New dependencies**: Rust OGG/Vorbis encoder crates built into the APK through the existing cargo-ndk pipeline because the target device does not expose a platform Vorbis encoder.
- **Affected packages**: `model/`, `service/`, `audio/`, `ui/` (dashboard), plus new `channel/` package.
- **File output**: App writes to user-selected external storage path.
