## Context

Subspace currently treats one complete PTT capture as a press/release interval routed through `PttForegroundService`. RSM hardware produces momentary press/release events, phone channel cards produce long-press press/release events, and channel routing uses the active channel plus readiness checks to decide whether to dispatch capture or emit an error beep.

Driving changes the input constraint. The handheld `B02PTT-FF01` is not a safe actuator while driving, and Android Auto does not expose the steering-wheel assistant/talk button as raw app input. Many cars do expose steering-wheel play/pause through Android media transport controls when an app owns an active media session. This change intentionally uses that transport as an input path for a latched virtual PTT button.

## Goals / Non-Goals

**Goals:**
- Represent car media play/pause as a virtual PTT input source that emits synthetic press/release events.
- Preserve the existing PTT session invariant: one message equals one press/release interval.
- Reuse active-channel routing, readiness checks, audio route resolution, error beeps, and channel controllers.
- Provide fail-safe release behavior whenever car/session/audio state becomes ambiguous.
- Provide glanceable and audible recording feedback suitable for driving.

**Non-Goals:**
- Do not use the Android Auto assistant/talk button; it remains system-owned.
- Do not build a custom Android Auto Compose UI.
- Do not implement Android Auto channel configuration.
- Do not implement inbound message playback, backlog, replay, or media-library browsing beyond what is necessary to receive media transport controls.
- Do not make Google Play review compliance a blocking requirement for this change.

## Decisions

### Treat Media Transport As A Latched PTT Input

The media session callback will not directly start recording. It will feed a `CarPttAdapter`/virtual input that tracks released/pressed state and emits synthetic PTT press/release events with a car-specific `PttSource`.

Alternatives considered:
- Directly call recording controllers from media callbacks. Rejected because it bypasses channel readiness, source ownership, error beeps, and existing dispatch behavior.
- Treat play as start and pause as stop only. Rejected because cars vary in whether they send play, pause, play/pause, or key events; toggle handling is more robust.

### Add A Car-Originated PTT Source

`PttSource` should gain a car/media-originated value so active sessions can only be released by the source that opened them and so tests can distinguish RSM, phone, and car behavior.

Alternatives considered:
- Reuse `Phone`. Rejected because phone long-press is momentary while car media is latched and has different fail-safe rules.
- Model source as strings. Rejected because the source set is small and compile-time exhaustiveness is useful in Kotlin.

### Fail Safe Toward Released

The virtual PTT state will force-release on Android Auto/media-session disconnect, media-session deactivation, audio-focus/session loss where detectable, max duration, capture error, service shutdown, or any ambiguous state transition while pressed.

Alternatives considered:
- Preserve recording until the next button event. Rejected because missed media events or car disconnects could leave the microphone open.

### Keep The First Android Auto Surface Minimal

The first version should expose enough media-session metadata/playback state for Android Auto to show Subspace as controllable and reflect `Ready`/`Recording`/`Not ready`. Any richer template UI can be added later after the control path is proven.

Alternatives considered:
- Build a full car app template surface immediately. Deferred because the critical requirement is physical play/pause actuation, not visual navigation.

## Risks / Trade-offs

- Media controls are semantically playback controls, not recording controls -> The app will explicitly model this as a sideloaded/F-Droid/GitHub-only car mode and keep internal names as virtual PTT, not playback.
- Android Auto may only deliver wheel media controls to the active media session -> The implementation must keep Subspace's media session active when car PTT mode is enabled and expose stable metadata/playback state.
- Different cars emit different media events -> The adapter must handle play, pause, stop, and play/pause conservatively, with any stop-like event releasing an active virtual press.
- A missed stop event could leave capture running -> Max duration, session-loss release, service-shutdown release, and error release are mandatory.
- Future inbound playback may conflict with capture-toggle semantics -> This change reserves car play/pause for capture-first behavior while car PTT mode is active; playback controls can be revisited with a separate change.

## Migration Plan

This change adds a new optional input path without changing existing RSM or phone PTT behavior. Rollback is removing the media-session service/manifest entries and the car virtual PTT adapter; persisted channel state remains compatible.

## Open Questions

- Which exact Android media API should be used during implementation: Media3 `MediaSessionService`, framework `MediaSession`, or compat APIs? Choose based on dependency footprint and Android Auto interoperability in this repository.
- Should car PTT mode be always available when the media session is active, or gated behind an explicit in-app setting? The safe default is to require an explicit setting if implementation cost is low.
