## Why

The current channel architecture uses independent `enabled` toggles, which allows multiple channels to appear active but complicates hardware routing. We need a more physical "radio-like" model where only one channel is active at a time, selected by interacting with the channel card. Additionally, users need immediate audio feedback when attempting to use a channel that is active but missing required configuration (e.g. Captain's Log missing a storage directory). 

## What Changes

- **BREAKING**: Remove independent `enabled` state from `Channel` model and introduce a mutually exclusive `activeChannelId` to `AppState`.
- Add a derived `isReady` state to channels to denote if they have all necessary configuration to handle PTT.
- Transform Main Dashboard cards into massive activation zones (radio buttons) where tapping the card switches the active channel.
- Migrate channel configuration UI elements (toggles, directory selection) from the main cards to dedicated configuration screens accessible via a "Config" button on the card.
- Add a two-tone characteristic error beep routed through the headset when PTT is pressed on an active but not-ready channel.

## Capabilities

### New Capabilities
- `channel-routing`: Manages mutually exclusive channel activation and delegates hardware inputs based on channel readiness.

### Modified Capabilities
- `captains-log`: Migrates configuration to a dedicated screen and calculates `isReady` based on directory and save toggles.
- `ptt-service`: Updates routing rules to respect mutually exclusive channel activation and handle the not-ready error scenario.

## Impact

- `Channel`, `CaptainsLogChannel`, `DebugChannel` models
- `AppState` (schema changes for active tracking)
- `MainDashboardScreen` (major layout change, removal of toggles)
- `PttForegroundService` (routing logic update)
- `AndroidAudio` (addition of `playErrorBeep`)
- New compose screens for `CaptainsLogConfig` and `DebugChannelConfig`