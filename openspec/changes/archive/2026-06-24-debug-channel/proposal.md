## Why

The app currently couples debug audio test toggles (echo, stt, tts, stt↔tts) to the field monitor view and relies on an extraneous `AudioRouter` layer to arbitrate PTT capture between these test controllers and the only real channel (Captain's Log). Promoting diagnostics to a first-class "Debug Channel" unifies the channel framework, deletes transitional routing logic, and returns the field monitor to its pure purpose of visualizing physical button states and connections.

## What Changes

- Introduce `DebugChannel` as a first-class data model implementing `Channel`, enforcing exactly one active `DebugMode` (Echo, STT, TTS, STT↔TTS).
- Persist `DebugChannel` configuration alongside `CaptainsLogChannel`.
- Delete `AudioRouter`. Enforce mutually exclusive channel activation (switching) in the service layer, maintaining a single active channel.
- Replace the static "Diagnostics" mock channel card on the Main Dashboard with a live `DebugChannel` card.
- Move test toggle configuration into a new dedicated configuration screen for the Debug Channel.
- Remove all debug test toggles from the `MonitorScreen` (Field Monitor view).

## Capabilities

### New Capabilities
- `debug-channel`: A diagnostic channel that provides mutually exclusive test modes (echo, stt, tts, stt↔tts) and a dedicated configuration screen.

### Modified Capabilities
- `channel-framework`: Remove `AudioRouter` test-mode dispatch. Enforce single-active-channel constraint and switching logic natively between multiple real channels.
- `main-device-dashboard`: Replace Diagnostics mock channel with the real Debug Channel card, showing its configuration and status.
- `ptt-stt-test`: Move the STT test from the connected monitor/test surface to the Debug Channel configuration.
- `ptt-tts-test`: Move the TTS test from the connected monitor/test surface to the Debug Channel configuration.
- `ptt-stt-tts-test`: Move the STT↔TTS test from the connected monitor/test surface to the Debug Channel configuration.

## Impact

- **UI**: `MonitorScreen` simplified to just physical buttons/status. `MainDashboardScreen` updated with new channel card. New `DebugChannelConfigScreen` added.
- **Service**: `PttForegroundService` loses `AudioRouter` dependency, gains native channel switching logic.
- **Data Model**: `DebugChannel` and `DebugMode` enum added to `model/` and `ChannelRepository`.
