## Why

Recent route-gating and channel-commitment changes fixed car capture delivery, but introduced terminal-path defects: an On-the-road session can leak its Telecom route during setup cancellation, a normal car call hang can race into cancellation instead of finalizing Journal, and capture may commit data collected before its ready beep. Physical validation also found opened recorders with zero-only PCM, overlapping HFP/Telecom ownership, transient or wrong Bluetooth call routes, Telecom reselecting a late-connected RSM after a successful exact-car preflight request, stale Android Auto terminal state, and phone responses that reached the Android Auto remote submix without audible focused playback.

## What Changes

- Make audio-input session terminal ownership atomic so normal release, forced cancellation, setup failure, and stale callbacks cannot race or double-clean a route.
- Guarantee exactly-once Telecom cleanup for pending, short-press, active, and pre-response On-the-road sessions; retain Work warm-release semantics and local no-op release semantics.
- Treat a Telecom hang after capture starts as normal terminal release, delivering the terminal recording to the committed channel before route cleanup; reserve cancellation for captures that cannot yield a terminal recording.
- Finalize accepted Journal captures on car hang and await terminal metadata, derived OGG/transcription processing, and daily Markdown regeneration before publishing terminal completion.
- Ensure channel-visible live and terminal PCM starts only after the ready beep, even when capture hardware must be opened for preflight.
- Populate production recorder-silencing evidence and reject a silenced recorder before ready beep/channel commitment.
- Apply observed Work-route release gates to non-capture feedback and stop a car HFP voice-recognition prime when its readiness wait fails.
- Require production recorders to prove nonzero PCM before readiness, use a bounded same-route recorder retry, recheck cancellation before exhausted-attempt failure, and keep committed reads blocking after nonblocking preflight.
- Select and prime one unambiguous non-RSM car by exact `BluetoothDevice` identity, reserve that identity across asynchronous Telecom connection creation, repeatedly reacquire only that exact route when Android overrides it, and require it to remain continuously active before capture.
- Derive Android Auto Recording/Finalizing/Ready state, terminal-completion idle retention, and Play/Pause/Stop behavior from owned PTT phases rather than callback timing.
- Route On-a-pinch recorded responses through stable normal-media readiness and transient media focus while keeping ready/problem beeps on the existing raw local feedback output.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audio-input-session-lifecycle`: serialize terminal session ownership and exact-once route cleanup, own the single pre-response Telecom release, publish terminal phases, and gate local response playback on stable focused media routing.
- `capture-service`: separate recorder preflight from channel-visible post-ready-beep PCM, enforce production recorder-silencing and PCM-liveness rejection, retry one zero-only recorder without route reacquisition, preserve final-window cancellation, and use blocking reads after commitment.
- `telecom-voip-car-ptt`: define normal car hang finalization, pre-capture and pre-response cleanup, exact-device HFP selection and handoff, one-shot expected-device reservation, bounded exact-route reacquisition, identity-based route stabilization, and terminal Android Auto state/control/idle-retention behavior.
- `captains-log-channel`: require accepted car captures to await terminal metadata and derived processing when the call is hung normally.
- `sco-audio`: require non-Work feedback to honor the observed Work-route release gate and preserve route-specific cleanup semantics.

## Impact

- Production code: `PttAudioSessionManager`, `PttForegroundService`, Telecom lifecycle/coordinator/connection, `CaptureService`, Android capture sources, route feedback, car HFP startup, Android Auto media session state/controls, and response playback.
- Tests: focused unit coverage for terminal races, setup/final-window cancellation, short car presses, pre-beep discard, recorder silencing/liveness/retry, split read modes, exact HFP selection/handoff, expected-device reservation, exact-route retry serialization and cancellation, identity-based route stabilization, exact-once car response cleanup, joined Journal processing, terminal media state, focused local playback, and Journal persistence.
- Runtime behavior: no new dependencies, persistence format, pairing behavior, hidden APIs, or public channel-facing route APIs.
