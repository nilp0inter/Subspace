## Context

`fix-mode-audio-routing` is intended to make `InputMode.Work` use the RSM, `InputMode.OnTheRoad` use Telecom car call audio plus car media response output, and `InputMode.OnAPinch` use local phone audio. Manual testing shows `OnTheRoad` works, but with both car and RSM connected the RSM button can still route capture/playback through the car, and the subsequent phone-local path can become silent or appear to capture through the car.

The target phone does not expose the RSM name through `AudioDeviceInfo.productName`, so existing product-name matching cannot explain the real endpoint selected by Android. The current investigation needs observability around route resolution and Android audio-device state before any routing behavior changes.

## Goals / Non-Goals

**Goals:**

- Produce a compact logcat trace that correlates PTT source, active `InputMode`, resolved route endpoint, Android audio mode, current communication device, and available communication devices.
- Prove whether `ScoAudioController` uses the anonymous-SCO fallback when the car and RSM are both connected.
- Prove whether `setCommunicationDevice()` selects the requested device or Android switches to a different active communication endpoint.
- Prove whether Work-mode warm retention leaves a car SCO communication route active before OnAPinch/local capture and playback.
- Prove whether Telecom car call-audio readiness is accepting the car route and rejecting the RSM route as intended.
- Keep the diagnostic patch small and easy to remove after the routing bug is understood.

**Non-Goals:**

- No routing fixes.
- No changes to endpoint matching, anonymous-SCO fallback behavior, route acquisition, route release, or audio focus behavior.
- No UI or persistence changes.
- No audio sample, PCM buffer, recording, transcript, Bluetooth MAC address, or secret logging.
- No attempt to infer the final route policy from these logs inside this change.

## Decisions

### D1. Use one dedicated logcat tag

Use `SubspaceRoute` for new diagnostic lines, regardless of whether the log originates in service, audio, or Telecom code. This makes collection deterministic with:

```sh
nix develop --no-write-lock-file -c adb logcat -s SubspaceRoute:D '*:S'
```

Alternative considered: reuse existing class tags such as `SubspacePttService`. Rejected because route state spans multiple files and a single tag avoids incomplete captures.

### D2. Centralize `AudioDeviceInfo` formatting

Add internal diagnostic formatting near the existing audio-device helpers so every log line uses the same fields:

- `id`
- numeric and friendly `type`
- `productName`
- `isBluetoothScoEndpoint`
- `isTargetRsmScoEndpoint`

The formatter must tolerate null devices and missing product names.

Alternative considered: inline string formatting at each call site. Rejected because inconsistent formatting would make manual log comparison harder.

### D3. Log branch decisions, not only final state

`ScoAudioController` must log both the device list and the selection reason:

- `target-product-name`
- `anonymous-hfp-fallback`
- `none`

The selected branch is more important than the selected device alone because the suspected failure is that the anonymous fallback can accept a car SCO endpoint when RSM HFP is connected.

Alternative considered: log only the current `communicationDevice` after acquire. Rejected because it cannot distinguish Android changing the route from the app choosing the wrong candidate.

### D4. Snapshot route boundaries in `PttForegroundService`

Log snapshots at PTT and route boundaries:

- before and after `autoTransitionFor(source)`
- after `resolvePttAudioRoute(mode)`
- before local/OnAPinch route use
- before and after Telecom capture-route release

Each snapshot should include current mode, availability, Android audio mode, current communication device, and available communication devices.

Alternative considered: log only inside route classes. Rejected because the observed bug crosses mode transition, route selection, warm retention, and local route use.

### D5. Keep diagnostics behavior-neutral

Logging must not call `clearCommunicationDevice()`, alter `audioManager.mode`, reorder route operations, delay polling, change acquisition timeouts, or change release behavior. The diagnostic patch must be safe to compare against the current failing behavior.

Alternative considered: add a defensive local-route reset while logging. Rejected because that would mix diagnosis with a potential fix and could hide the stale-route evidence.

## Risks / Trade-offs

- Log volume may be high during PTT testing -> keep logs limited to route boundaries, acquire/release transitions, and Telecom call-audio changes.
- Android device names/product names may still be null or generic -> include ids and type labels so transitions can still be correlated within a single logcat session.
- Device ids may not be stable across reconnects -> treat ids as per-session correlation only.
- Logging Bluetooth display names may expose user/device names in local logs -> do not log MAC addresses; use logs only for manual debugging and remove or gate them after the issue is understood.
- Diagnostic code can drift into permanent behavior -> keep the capability explicitly diagnostic-only and avoid routing changes in the same patch.

## Migration Plan

1. Add diagnostic helpers and log points.
2. Build and install the debug APK.
3. Clear logcat and capture `SubspaceRoute` while exercising the failing mixed car/RSM/mobile sequence.
4. Use the trace to decide the next routing fix.
5. Remove or gate diagnostics once the endpoint behavior is understood.

Rollback is deleting the diagnostic log helpers and call sites. No data migration is involved.

## Open Questions

- Does Android expose one anonymous SCO route or multiple SCO `AudioDeviceInfo` entries when both car and RSM are connected?
- Does `setCommunicationDevice()` keep the requested device active, or does the system replace it with the car endpoint?
- Is mobile/local playback silent because Work-mode warm retention leaves car SCO active, or because local media playback chooses an unexpected car/media route even after normal mode is restored?
