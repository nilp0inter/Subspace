## Why

On-the-road/car PTT can complete the Telecom call lifecycle while no audio reaches the selected channel: the VU meter stays flat and Journal creates no entry. The missing contract is not only route readiness; the ready beep must mean the selected channel has accepted the input and post-beep audio will reach it, while the problem beep must mean this PTT will not reach the channel.

## What Changes

- Define ready beep as a mandatory input-subsystem commit signal: after the ready beep, audio captured from that PTT SHALL reach the selected channel.
- Define problem beep as the user-visible rejection/failure signal: audio hardware or Telecom may work, but this PTT will not reach the selected channel.
- Add an input-subsystem channel commitment barrier before ready beep.
- Prevent channel controllers from silently refusing input after the input subsystem has committed a PTT.
- Make On-the-road ready beep Telecom-aware: play the mandatory ready beep through the active car call route instead of using `AudioManager.communicationDevice` as the sole hard proof.
- Preserve the route/channel boundary: channels may expose accept/refuse information and consume committed input, but SHALL NOT own beeps, Android route state, Telecom state, SCO, `PcmOutput`, `CaptureSource`, or `ResolvedAudioRoute`.
- Add tests for ready/problem beep ordering, channel commitment/refusal, Telecom car call success without channel commitment, and live PCM/channel-entry evidence.
- Do not redesign Journal, STT, TTS, keyboard HID, Bluetooth pairing, UI layout, Gradle/Nix, Android SDK targets, release signing, or persisted channel configuration.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `audio-input-session-lifecycle`: Ready/problem beep semantics, channel commitment before ready beep, and pre-commit failure cleanup become part of the input lifecycle contract.
- `telecom-voip-car-ptt`: On-the-road ready beep SHALL be routed through the Telecom/call audio path after car route readiness and selected-channel commitment.
- `channel-framework`: Channel controllers SHALL expose structured input acceptance/commitment and SHALL NOT silently drop a committed input session.
- `capture-service`: Capture setup and ready-beep sequencing SHALL support the invariant that post-ready-beep audio reaches the committed channel.

## Impact

- Affected production code: `PttAudioSessionManager`, `PttDispatcher`, `ChannelRouter`, `PttForegroundService`, channel controllers that currently silently return from input start, `CaptureService`, `TelecomCapturePcmOutput`, `AndroidPcmOutput`, and On-the-road route resolution.
- Affected tests: audio input session manager tests, channel router/controller tests, Telecom car PTT tests, capture service sequencing tests, and manual `B02PTT-FF01` car validation.
- No route facts or Android API objects exposed to channels.
- No new external dependencies.
- No hidden Android APIs.
