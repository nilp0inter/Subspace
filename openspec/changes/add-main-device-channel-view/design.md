## Context

The current app is a Kotlin/Compose Android proof of concept with two root-level screens:

- `ConnectionScreen` validates permissions, Bluetooth state, target discovery/pairing, SPP connection, and SCO availability.
- `MonitorScreen` displays live button state and runs the in-memory echo test.

`MainActivity` currently selects between these screens automatically by checking `AppState.readyForMonitor`. Root documentation now says the proof of concept works on the target `B02PTT-FF01` device and the next useful work is evolving the app toward its final operator-facing shape. The visual identity document defines the phone as a glanceable field-operations status monitor and introduces a channel metaphor.

This change keeps the validated hardware proof-of-concept views intact, but stops treating them as the primary app surface.

## Goals / Non-Goals

**Goals:**

- Introduce a main dashboard as the default app view.
- Show the device connection state at a glance.
- Allow the user to click the connection indicator to reach the appropriate legacy validation view.
- Show a mock channel list that matches the final-app direction without requiring channel functionality yet.
- Preserve existing service binding, connection actions, monitor actions, echo behavior, and readiness semantics.

**Non-Goals:**

- No STT, TTS, command execution, cloud service, or agent integration.
- No real channel transport, channel routing, channel persistence, or active channel switching behavior.
- No Bluetooth protocol, SPP parser, SCO audio, echo-controller, or foreground-service behavior changes.
- No support for device models beyond `B02PTT-FF01`.
- No hidden Android Bluetooth APIs, A2DP path, or phone mic/speaker fallback.
- No redesign of the legacy connection and monitor screen internals beyond navigation affordances needed to return to the dashboard.

## Decisions

### MainActivity owns simple screen routing

Use a small in-activity route state for `Dashboard`, `Connection`, and `Monitor` rather than adding Navigation Compose.

Rationale: the app has only three local screens, no deep links, no argument passing, and no back stack complexity. A sealed UI route or enum keeps the change minimal and avoids a new dependency/pattern.

Alternative considered: Navigation Compose. It would be useful once the app has nested flows or deep links, but it adds unnecessary structure for this transition.

### Dashboard uses existing readiness as the connection definition

Treat `AppState.readyForMonitor` as the dashboard's connected/ready signal.

Rationale: the existing readiness gate already encodes the proof-of-concept definition of usable device connection: permissions, Bluetooth enabled, target bonded, SPP connected, and SCO communication-device availability. Reusing it prevents a second definition from drifting.

Alternative considered: show SPP-only connected state. That would make the dashboard look connected even when headset audio is unavailable, conflicting with existing monitor readiness and the no-fallback audio constraint.

### Legacy views remain operational tools

The dashboard connection indicator routes to `ConnectionScreen` when not ready and `MonitorScreen` when ready.

Rationale: this preserves all validation controls while making them secondary screens. It also matches the requested behavior exactly: disconnected opens the first legacy view, connected opens the second legacy view.

Alternative considered: embed connection/monitor panels directly in the dashboard. That would make the first step larger and dilute the main view's glanceable channel-focused purpose.

### Mock channels are static UI data

Represent channels as static Compose/UI data for now.

Rationale: channels are explicitly mock-ups at this stage. Static data avoids premature domain models, persistence, repositories, or service coupling.

Alternative considered: add channel types to `AppState`. That would be appropriate once channels drive behavior, but it is unnecessary for non-functional mock display.

### Back behavior returns to dashboard from legacy views

When the user opens a legacy view from the dashboard, pressing Android back should return to the dashboard before exiting the app.

Rationale: the dashboard is the new home surface. Legacy views are drill-down tools.

Alternative considered: let back exit the activity from legacy views. That would make the new dashboard less useful as the root surface.

## Risks / Trade-offs

- Two connection concepts could confuse users if labels are vague -> Use copy that distinguishes `Connected`/`Not connected` based on full readiness, and expose detailed state only in the legacy screens.
- Static mock channels could be mistaken for working communication routes -> Label or style them as mock/preview/unavailable until real behavior exists.
- Keeping route state only in `MainActivity` means route resets on process recreation -> Acceptable for this lightweight UI transition; the dashboard is the intended default root.
- Legacy monitor access remains gated by SCO availability -> This intentionally preserves current proof-of-concept readiness semantics and avoids false success through unsupported audio paths.

## Migration Plan

1. Add the dashboard UI and route state without changing service ownership.
2. Make the dashboard the default screen.
3. Wire the dashboard connection indicator to legacy connection/monitor screens based on `readyForMonitor`.
4. Preserve legacy screen actions and add a minimal return path to the dashboard if needed.
5. Run existing unit tests and assemble the debug APK.

Rollback is straightforward: restore `MainActivity` to the previous `readyForMonitor` conditional between `ConnectionScreen` and `MonitorScreen`, and remove the dashboard screen.

## Open Questions

- Exact mock channel names are not functionally significant for this change; implementation can choose placeholder names that align with the visual identity's channel metaphor.
