## Context

Subspace currently has one low-level `CaptureService` that owns the active `AudioRecord`, but the higher-level PTT lifecycle is split across `PttDispatcher`, `PttForegroundService`, channel controllers, `ScoAudioController`, `TelecomCapturePcmOutput`, and direct `AudioManager` mutations. Normal PTT release carries the per-press `ResolvedAudioRoute` to the channel, but forced cancellation, mode switch, service teardown, and source-loss paths cancel by channel ID and often lose the active route.

The intended architecture is a subsystem that owns audio input lifecycle and input-mode complexity. It should hand the selected channel a simple stream/result contract rather than making every channel understand SCO, local fallback, Telecom route switching, ready-beep sequencing, or route release semantics.

Current useful building blocks remain valid:

- `CaptureService` already enforces one active `AudioRecord` and exposes live frames plus terminal PCM.
- `ResolvedAudioRoute` already packages the selected route, output, capture source, and endpoint.
- `ScopedPcmOutput.releaseRoute()` already encodes Work warm retention, On-the-road immediate route switch, and local no-op semantics.
- `InputModeController` already maps actuator home modes and availability.
- `TelecomCarPttLifecycle` already models car call route readiness.

The change should preserve those pieces while moving ownership to one higher-level session manager.

## Goals / Non-Goals

**Goals:**

- Introduce one active audio input session owner for PTT press, route acquisition, capture, release, and cancellation.
- Hide `ResolvedAudioRoute`, `ScoRoute`, `PcmOutput`, `CaptureSource`, and `InputMode` from channel controllers.
- Make forced cancellation and teardown release the same route that was acquired for the active session.
- Treat route acquisition, ready beep, active capture, post-capture processing, pending Telecom route, and release as one session lifecycle for app-wide gating.
- Preserve existing behavior for Work/RSM, On-a-pinch/phone, and On-the-road/Telecom routes.
- Keep the first implementation minimal by reusing existing `CaptureService`, route resolver, route outputs, and controller business logic.

**Non-Goals:**

- No rewrite of STT, TTS, STT↔TTS, journal processing, keyboard HID output, or model loading.
- No change to Bluetooth pairing, SPP parsing, RSM button protocol, or Android Auto media browsing UX.
- No hidden Android APIs or reflection.
- No new dependency or persistent data migration.
- No UI redesign beyond any status wiring needed to preserve existing monitor output.
- No change to release signing, Gradle, Nix, or Android SDK configuration.

## Decisions

### Decision 1: Add a higher-level `PttAudioSessionManager`

Create an audio input subsystem class that owns the active PTT audio lifecycle above `CaptureService`.

Responsibilities:

- hold the single active audio input session identity;
- resolve the input-mode route for the press;
- acquire route and invoke `CaptureService.startSession()`;
- retain the active `ResolvedAudioRoute` internally;
- expose live frames and terminal PCM to the selected channel;
- release the route exactly once on normal release, forced cancel, failure, or teardown;
- ignore stale release/cancel events that do not match the active source/session.

Alternative considered: pass `ResolvedAudioRoute` into `ChannelRouter.cancelAndRelease(channelId, route)`. That would fix the most visible leak but preserve route ownership in channels and keep the architecture split. It is acceptable only as a temporary emergency patch, not as this change's target.

### Decision 2: Keep `CaptureService` as the low-level recorder

`CaptureService` remains responsible for exactly one active `AudioRecord`, ready-beep-before-record sequencing, live frames, terminal PCM, and low-level setup failure cleanup. The new manager becomes its caller.

Alternative considered: fold `CaptureService` into the new manager. Rejected because `CaptureService` already has focused tests and should stay as the low-level capture primitive.

### Decision 3: Use existing route resolver as the initial strategy implementation

The first implementation keeps `resolvePttAudioRoute(mode)` and route classes intact. The new subsystem treats it as the strategy selector for:

- Work/RSM strategy: target RSM-owned SCO route;
- On-a-pinch strategy: local mic/output route;
- On-the-road strategy: Telecom capture route and mandatory route switch.

A later cleanup may split those into explicit `AudioInputStrategy` classes after the boundary is stable.

Alternative considered: introduce a full strategy hierarchy first. Rejected because it would add names without fixing the current ownership bug if channels still own release.

### Decision 4: Replace route-facing channel callbacks with channel input callbacks

Channels should receive channel-level input data, not route internals.

Initial channel contract should be narrow:

