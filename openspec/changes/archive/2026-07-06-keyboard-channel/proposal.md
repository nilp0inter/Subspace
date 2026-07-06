## Why

Subspace captures PTT audio and transcribes it on-device, but the transcript is
currently terminal — it lands in the journal or the debug panel and stays on the
phone. The natural next step for a walkie-talkie-style capture is to *type* the
transcribed text into a remote host as if a physical keyboard were pressing the
keys. `sleepwalker` (github.com/nilp0inter/sleepwalker) provides exactly that
primitive: a BLE→USB-HID bridge whose `sleepwalker-core` Kotlin library resolves
text to low-level HID operations and frames them for the firmware. Adding a
Keyboard channel closes the loop — speak into the RSM, the on-device STT
transcribes, and the text is injected into the paired host's keyboard input
stream.

## What Changes

- Add a new `KeyboardChannel` subtype of the `Channel` sealed interface, with a
  configurable target host profile (`HostProfile` from `sleepwalker-core`) and a
  readiness state that requires both the STT model and a connected sleepwalker
  BLE bridge.
- Add a `KeyboardPttController` that reuses the existing capture → STT pipeline
  (`CaptureService` + `PcmTranscriber`) and, on a successful transcript, plans
  the text via `TextPlanner` and sends the resulting `LowLevelOp` frames over
  the sleepwalker BLE connection.
- Integrate `sleepwalker-core` as a Gradle module (vendored copy or submodule
  under the repo root, `include(":sleepwalker-core")` in `settings.gradle.kts`,
  `implementation(project(":sleepwalker-core"))` in `app/build.gradle.kts`).
- Add a BLE connection manager for the sleepwalker bridge: scan by device name
  `"sleepwalker"`, discover `BleUuids.SERVICE`, enable TX notifications, request
  MTU 247, write RX with `WRITE_TYPE_NO_RESPONSE`. The integrator owns the
  connection lifecycle per the library contract.
- Extend `ChannelRepository` to persist `KeyboardChannel` configuration (host
  profile, optional device address) and include it in `loadChannels()`.
- Extend the PTT dispatch `when` arms in `PttForegroundService`
  (`dispatchPttPressed`, `dispatchPttReleased`, `cancelActiveSession`,
  `updateActiveControllers`) with a `KeyboardChannel.ID` branch.
- Add a `KeyboardChannelConfigScreen` for selecting the host profile and
  observing bridge connection state, wired into the dashboard navigation like
  the existing journal/debug config screens.
- Add a `KeyboardStatus` monitor state (Idle, Recording, Transcribing, Typing,
  Done, Error) surfaced in `MonitorState` and the dashboard.

## Capabilities

### New Capabilities

- `keyboard-channel`: A channel that transcribes PTT audio via the existing STT
  pipeline and types the resulting text into a remote host through the
  sleepwalker BLE→USB-HID bridge. Covers channel identity, readiness (STT model
  ready + bridge connected), PTT lifecycle (capture → transcribe → plan → type),
  bridge connection management, and status reporting.

### Modified Capabilities

- `channel-framework`: Adds a third `Channel` subtype (`KeyboardChannel`) with
  its own persisted configuration and readiness rule, and extends
  `ChannelRepository` to load/save/persist it alongside the journal and debug
  channels.
- `channel-routing`: Adds a `KeyboardChannel.ID` dispatch arm to PTT
  press/release/cancel and to `updateActiveControllers`, so the keyboard
  controller is enabled/disabled and torn down under the same mutual-exclusion
  and readiness rules as the existing channels.

## Impact

- **New code**: `KeyboardChannel` model, `KeyboardPttController`,
  `SleepwalkerBleConnection` (scan/connect/write/notify), `KeyboardChannelConfigScreen`,
  `KeyboardStatus` state, BLE permissions in `AndroidManifest.xml`
  (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` are already present for the RSM; the
  sleepwalker scan reuses them).
- **Modified code**: `Channel.kt` (new subtype + `orderIndex` branch),
  `ChannelRepository.kt` (load/save keyboard, include in `loadChannels`),
  `ChannelBrowseEntry.kt` (`orderIndex` branch), `PttForegroundService.kt`
  (dispatch arms, controller wiring, lifecycle), `Models.kt` (`MonitorState`
  fields), `MainActivity.kt` (config screen route), dashboard UI (channel card).
- **Build system**: `settings.gradle.kts` includes `:sleepwalker-core`;
  `app/build.gradle.kts` depends on it. The `sleepwalker-core` source is
  vendored at `sleepwalker-core/` in the repo root (or added as a git
  submodule). `minSdk` for the library is 26; the app's `minSdk = 31` satisfies
  this.
- **Dependencies**: No new Maven dependencies; `sleepwalker-core` depends only
  on Android BLE APIs. The app already has `kotlinx-coroutines-android`.
- **Native build**: Unaffected. STT reuses the existing Parakeet JNI path; no
  new native libraries.
- **Permissions**: `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` already declared and
  runtime-requested for the RSM; the sleepwalker scan reuses the same grants.
  No new manifest permissions required.