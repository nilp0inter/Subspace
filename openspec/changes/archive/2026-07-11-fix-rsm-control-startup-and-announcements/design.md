## Context

The existing RSM and bootstrap subsystems already provide the required primitives: catalogue-order offset selection, a serialized memoized `SystemAnnouncer`, SCO route ownership, a prerequisite-aware `ReconnectPolicy`, and persistent announcement render settings. The defects came from orchestration gaps rather than missing infrastructure:

- RSM Volume Up/Down were mapped to catalogue offsets opposite to the physical/visual direction.
- Saturating selection silently retained the boundary channel without feedback.
- `ReconnectPolicy.startMonitoring()` was called only by the manual Connect serial action, so a fresh service could observe a bonded RSM but was not authorized to start SPP.
- Announcement synthesis requests and persistent cache settings used a higher step count instead of the required four-step rendering contract.

The changes must preserve exact-once SCO release, explicit-disconnect semantics, delayed retry serialization, pairing/setup fallback, and canonical persistent-cache invalidation.

## Goals / Non-Goals

**Goals:**

- Make RSM Volume Up move toward the preceding/top channel and Volume Down move toward the following/bottom channel.
- Preserve non-wrapping traversal and produce an error beep when a boundary prevents movement.
- Automatically initiate the first eligible SPP connection during service startup without requiring a historical manual connection.
- Use one connection scheduler for startup, manual requests, and reconnect eligibility while preserving serialized attempts.
- Render and fingerprint system announcements with exactly four synthesis steps.
- Keep automated contracts for direction, SCO error-beep ownership, initial monitoring, and TTS parameter propagation.

**Non-Goals:**

- Changing ButtonParser token identity or remapping the RSM firmware protocol.
- Changing Android Auto next/previous commands or catalogue ordering semantics.
- Wrapping channel traversal from the first channel to the last or vice versa.
- Automatically pairing an unbonded RSM, opening Bluetooth settings, or replacing manual setup controls.
- Reconnecting after an explicit disconnect within the same service lifetime.
- Changing PTT capture route policy, channel runtime behavior, Debug TTS/STT-TTS settings, announcement voice style, language, speed, or output sample rate.

## Decisions

### D1: Represent RSM direction as a pure event-to-offset mapping

Map `VolumeUpClicked` to `-1`, `VolumeDownClicked` to `+1`, and non-volume events to no offset. Keep ButtonParser mappings unchanged and feed the resulting offset into the existing saturating catalogue selector.

This makes the physical direction contract independently testable. Swapping parser tokens was rejected because those tokens correctly represent the hardware buttons and are also consumed by button-state diagnostics.

### D2: Detect a traversal boundary from the committed selection result

Record the active instance ID, apply the saturating offset mutation, and compare the resulting committed active ID. A changed ID triggers the existing channel-name announcement. An unchanged ID triggers the error beep.

Index prechecks were rejected because they would duplicate catalogue-selection semantics and could diverge when order or active-selection repair changes.

### D3: Serialize boundary beeps with announcements

Extend `SystemAnnouncer` with an error-beep playback operation that uses the same playback mutex and active job as spoken announcements. The operation cancels and joins prior playback, acquires SCO, plays one existing error beep, and releases SCO exactly once.

Direct playback from the button handler was rejected because it could overlap a channel-name announcement and create competing SCO owners.

### D4: Establish startup monitoring intent before readiness evaluation

A new device-link service instance calls `ReconnectPolicy.startMonitoring()` before its initial readiness refresh. After each readiness refresh, a scheduler evaluates whether monitoring is requested, no serial/reconnect attempt is active, and permissions, Bluetooth, and bonded-target prerequisites are all satisfied. Eligible state schedules an immediate attempt; ineligible state remains idle without discovery or pairing.

The manual Connect serial action establishes the same monitoring intent and delegates to this scheduler. Explicit Disconnect clears monitoring before readiness is refreshed, preventing immediate reattachment. Unexpected serial loss continues through the existing delayed retry path.

Persisting monitoring intent across process death was rejected: startup behavior is unconditional for a new service instance, while explicit disconnect must suppress reconnect only for the current lifetime.

### D5: Use four steps as one render-and-cache constant

Define one announcement-only step constant with value `4` and use it in every `SynthesisRequest` and every `AnnouncementRenderSettings`, including empty-vocabulary commits. Because synthesis steps are fingerprint input, changing the value invalidates prior higher-step entries and causes one controlled regeneration.

Changing user-configurable Debug TTS defaults was rejected because those are separate interactive diagnostics and not part of system-announcement precomputation.

## Risks / Trade-offs

- **[Risk] Startup SPP could race a manual request.** The shared scheduler checks active serial work, scheduled reconnect work, and policy attempt ownership; manual requests use the same path.
- **[Risk] Readiness refresh could repeatedly schedule blocked attempts.** Scheduling occurs only after all prerequisites pass and while no attempt/job is active.
- **[Risk] Boundary feedback could overlap spoken navigation.** Error beeps and announcements share one serialized playback job.
- **[Risk] SCO acquisition failure produces no audible boundary feedback.** The operation fails closed on the intended headset route rather than leaking feedback to the phone speaker.
- **[Trade-off] Every new service lifetime restores startup monitoring even after a prior explicit disconnect.** This matches automatic operation on app start; explicit disconnect remains authoritative only until that service lifetime ends.
- **[Trade-off] Four-step settings invalidate the persistent announcement cache once.** Regeneration cost is intentional and bounded; retaining higher-step PCM would violate the new canonical rendering contract.

## Migration Plan

1. Deploy the event-to-offset mapping and serialized boundary beep together so direction and feedback change atomically.
2. Start monitoring before initial readiness and route manual Connect serial through the same scheduler; retain existing delayed reconnect state transitions.
3. Change the shared announcement step constant in both synthesis requests and cache settings. On the next bootstrap, prior fingerprints miss and the cache reconciler sweeps superseded PCM after committing four-step entries.
4. Preserve existing catalogue, pairing, channel configuration, and manual setup data; no persisted schema migration is required.
