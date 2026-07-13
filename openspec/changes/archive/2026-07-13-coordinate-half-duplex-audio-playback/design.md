## Context

Subspace already has a reliable host-owned input path: actuator policy selects an `InputMode`, `PttAudioSessionManager` owns one session and atomic terminal cleanup, `resolvePttAudioRoute` chooses Work/Telecom/local capture, `CaptureService` acquires and preflights the recorder and route, and channels receive only semantic audio. That path was established through extensive physical-device work and is not a refactoring target.

Output is fragmented. `PcmOutput` implementations know how to write PCM, synchronous terminal playback uses the route captured at input setup, `SystemAnnouncer` owns a separate mutex, and delayed agent playback reaches a composition lambda without the process-wide admission or playback-time mode routing promised by `DelayedPlaybackAudioPort`. A pending response can therefore race capture, rely on a merely warm RSM route, target Work regardless of current mode, or be pumped again immediately after the user tries to stop it.

The product model is one half-duplex conversational audio system. Channels remain device-agnostic. Input-session feedback belongs to its capture route; channel-produced content is queued independently and uses the mode selected when playback is admitted. Work means the target RSM, On-the-road means the car, and On-a-pinch means the phone. Failure is fail-closed, never cross-mode fallback.

## Goals / Non-Goals

**Goals:**

- Establish one process-wide admission authority for capture reservations, playback, announcements, and route transitions.
- Preserve durable, FIFO, device-unbound channel responses while capture or output is busy.
- Resolve and acquire a host-only playback route from the current mode after admission.
- Reject PTT during audible playback before actuator auto-transition; duck speech and overlay a debounced error tone on the active stream.
- Let RSM SOS explicitly skip active playback, pause that channel's later response drain, and leave agent conversation state intact.
- Resume a paused queue only through deliberate reselection of that same channel on the shared selection path used by phone, RSM, and car.
- Make lifecycle races exact: no overlapping capture/playback, no duplicate route release, no duplicate heard/skip commit, and no implicit replay after heard.
- Keep all device, route, focus, mixer, and admission objects out of provider/runtime/channel contracts.

**Non-Goals:**

- **The input subsystem internals are immutable for this change.** Do not redesign, rewrite, reorder, or replace `InputModeController`, admissible actuator auto-transition, `PttAudioSessionManager` session and terminal ownership, route gates, `CaptureService` recorder setup/preflight/frame delivery, ready-beep timing, or Work/Telecom/local capture strategies.
- Do not move playback concerns into `CaptureService` or make capture sessions understand durable response queues.
- Do not permit full-duplex capture and playback even when different physical endpoints might technically support it.
- Do not expose output-device selection in channel configuration or let a model/runtime choose a mode or device.
- Do not fall back from RSM to car/phone, car to phone/RSM, or phone to another ambient route.
- Do not persist generated TTS audio or playback offsets; authoritative text and queue state remain durable.
- Do not add a new playback/resume button. Same-channel reselection is the cross-surface resume control.
- Do not change SOS behavior when no playback operation is active or completing.

## Decisions

### D1: Add a host audio coordinator above, not inside, the input subsystem

A service-owned `HostAudioCoordinator` (name illustrative) is the single admission authority. It owns a small state machine and operation identities:

```text
IDLE
  ├─ accepted PTT intent ─► CAPTURE_RESERVED ─► CAPTURE_OWNED ─► RELEASING ─► IDLE
  └─ eligible response ───► PLAYBACK_RESERVED ─► PLAYING ──────► RELEASING ─► IDLE
```

Capture integration is boundary-only:

1. Every PTT entry point asks the coordinator before any actuator auto-transition.
2. If playback is audible, the coordinator consumes/rejects the press and does not enter the existing PTT path.
3. Otherwise it reserves capture and invokes the existing dispatcher/session path unchanged.
4. If existing dispatch does not create/reserve an input session, the adapter releases the reservation.
5. Once an input session is reserved, only its existing terminal-completion callback releases coordinator capture ownership and wakes output.

The adapter must not become another input terminal owner and must not call route release itself.

**Rationale:** A shared `Mutex` inside delayed playback would not exclude pending capture setup, and moving arbitration into `PttAudioSessionManager` would risk the mature input mechanics. An external coordinator gives atomic cross-direction admission while leaving capture authoritative.

**Alternatives considered:**

- Extend `PttAudioSessionManager` to own all output: rejected because it conflates durable output with transient input and violates the protected-input constraint.
- Check `audioSessionManager.isActive` before playback: rejected as a check-then-act race with no announcement or route-transition coverage.
- Per-endpoint locks: rejected because channels are globally half-duplex and Android communication mode/focus is process-wide.

### D2: Separate durable scheduling from physical audio ownership

