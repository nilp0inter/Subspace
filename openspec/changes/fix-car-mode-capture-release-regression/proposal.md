## Why

The car-mode PTT regression reintroduced a previously fixed failure mode: the on-the-road Telecom/SCO route can be switched or released before the active capture session has actually stopped. On affected head units this leaves the virtual call/audio route in an inconsistent state, causing hang/redial behavior and making the next steering-wheel PTT cycle unreliable.

This needs a targeted fix because the current code already has the right centralized `CaptureService` abstraction; the bug is in channel/controller release ordering and in tests that assert release happened without asserting when it happened.

## What Changes

- Restore the car-mode release sequence for all channels: stop/finalize capture first, then trigger the Telecom route switch.
- Preserve the centralized `CaptureService`; do not return to per-channel recorder ownership.
- Make Journal's no-playback path release the on-the-road route without using `play(empty)` as a route-switch side effect before `session.stop()`.
- Add regression tests that record event order, not only route-release call counts.
- Preserve existing post-refactor fixes: negotiated sample-rate propagation, Journal frames collector joining, thread-safe WAV finalization, service-owned setup-failure release, and post-capture cancellation release via `finally` where applicable.
- No breaking API changes are intended.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `on-the-road-ptt-session`: strengthen the route-switch contract so capture stop/finalization completes before Telecom/SCO route release for both playback and no-playback channels.
- `captains-log-channel`: require Journal capture finalization ordering to preserve WAV/metadata integrity before route release in car mode.
- `sco-audio`: clarify that `releaseRoute()` is the no-playback route-switch primitive and controllers must not rely on empty playback to release a route.

## Impact

- Affected code:
  - `app/src/main/java/dev/nilp0inter/subspace/channel/JournalPttController.kt`
  - `app/src/main/java/dev/nilp0inter/subspace/audio/TelecomCapturePcmOutput.kt`
  - controller tests under `app/src/test/java/dev/nilp0inter/subspace/`
- Affected behavior:
  - On-the-road Journal release must stop capture and finalize Journal artifacts before dropping Telecom/SCO.
  - No-response car-mode releases must still return the car to media mode without audible playback.
  - Echo, STT, STT↔TTS, and cancellation paths must keep their centralized capture-service semantics and balanced route release.
- No dependency, SDK, or persistence-format changes.
