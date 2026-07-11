## Why

Several device-control and bootstrap behaviors remain inconsistent with the intended hands-free experience: RSM channel navigation runs opposite to the physical up/down controls, list boundaries provide no feedback, a bonded RSM requires one manual serial connection before automatic reconnect becomes effective, and system announcements render with an unnecessarily high synthesis step count. These behaviors are independent of the channel-runtime generalization and need explicit requirements so they do not regress.

## What Changes

- Map RSM Volume Up to the preceding/up channel and Volume Down to the following/down channel while preserving saturating, non-wrapping traversal.
- Play the existing error beep over the serialized SCO announcement path when the user attempts to move above the first channel or below the last channel.
- Establish serial monitoring intent when the device-link service starts and schedule an immediate first SPP connection whenever permissions, Bluetooth, and bonded-target prerequisites are already satisfied.
- Preserve explicit disconnect as cancellation of monitoring intent, preserve serialized delayed retries after unexpected loss, and keep manual setup/connect actions as fallback controls.
- Render every system-announcement TTS cache miss with exactly four synthesis steps and include the same value in persistent cache identity so older render settings cannot be reused.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `rsm-audio-navigation`: Corrects physical Volume Up/Down channel direction and adds serialized SCO error feedback at non-wrapping list boundaries.
- `device-auto-reconnect`: Extends automatic SPP behavior to the first eligible service startup rather than requiring a prior manual serial session.
- `announcement-vocabulary-cache`: Changes the canonical system-announcement synthesis step count and cache identity from twenty steps to four.

## Impact

- Affects RSM Control-mode event-to-offset mapping and boundary feedback in `PttForegroundService`.
- Extends `SystemAnnouncer` serialized SCO playback to support the existing error beep without overlapping channel announcements.
- Affects `ReconnectPolicy`, service startup/readiness evaluation, manual serial connection delegation, and SPP attempt scheduling.
- Changes persistent announcement fingerprints and therefore intentionally regenerates cached announcement PCM once after deployment.
- Adds no external dependencies and does not change PTT capture routing, Android Auto next/previous semantics, Bluetooth pairing, or user-configurable Debug TTS/STT-TTS parameters.