`DelayedPlaybackCoordinator` continues to own durable message eligibility, selected-channel FIFO, synthesis artifact caching, pending/playing/heard transitions, and queue pause. It submits opaque synthesized audio to a host audio port.

The host audio port owns admission, current-mode snapshot, playback-route acquisition, active-stream control, focus, completion, and cleanup. The queue never stores a mode, endpoint, or route. Synthesis may run before admission and does not hold the audio lease.

```text
Durable PENDING text
  → synthesize outside audio lease
  → request host playback
  → acquire global playback lease
  → revalidate channel
  → snapshot current mode
  → acquire mode route
  → begin durable PLAYING
  → play
  → release route/lease
  → commit HEARD
```

If acquisition, contention, selection, or route readiness blocks playback, the message returns/remains `PENDING`. Recovery converts any uncommitted `PLAYING` state to `PENDING` as today.

**Rationale:** Message scheduling and audio ownership have different persistence and failure domains. Keeping them separate preserves retryable durable text without retaining Android objects.

### D3: Introduce a host-only playback route abstraction

Do not reuse `ResolvedAudioRoute` for delayed output: it requires an input `CaptureSource` and carries input-session release assumptions. Introduce a host-only playback strategy/result concept such as:

```text
PlaybackRouteStrategy.resolveAndAcquire() ->
  AcquiredPlaybackRoute(endpoint, activeOutput, release)
  | Busy
  | Unavailable
  | Failed
```

A resolver maps the mode snapshot to exactly one strategy:

```text
Work       → target-RSM HFP/SCO playback strategy
OnTheRoad  → validated car media playback strategy
OnAPinch   → validated phone playback strategy
```

Each acquired route owns exactly-once cleanup. Strategies fail closed. They do not choose another endpoint when their semantic endpoint is unavailable.

For Work, the strategy acquires a fresh SCO client lease even when the transport is warm. Warmth optimizes acquisition; it is not admission or ownership. For On-the-road, Telecom capture must already be completely released before car media playback is acquired. For On-a-pinch, the strategy must validate/target the phone policy rather than blindly accepting any ambient media route.

**Rationale:** `PcmOutput` abstracts PCM writing, not endpoint policy or acquisition. A playback-specific abstraction avoids fake capture sources and makes current-mode routing testable.

### D4: Use a controllable streaming playback operation

A one-shot `PcmOutput.play(recording)` cannot accept a PTT rejection tone or SOS stop after playback starts. An admitted playback creates a host-only `ActivePlayback` operation with:

```text
completion
rejectPttWithTone()
skip()
interrupt(reason)
close/cleanup
```

The output path uses one owned stream. `rejectPttWithTone()` temporarily ramps speech down, mixes the characteristic error tone into the same PCM stream with saturation, and ramps speech back up. It neither opens another `AudioTrack` nor acquires another route. A per-operation debounce guarantees at most one active/queued rejection tone and at most one tone per physical press.

PTT release corresponding to a rejected press is consumed without touching `PttAudioSessionManager`. All RSM, phone, and car PTT ingress paths use the same pre-dispatch arbitration.

**Rationale:** Overlay is immediate and preserves the response. A second track or route would undermine deterministic ownership; pausing/resuming would require a separate position contract the product did not choose.

### D5: Make SOS a contextual atomic host control

SOS ingress first asks the host coordinator to consume it as playback control. While playback is active or still owns terminal cleanup:

1. cancel any rejection-tone overlay;
2. stop the active output;
3. complete route cleanup;
4. commit the current message heard by explicit skip exactly once;
5. set that channel's queue-drain state to paused;
6. consume SOS without calling `runtimeRegistry.dispatchSos`.

If no playback operation is active or completing, existing SOS dispatch runs unchanged and the selected agent may reset its volatile conversation.

Natural completion and SOS race through the same operation identity. Exactly one terminal classification wins. A route failure returns the response to pending; SOS is the only path here that explicitly skips/hears it.

**Rationale:** Checking active playback in `PttForegroundService` and then issuing unrelated calls would race completion. Contextual control belongs with the active operation owner.

### D6: Persist per-channel playback-drain pause and clear it only on deliberate reselection

Store a per-channel queue-drain state alongside durable response playback state:

```text
ENABLED
PAUSED_BY_USER
```

SOS skip sets `PAUSED_BY_USER`. Later messages stay pending. New response arrival, audio-idle notification, route recovery, mode change, process restart, and passive catalogue projection do not clear it.

The shared channel-selection mutation must carry or derive a deliberate-selection event even when the selected ID equals the active ID. Phone, RSM, and car selection all use this same path. Deliberately reselecting that channel clears only its pause and wakes the scheduler; selecting another channel does not clear it.

**Rationale:** Reselection is the only resume control available consistently on all three control surfaces. Persisting pause prevents service restart from reversing an explicit stop command.

**Alternatives considered:**

