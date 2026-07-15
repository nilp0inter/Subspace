## Why

A powered-off but bonded RSM causes automatic SPP reconnect attempts to end repeatedly, and each ended attempt currently force-cancels whichever PTT session is active. This violates source ownership: unrelated Phone and On-the-road captures stop after a few seconds, while pending car calls can be aborted silently before their own route lifecycle terminates.

## What Changes

- Make PTT cancellation source-scoped so RSM serial lifecycle events can terminate only RSM-owned input sessions.
- Make Telecom route timeout and setup-failure callbacks terminate only their owned pending or active CarTelecom session.
- Preserve an explicit all-source cancellation operation exclusively for whole-service shutdown or another true global teardown.
- Keep automatic RSM serial reconnect running while Phone or On-the-road capture is active without allowing reconnect failure, explicit serial disconnect, or serial stream loss to disturb those sessions.
- Persist diagnostic events for reconnect attempt termination, source-scoped cancellation requests, rejected cross-source cancellation, and audio-session terminal ownership/reason.
- Preserve the current configured-car identity, HFP priming, exact-device Telecom routing, bounded Telecom route timeout, phone PTT gesture semantics, and 60-second capture limit.
- Do not add a car-route retry policy or claim to correct Android/Android Auto refusing an exact car route after configuration; that independently observed downstream route-acquisition failure remains outside this ownership correction.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audio-input-session-lifecycle`: Require non-global cancellation signals to identify and match the session source before claiming terminal ownership.
- `device-auto-reconnect`: Require automatic and explicit RSM serial teardown to preserve Phone and CarTelecom sessions while retaining reconnect behavior.
- `telecom-voip-car-ptt`: Restrict Telecom timeout, connection, and pending-setup cancellation to CarTelecom ownership.
- `phone-channel-card-ptt`: Preserve held and locked Phone sessions across unrelated RSM serial and Telecom lifecycle events.
- `observability-logs`: Persist non-identifying cancellation provenance and terminal-reason diagnostics needed to attribute field failures.

## Impact

Affected code is concentrated in `PttForegroundService`, `PttDispatcher`, `PttAudioSessionManager`, RSM SPP reconnect teardown, and Telecom callback wiring. Focused JVM tests must cover source mismatch, serial reconnect failure during Phone and CarTelecom capture, Telecom timeout during Phone capture, global shutdown, exact-once terminal cleanup, and diagnostic redaction. No Android permissions, hidden APIs, external dependencies, storage migrations, channel contracts, Bluetooth identity policy, or UI workflow changes are introduced.
