## Context

Subspace currently supports momentary PTT capture from the B02PTT-FF01 RSM and phone long-press channel cards. The Android Auto media-session experiment proved that steering-wheel/media controls can start an app action, but it also exposed a blocking ergonomic failure: once microphone capture causes the car/head unit to enter a call-like HFP mode, media transport events are no longer a reliable stop path.

The new architecture intentionally accepts two different Android audio/control domains. A media action or car-app action starts the interaction, then Android Telecom owns the active capture interval so the car's physical end-call control becomes the deterministic stop event. Response playback returns to normal media/A2DP after capture has ended.

## Goals / Non-Goals

**Goals:**

- Provide a driver-safe in-car PTT capture path with a deterministic physical stop control.
- Use only public Android APIs on Android 12+.
- Register a self-managed Telecom `PhoneAccount` and implement a `ConnectionService` for capture sessions.
- Start recording only after Telecom reports a usable call audio route, preferably Bluetooth/HFP.
- Reuse active-channel readiness checks, routing, ready/error beeps, capture finalization, and source ownership semantics.
- Fail safe by stopping capture on disconnect, abort, reject, call-audio loss, service shutdown, or conflicting call state.
- Play generated/processed responses through normal media playback instead of HFP call audio.

**Non-Goals:**

- Do not rely on media play/pause as both start and stop for capture.
- Do not use hidden Android Auto or Telecom APIs.
- Do not intercept the steering-wheel assistant/talk button directly.
- Do not implement a full conversation UI, transcript surface, or inbound message backlog in this change.
- Do not make Google Play policy compliance a blocking requirement for this experiment.
- Do not route response playback through the active HFP call unless a later change explicitly requires low-fidelity call audio.

## Decisions

### Use Self-Managed Telecom For The Capture Interval

Subspace will register a self-managed `PhoneAccount` and place an app-owned VoIP call when the driver starts car capture. The resulting `Connection` represents one PTT capture interval.

Alternatives considered:
- Continue using media-session transport controls. Rejected because media controls become unreliable after the car enters HFP/call mode.
- Use a custom Android Auto screen button for both start and stop. Rejected as the primary stop path because it requires display interaction while driving.
- Use a Bluetooth HID or external button. Viable as a separate hardware path, but it does not solve steering-wheel/head-unit ergonomics with existing car controls.

### Treat End-Call As The Authoritative Stop Event

The implementation will stop capture from `Connection.onDisconnect()` and equivalent abort/reject/destroy paths. This matches car firmware behavior: once an HFP call is active, the physical hang-up button is routed to Android Telecom rather than to media transport.

Alternatives considered:
- Try to preserve media-session priority during capture. Rejected because the observed head unit suppresses or remaps media controls once call audio is active.
- Use timeout-only stop semantics. Rejected because a max duration is a fail-safe, not a usable PTT interaction.

### Wait For Telecom Audio Route Before Recording

The `Connection` will not start recording immediately after call placement. Capture starts only after call audio state indicates the expected route is active, with Bluetooth/HFP preferred and a bounded timeout/failure path if no acceptable route appears.

Alternatives considered:
- Start recording immediately. Rejected because initial syllables can be lost while the head unit is still opening call audio.
- Force app-level SCO outside Telecom. Rejected for this path because Telecom is the entity that owns car call state and stop routing.

### Reuse Existing PTT Dispatch With A New Source

Telecom-originated capture will enter the existing dispatch path as a car/Telecom PTT source. The active channel remains the target, and readiness failure uses the existing error feedback path without opening a Telecom recording session.

Alternatives considered:
- Call capture controllers directly from the `ConnectionService`. Rejected because it bypasses channel readiness, source ownership, and common fail-safe behavior.

### Play Responses As Media After Call Teardown

After capture finalizes and the Telecom connection is disconnected, Subspace will request media audio focus and play any response through normal media output. This avoids low-fidelity HFP playback and allows the car's normal media session to resume afterward.

Alternatives considered:
- Play responses inside the call. Rejected because HFP audio is low-bandwidth and keeps the car in call mode longer than necessary.

## Risks / Trade-offs

- Self-managed Telecom behavior varies across OEMs and Android Auto versions -> Validate on the target car and add conservative disconnect/timeout handling.
- Starting capture requires a visible call-mode transition -> Accept as a trade-off for deterministic hardware hang-up handling.
- Self-managed calls may interact with real cellular calls -> Abort or reject Subspace capture when Telecom reports conflicting call state or audio focus loss.
- PhoneAccount registration may require first-run user enablement on some devices -> Surface a readiness state and setup action in-app.
- HFP capture quality may be lower than phone microphone or headset SCO paths -> Accept for driver safety; media/A2DP remains used for response playback.
- Car App Library dependency may be needed for a reliable launcher/start surface -> Keep it minimal and limited to start/status if added.

## Migration Plan

1. Disable the previous Android Auto media-session latched PTT stop path so it cannot leave capture stuck.
2. Add Telecom permission, `ConnectionService`, and `PhoneAccount` registration.
3. Add a car-capture source and coordinator that bridges Telecom connection lifecycle to existing PTT dispatch.
4. Add a minimal start entry point through an existing media action or Car App Library surface.
5. Verify capture start, hang-up stop, disconnect fail-safe, and media response playback on the target car.

Rollback is removing the Telecom service/phone-account registration and restoring the prior non-car PTT paths; persisted channel state remains compatible.

## Open Questions

- Does the target phone/car require the user to manually enable the self-managed `PhoneAccount` before calls can be placed?
- Is a Car App Library dependency necessary for discoverability/start, or can the existing app plus a media action provide a sufficient start path?
- Which response playback path should be wired first: existing TTS/debug playback only, or a generic post-capture response queue?
