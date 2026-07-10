## Why

Recent route-gating and channel-commitment changes fixed car capture delivery, but introduced terminal-path defects: an On-the-road session can leak its Telecom route during setup cancellation, a normal car call hang can race into cancellation instead of finalizing Journal, and capture may commit data collected before its ready beep. Android silencing evidence and car-HFP timeout cleanup are also incomplete, so the ready beep can still promise an unusable capture path.

## What Changes

- Make audio-input session terminal ownership atomic so normal release, forced cancellation, setup failure, and stale callbacks cannot race or double-clean a route.
- Guarantee exactly-once Telecom cleanup for pending, short-press, and active On-the-road sessions; retain Work warm-release semantics and local no-op release semantics.
- Treat a Telecom hang after capture starts as normal terminal release, delivering the terminal recording to the committed channel before route cleanup; reserve cancellation for captures that cannot yield a terminal recording.
- Finalize accepted Journal captures on car hang, including terminal metadata, derived OGG/transcription processing, and daily Markdown regeneration.
- Ensure channel-visible live and terminal PCM starts only after the ready beep, even when capture hardware must be opened for preflight.
- Populate production recorder-silencing evidence and reject a silenced recorder before ready beep/channel commitment.
- Apply observed Work-route release gates to non-capture feedback and stop a car HFP voice-recognition prime when its readiness wait fails.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audio-input-session-lifecycle`: serialize terminal session ownership and exact-once route cleanup across normal release, cancellation, and setup suspension.
- `capture-service`: separate recorder preflight from channel-visible post-ready-beep PCM and enforce production recorder-silencing rejection.
- `telecom-voip-car-ptt`: define normal car hang finalization, pre-capture cancellation cleanup, and failed HFP-prime cleanup.
- `captains-log-channel`: require accepted car captures to reach terminal metadata and derived processing when the call is hung normally.
- `sco-audio`: require non-Work feedback to honor the observed Work-route release gate and preserve route-specific cleanup semantics.

## Impact

- Production code: `PttAudioSessionManager`, `PttForegroundService`, Telecom lifecycle/coordinator/connection, `CaptureService`, Android capture source, route feedback, car HFP startup, and `JournalPttController`.
- Tests: focused unit coverage for race ordering, setup cancellation, short car presses, pre-beep PCM discard, Android recorder evidence, HFP timeout cleanup, and Journal terminal persistence.
- Runtime behavior: no new dependencies, persistence format, UI flows, pairing behavior, or public channel-facing route APIs.
