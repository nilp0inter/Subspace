## Context

The previous audio input session refactor moved route ownership toward a central audio input subsystem: `PttAudioSessionManager` owns active PTT sessions, selected routes stay internal, and channels consume only channel input events, live PCM, terminal `RecordedPcm`, cancellation, and failure.

The current implementation still contains time-bounded gates that can continue even when Android has not proven route state. The failing case is Work/RSM PTT followed by On-the-road/car PTT inside the 30-second Work SCO warm-retention window. The car Telecom call can run and appear to record while channel audio is empty or never starts, because Telecom route state, Bluetooth HFP audio state, `AudioManager.communicationDevice`, and actual `AudioRecord` capture state are not treated as one observed readiness contract.

Useful existing building blocks:

- `PttAudioSessionManager` already owns session identity, setup, release, and channel event emission.
- `ScoAudioController` already owns Work/RSM SCO acquisition, warm retention, and immediate release hooks.
- `CarTelecomStarter`, `TelecomCarPttLifecycle`, and `SubspaceConnection` already model self-managed Telecom call setup and call audio route callbacks.
- `CaptureService` and `AndroidCaptureSource` already centralize `AudioRecord` startup and live PCM production.
- `ResolvedAudioRoute` already keeps route/output/source details internal to the audio subsystem.

## Goals / Non-Goals

**Goals:**

- Replace success-by-time route transitions with success-by-observed-state gates inside the audio input subsystem.
- Keep timeouts as fail-closed bounds: a timeout may cancel or fail a route transition, but must not prove success.
- Preserve Work/RSM warm SCO reuse when the next input is Work/RSM.
- Require observed RSM release before switching from warm Work/RSM to Phone or On-the-road.
- Require observed car/Telecom readiness before On-the-road capture is reported to channels.
- Release Telecom/car route ownership when setup fails before capture starts.
- Keep channels isolated from OS route details and route objects.
- Add tests around route state gates, setup failure cleanup, and channel-boundary preservation.

**Non-Goals:**

- No channel controller rewrite beyond consuming existing channel input contracts.
- No STT, TTS, STT↔TTS, journal, keyboard HID, or model behavior redesign.
- No Bluetooth pairing, SPP protocol, Android Auto browsing, UI layout, release signing, Gradle, Nix, or SDK target changes.
- No hidden Android APIs or reflection.
- No new external dependencies.
- No attempt to guarantee non-silent semantic speech; the system only needs to prove route/capture readiness and fail when Android reports capture is unavailable or silenced.

## Decisions

### Decision 1: Introduce internal route-state gates, not channel-visible route state

Add an internal route readiness/release gate concept owned by the audio input subsystem. The gate may live beside route abstractions (`AudioPorts` / `ResolvedAudioRoute`) or as strategy objects returned by route resolution, but it must not become part of `ChannelRouter` or channel controller APIs.

The gate contract should distinguish:

- requested transition;
- observed success facts;
- observed failure facts;
- timeout as fail-closed bound;
- route cleanup required after failure.

Alternative considered: pass Android route facts to channels so each controller can decide whether to proceed. Rejected because it breaks the established subsystem/channel boundary and recreates per-channel route ownership.

### Decision 2: Keep `PttAudioSessionManager` as the setup gatekeeper

`PttAudioSessionManager.runSetup()` should remain the point that resolves the route, waits for the route/capture gate, invokes `CaptureService`, and only then emits `ChannelInputEvent.Started` through `ChannelRouter.onInputStarted`.

This preserves the invariant that channels see only a usable channel input session or a terminal failure/cancellation event.

Alternative considered: perform all route gating in `PttDispatcher` before session creation. Rejected because car Telecom pending-route ownership is already modeled as an active audio input session, and gating outside the manager would split session ownership again.

### Decision 3: Make RSM immediate release observable and fail-closed

`ScoAudioController.releaseImmediately()` currently clears internal state, calls `AudioManager.clearCommunicationDevice()`, waits up to 1.5 seconds for `isTargetRsmHfpAudioConnected()` to become false, logs the result, and returns regardless.

For mode switches away from Work, the subsystem should treat RSM release as successful only when observed facts hold:

- target RSM HFP audio is disconnected (`BluetoothHeadset.isAudioConnected(rsm) == false` through the existing target helper);
- `AudioManager.communicationDevice` is not the selected RSM SCO device;
- the controller has cleared selected Work route ownership.

Timeout means release failure and the next route must not proceed as successful.

