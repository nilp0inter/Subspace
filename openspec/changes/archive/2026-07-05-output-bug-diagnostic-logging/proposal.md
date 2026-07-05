## Why

Mixed car-plus-RSM testing for `fix-mode-audio-routing` shows that car mode works, but RSM and mobile flows can still record or play through the car when the car is connected. The target phone does not expose the RSM `AudioDeviceInfo.productName`, so the next step is a diagnostic-only trace that proves which Android communication endpoint is scanned, selected, retained, or leaked by each route.

## What Changes

- Add temporary debug route diagnostics under one logcat tag, `SubspaceRoute`, for mixed car/RSM/mobile testing.
- Log PTT source-to-mode transitions, resolved route endpoint, Android audio mode, current communication device, and available communication devices at route boundaries.
- Log `ScoAudioController` RSM route scans, anonymous-SCO fallback selection, `setCommunicationDevice()` result, selected communication device, acquire outcome, and warm-release lifecycle.
- Log Telecom car call-audio accept/reject state and Telecom capture-route release snapshots.
- Log local/mobile route resolution snapshots so stale car SCO or `MODE_IN_COMMUNICATION` state is visible before phone-local capture/playback.
- Do not change routing decisions, endpoint policy, audio behavior, UI, or persistence in this change.
- Do not log PCM/audio content or Bluetooth MAC addresses.

## Capabilities

### New Capabilities
- `audio-route-diagnostics`: Debug-only route tracing for identifying which Android audio endpoint each PTT mode selects and retains during mixed car/RSM testing.

### Modified Capabilities

None. This change adds observability only and does not change product routing requirements.

## Impact

- Affected code: `PttForegroundService`, `ScoAudioController`, `AndroidAudioDevices`, `SubspaceConnection`, and optionally `TelecomCarPttCoordinator` for lifecycle state logging.
- Runtime behavior: debug logs only; no route clearing, no acquisition policy changes, no output routing changes.
- Test flow: requires manual logcat capture while exercising car PTT, RSM PTT with car connected, and phone/local PTT after the failing RSM path.
- Privacy: diagnostics include Android audio device ids, types, product names, mode, route branch, and Bluetooth display names when already exposed by Telecom call-audio state; diagnostics exclude MAC addresses and audio samples.