- Resume on new response or audio availability: rejected because SOS would not reliably stop speech.
- Skip all pending messages: rejected because the user selected skip-current, not discard-queue.
- Add a new play button: rejected because it would not be available consistently across RSM, phone, and car.

### D7: Preserve input feedback; migrate channel content to playback-time routing

Ready beeps and input-session failure feedback remain owned by the existing input route because they communicate capture readiness/failure on the endpoint being recorded. Channel-generated content is distinct and goes through the host playback coordinator.

Delayed agent responses migrate first. Any synchronous runtime currently returning channel-content playback through `ChannelInputResult` must migrate at its semantic channel/capability boundary without changing `PttAudioSessionManager.runTerminal`; legacy input-terminal playback remains temporarily supported only until every affected built-in channel is migrated and covered by regression tests.

**Rationale:** Removing or reordering terminal playback inside the input manager would violate the protected-input constraint. Moving content production to the semantic output path preserves the manager while converging policy.

### D8: Scheduler wakeups are hints, never admission

Response arrival, synthesis completion, capture terminal completion, playback completion, channel selection, mode change, and endpoint readiness only request a pump. The pump atomically rechecks queue pause, selected channel, global admission, current mode, and route availability. Repeated wakeups coalesce and cannot overlap pump jobs.

**Rationale:** Existing `onAudioAvailable()`-style callbacks are useful but cannot prove ownership. Atomic lease acquisition remains authoritative.

## Risks / Trade-offs

- **[Risk] External capture reservation can leak if existing dispatch exits before creating a session.** → Use one boundary adapter that must receive a typed started/rejected result and release only its own uncommitted reservation; once the input session owns capture, terminal completion is the sole release signal.
- **[Risk] Implementors refactor input code while adding admission.** → Treat the protected input classes and effect ordering as acceptance constraints, reject changes that move route, recorder, beep, target, or terminal ownership, and run the complete existing input regression suite after the behavior works.
- **[Risk] Car or phone endpoint identity cannot be proven with current Android APIs on some devices.** → Fail closed and retain the response pending; add route diagnostics without device addresses or PCM. Never substitute another mode.
- **[Risk] Streaming/mixing increases playback complexity and allocation pressure.** → Use bounded reusable PCM buffers, saturating in-place mixing, one output stream, and no whole-response copies for each beep.
- **[Risk] PTT/SOS/completion races cause duplicate state transitions.** → Give each playback an operation identity and one atomic terminal owner; store heard remains monotonic and route cleanup idempotent.
- **[Risk] Persistent paused state makes pending messages appear stuck.** → Project paused playback distinctly on phone/car state while preserving pending count; reselection is documented and tested on every surface.
- **[Risk] Repeated PTT signals create audible spam.** → Debounce within the active playback operation and consume matching releases.
- **[Trade-off] Process-wide exclusion prevents playback on one endpoint while recording another.** → Accept intentionally: the product is half-duplex, and global exclusion avoids focus, route, and conversational conflicts.
- **[Trade-off] Interrupted playback restarts from the beginning after non-SOS failure.** → Accept because generated audio and offsets remain non-durable; authoritative text is re-synthesized/replayed later.

## Migration Plan

1. Add the host coordinator and fake capture/playback operations behind isolated composition without changing production input internals.
2. Add durable per-channel queue-pause state, explicit skip handling, and deliberate-reselection signaling through the shared selection mutation.
3. Add host-only playback route strategies for Work, On-the-road, and On-a-pinch with strict endpoint validation and no fallback.
4. Add controllable streaming playback and in-stream ducked rejection-tone mixing.
5. Wire all PTT ingress through the external pre-dispatch gate and capture terminal completion back to the coordinator. Keep the existing admitted PTT sequence byte-for-byte/behaviorally equivalent where possible.
6. Route delayed agent playback through the coordinator; remove direct concrete `PcmOutput` wiring from agent composition.
7. Add contextual SOS interception and ensure idle SOS still dispatches the existing runtime action.
8. Migrate remaining channel-generated content to semantic playback-time routing without rewriting input terminal mechanics; retain session-owned ready/error feedback.
9. Smoke-test all three modes and control paths, then add regression tests for the protected input behavior and the new coordination contracts.
10. Verify all three physical modes and all three control surfaces, including queue accumulation, rejected PTT feedback, SOS pause, same-channel reselection, endpoint failure, service restart, and foreground-service teardown.

Rollback removes coordinator composition and output migration while leaving the pre-existing input subsystem untouched. Durable pause data must be versioned so older builds safely ignore it without corrupting message history.

## Open Questions

- The product behavior is settled. Implementation must still prove which public Android APIs can strictly validate car media and phone output on the supported Android 12+ devices without hidden APIs; inability to prove the selected endpoint is an unavailable route, not permission to fall back.