Alternative considered: reduce or increase the 1.5-second timeout. Rejected because the problem is using elapsed time as proof, not the specific duration.

### Decision 4: Make car HFP/Telecom readiness a composed gate

`TelecomCarPttLifecycle` currently treats acceptable Telecom `CallAudioState` as enough to enter `Recording`. That fact is necessary but not sufficient during RSM warm-to-car transitions.

The On-the-road readiness gate should compose available OS facts:

- Telecom reports an acceptable car call endpoint or call audio route;
- the active Bluetooth device is not the target RSM;
- car HFP audio has connected when a car HFP device is available;
- `AudioManager.communicationDevice` is not the stale RSM SCO endpoint;
- `AudioRecord` startup succeeds for `VOICE_COMMUNICATION`;
- active recording configuration does not report the client silenced when available.

The exact implementation may stage these facts: Telecom readiness can trigger capture setup, but channel input start should wait until capture readiness succeeds.

Alternative considered: keep Telecom `CallAudioState` as the sole readiness condition. Rejected because the observed bug shows Telecom can be ready while the app capture path is still stale or silent.

### Decision 5: Treat capture setup failure as route-owned cleanup

If capture setup fails after a route has been resolved or acquired, the audio input session owner must release the route associated with that session exactly once. This is especially important for Telecom, because `TelecomCallScoRoute.release()` is a no-op and the cleanup behavior lives in `TelecomCapturePcmOutput.releaseRoute()`.

Alternative considered: rely on `CaptureService` to call `sco.release()` on setup failure. Rejected because that is insufficient for Telecom route cleanup and duplicates route-release knowledge across low-level capture code.

### Decision 6: Use recorder facts as health evidence, not channel API

`AndroidCaptureSource` can remain the low-level `AudioRecord` opener, but the subsystem may need access to additional internal evidence: audio session id, active recording configuration, reported input device, and `isClientSilenced()` where available.

This evidence should stay internal. Channels continue to receive `ChannelAudioInputSession.frames` and `sampleRate` only.

Alternative considered: add route or device metadata to `ChannelAudioInputSession`. Rejected because it leaks route-state complexity into channel consumers.

## Risks / Trade-offs

- Android APIs can be incomplete or contradictory across vendors. → Mitigation: compose multiple facts, fail closed on disagreement, and keep manual log evidence for physical-device validation.
- Waiting for OS facts can make failed route switches visibly slower. → Mitigation: bound every wait with a timeout, but treat timeout as failure rather than success.
- Over-gating can reject valid captures on devices that do not expose complete `AudioRecordingConfiguration` data. → Mitigation: classify facts as required versus best-effort; only make universally available facts hard gates.
- Changing release ordering can regress Work/RSM warm reuse. → Mitigation: preserve warm retention for Work→Work and add tests proving reuse is unaffected.
- Moving Telecom setup cleanup into route-owned failure paths can double-release if not guarded. → Mitigation: keep exact-once route release in `PttAudioSessionManager` session identity state.
- Event callbacks can arrive stale after a newer session starts. → Mitigation: keep existing session identity checks and ensure route-gate callbacks are scoped to the active session.

## Migration Plan

1. Add tests and fakes for route-state gates before changing production flow.
2. Add internal route gate/result abstractions behind route resolution without changing channel APIs.
3. Convert Work/RSM immediate release for mode switches away from Work to return an observed success/failure result.
4. Use the Work release gate before starting Phone or On-the-road setup when a warm Work route exists.
5. Add an On-the-road readiness gate that composes Telecom, Bluetooth HFP, `AudioManager`, and capture-start evidence.
6. Ensure setup failure after route resolution/acquisition releases the session route exactly once, including Telecom.
7. Add recorder/capture health evidence where Android exposes it, while keeping channel input contracts unchanged.
8. Remove or downgrade continue-anyway timeout paths that became redundant.
9. Run targeted audio/session/SCO/Telecom tests, then perform physical-device RSM→car manual validation.

Rollback strategy: revert the gate abstractions and restore the previous route setup flow. No persisted data migration is involved.

## Open Questions

- Which recorder facts should be hard gates on Android 12+ versus diagnostics only?
- Should car HFP priming failure prevent Telecom call placement, or should Telecom placement proceed but capture start fail closed until call endpoint facts arrive?
- Should `ScoAudioController.releaseImmediately()` become suspending with a typed result, or should a separate Work route release gate wrap it?
- Should `AudioManager.OnCommunicationDeviceChangedListener` become a shared subsystem observer, or should each route gate poll current state with bounded waits first?
