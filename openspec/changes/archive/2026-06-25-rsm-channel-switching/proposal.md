## Why

The primary interface for Subspace is the hardware Remote Speaker Microphone (RSM). Currently, switching active channels requires interaction with the Android device screen. To fully realize a voice-first interaction model, users need to navigate channels, modify state, and hear their selections directly through the headset using the RSM's hardware buttons without visual reliance.

## What Changes

- Map RSM `Group` button presses to enter `ControlMode`, playing a "Channels" audio cue.
- Map RSM `VolumeUp` and `VolumeDown` clicks while in `ControlMode` to cycle the globally `activeChannelId`, accompanied by immediate audio feedback of the channel name (e.g., "Journal Channel").
- Map RSM `PTT` presses while in `ControlMode` to confirm the selection (e.g., playing "Journal Channel Selected") and return the hardware to `ActiveMode`.
- Introduce a shared `SystemAnnouncer` that memoizes TTS phrases (via `SupertonicJniSynthesizer`) on boot. This trades a negligible amount of heap memory (~250KB) for zero-latency audio playback, eliminating the ~500ms inference delay during navigation.
- Ensure all hardware-driven state mutations propagate instantly to the Jetpack Compose UI via the existing `AppState` state flows.

## Capabilities

### New Capabilities

- `rsm-audio-navigation`: Headless audio menu system mapping physical hardware inputs (Group, Vol Up/Down, PTT) to state transitions and zero-latency memoized voice announcements over the headset.

### Modified Capabilities

- (None)

## Impact

- **UI**: The `MainDashboardScreen` will automatically highlight the active channel as the user navigates via hardware, driven by UDF.
- **Service**: `PttForegroundService` adds the `SystemAnnouncer` lifecycle (pre-compute on initialization, cancel/play on events).
- **Audio**: Expands SCO route usage to system menu navigation.
