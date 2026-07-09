## Why

Subspace's audio capture path currently resolves route ownership per PTT press but hands route lifecycle details to channel controllers, so normal release, forced cancellation, mode switching, and Telecom teardown do not share one owner. This causes route leaks, stale `AudioManager` state, and source/mode races when RSM, phone, and car PTT events interleave.

## What Changes

- Introduce a central audio input session lifecycle capability that owns one active PTT input session from press through route release.
- Move route acquisition, ready-beep sequencing, capture startup, cancellation, terminal PCM collection, and route release behind an audio input subsystem boundary.
- Add input-mode strategies for Work/RSM, On-a-pinch/phone, and On-the-road/Telecom while initially reusing the existing route resolver and route implementations.
- Change channel-facing contracts so selected channels consume channel input events and audio streams/results, not `ResolvedAudioRoute`, `ScoRoute`, `PcmOutput`, or input-mode details.
- Make forced release, mode switch, service teardown, and source-loss cleanup route-aware by cancelling the active audio input session rather than cancelling a channel by ID alone.
- Preserve existing user-visible route semantics: Work uses target RSM-owned SCO with warm retention, On-a-pinch uses local mic/output, and On-the-road uses Telecom call capture with mandatory route switch.
- Do not redesign STT/TTS models, journal persistence, keyboard HID output, UI layout, Bluetooth pairing, or release signing.

## Capabilities

### New Capabilities
- `audio-input-session-lifecycle`: Defines the central PTT audio input subsystem, session ownership, mode strategies, channel input events, and exact cleanup semantics.

### Modified Capabilities
- `channel-framework`: Channels receive input sessions/events and audio streams/results from the audio subsystem instead of owning route/capture lifecycle.
- `capture-service`: Capture remains the low-level single-`AudioRecord` owner, but a higher-level audio input subsystem owns start/cancel/release orchestration and hands channel-safe streams/results to consumers.
- `sco-audio`: Route release ownership moves from channel controllers to the central audio input session owner; route-specific release semantics remain unchanged.
- `input-mode`: Actuator auto-transition and mode-based route resolution feed the audio input subsystem strategies, and active-session gating applies across press, pending route acquisition, capture, and release.
- `telecom-voip-car-ptt`: On-the-road Telecom capture participates in the same active audio input session lifecycle, including pending route acquisition and mandatory route switch cleanup.

## Impact

- Affected production code: `PttDispatcher`, `PttForegroundService`, `ChannelRouter`, `PttAudioRouteResolver`, `CaptureService` call sites, debug channel controllers, journal/keyboard controllers, `ScoAudioController` release call ordering, Telecom car PTT glue.
- Affected tests: add direct tests for audio input session ownership, dispatcher/session gating, forced cancellation, mode switching, setup cancellation, exact route release, and channel input event delivery; update channel controller tests to stop asserting route ownership.
- No new external dependencies.
- No change to Android SDK targets, hidden API policy, Gradle/Nix tooling, or persisted channel configuration format.