```kotlin
sealed interface ChannelInputEvent {
    data class Started(val session: ChannelAudioInputSession) : ChannelInputEvent
    data class Released(val recording: RecordedPcm) : ChannelInputEvent
    data class Cancelled(val reason: String) : ChannelInputEvent
    data class Failed(val reason: String) : ChannelInputEvent
}

interface ChannelAudioInputSession {
    val frames: Flow<ShortArray>
    val sampleRate: Int
}
```

The exact Kotlin names may differ, but the boundary must hold: no channel receives `ResolvedAudioRoute` or calls `releaseRoute()`.

Alternative considered: leave controller method signatures unchanged and store active routes in each controller. Rejected because it duplicates session ownership across all channels.

### Decision 5: Make forced release session-scoped

`PttDispatcher.forceReleaseActivePtt()`, source loss, mode switch, service destroy, and controller deactivation must cancel the active audio input session, not a channel ID.

The new manager should have explicit operations like:

```kotlin
fun cancelActive(reason: String)
fun release(source: PttSource)
```

Both operations must use the internally retained active route and must be exact-once.

Alternative considered: keep `ChannelRouter.cancelAndRelease(channelId)`. Rejected because channel ID is not enough information to release the Android audio route correctly.

### Decision 6: Treat car Telecom waiting-for-route as active audio input ownership

When car PTT starts and Subspace is waiting for an acceptable Telecom Bluetooth route, the app should consider the audio input subsystem busy for PTT purposes. This prevents phone/RSM from starting a competing session while Telecom route acquisition is pending.

This does not require starting `CaptureService` before Telecom route readiness. It only reserves the session owner until the car route either starts capture, fails, times out, or is cancelled.

Alternative considered: keep the current gap until `onTelecomCaptureStart()`. Rejected because it allows another PTT source to start while Telecom owns the car setup lifecycle.

### Decision 7: Route release must be ordered from the manager perspective

The manager's release operation must not report session cleanup complete until the route release operation has been requested and any route-specific suspend gates have run. If `ScoAudioController.release()` remains fire-and-forget internally, the manager should still sequence its own state so no stale session can release a newer route.

A later implementation may make Work SCO release suspending or add an explicit release-completion hook. This change only requires exact ownership and stale-release protection.

## Risks / Trade-offs

- **Risk:** This touches many controllers. → **Mitigation:** Change the boundary first, keep channel business logic intact, and migrate one controller pattern at a time under tests.
- **Risk:** Duplicating `CaptureService` ownership accidentally creates two active-session gates. → **Mitigation:** The new manager owns PTT lifecycle; `CaptureService` remains the low-level defensive gate. Tests cover both.
- **Risk:** Telecom pending-route reservation may change behavior when the user presses phone/RSM while car setup is pending. → **Mitigation:** Specify fail-closed busy behavior and surface error/ignore consistently; this is intentional to prevent route races.
- **Risk:** Exact-once route release can regress warm SCO retention or car route switch. → **Mitigation:** Keep existing `ScopedPcmOutput` semantics and add exact release-count tests for Work, On-a-pinch, and On-the-road.
- **Risk:** Channel input event naming can over-abstract before usage is clear. → **Mitigation:** Use the smallest interface needed by current channels: live frames, sample rate, terminal PCM, cancelled/failed.
- **Risk:** Service lifecycle callbacks can still race through global buses. → **Mitigation:** Centralizing active audio input state reduces damage; global bus generation tokens can be a later change if tests still expose races.

## Migration Plan

1. Add tests for the desired boundary before moving code: active audio session owns route, forced cancel releases the active route, wrong-source release is ignored, and channel callbacks do not receive route internals.
2. Introduce the new audio session manager using existing route resolver and `CaptureService`.
3. Route one simple channel through the new channel input contract, then migrate STT/Echo/Journal/Keyboard patterns.
4. Replace `ChannelRouter` route-facing methods with input-event methods.
5. Move forced release, service teardown, mode switch, and source-loss cleanup to the session manager.
6. Move car pending-route reservation into the session manager while keeping Telecom route readiness logic in existing Telecom classes.
7. Remove obsolete route/capture fields from channel controllers after all call sites use the subsystem.

Rollback strategy: the change is internal to app code. Revert the session manager and channel interface migration if tests show behavior regression; no persisted state migration is involved.

## Open Questions

- Should TTS-only playback remain outside the audio input subsystem initially, or should it receive a no-capture session path for uniform route output ownership?
- Should Work SCO `release()` become suspending in this change, or should the manager only enforce exact session ownership while retaining current warm-release scheduling?
- What user-visible feedback should occur when a second PTT source is pressed while a car Telecom route is pending?
