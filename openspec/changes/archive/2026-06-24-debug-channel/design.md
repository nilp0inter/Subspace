## Context

The app currently uses an `AudioRouter` to arbitrate between the only real channel (`CaptainsLogChannel`) and legacy diagnostic test modes (Echo, STT, TTS, STT↔TTS). These test toggles reside on the `MonitorScreen`, conflating hardware diagnostics (button states, link status) with audio routing behavior. The goal is to elevate these audio diagnostics into a first-class `DebugChannel`, removing transitional routing code and allowing the `PttForegroundService` to handle uniform channel dispatch.

## Goals / Non-Goals

**Goals:**
- Unify audio routing under the `Channel` framework by creating `DebugChannel`.
- Delete `AudioRouter` and implement native mutual-exclusion (channel switching) directly in `PttForegroundService` and `AppState`.
- Clean up `MonitorScreen` to only visualize hardware status.
- Provide a dedicated UI for configuring the `DebugChannel`.

**Non-Goals:**
- Implementing hardware-based channel switching (using Volume Up/Down on the physical device). This will come later; for now, switching is UI-driven only.
- Making the list of channels fully dynamic. The system can continue tracking a fixed set of channels (Captain's Log, Debug Channel) in its data layer and `AppState`.

## Decisions

### D1: DebugChannel Data Model
**Decision**: Create a `DebugChannel` data class implementing the `Channel` interface. It will contain a `mode` property of type `DebugMode` (Enum: `ECHO`, `STT`, `TTS`, `STT_TTS`). There is no "off" state within the mode; the channel itself is simply enabled or disabled like any other channel.
**Rationale**: Aligns diagnostic modes with standard channel behavior. Ensures that when the channel is active, it strictly enforces exactly one diagnostic mode.

### D2: Service-Level Channel Switching
**Decision**: Remove `AudioRouter`. The `PttForegroundService` will enforce channel switching. When `setCaptainsLogEnabled(true)` is called, the service explicitly sets `debugChannel.enabled = false`. When `setDebugChannelEnabled(true)` is called, the service sets `captainsLog.enabled = false`. PTT events will be routed directly to the active channel's controller.
**Rationale**: This accurately models real-world radio behavior where only one channel is active at a time. It simplifies the routing logic and removes the artificial distinction between "channels" and "test routes".

### D3: UI Structure
**Decision**: 
- Remove all test toggles from `MonitorScreen`.
- Replace the "Diagnostics" mock channel on the `MainDashboardScreen` with a real `DebugChannel` card. This card will display the current active/inactive status and the selected `DebugMode`.
- Introduce a new `DebugChannelConfigScreen` (accessible via the `MainDashboardScreen` card) to change the `DebugMode` using mutually exclusive radio buttons or segmented controls.
**Rationale**: Keeps the main dashboard glanceable while moving configuration into dedicated screens. Returns the `MonitorScreen` to its original purpose as a pure hardware debug view.

## Risks / Trade-offs

- **Risk**: Deleting `AudioRouter` might break PTT routing if the service state transitions don't perfectly maintain a single active channel.
  - **Mitigation**: Rely on the centralized `AppState` in the service to act as the single source of truth. By having the service orchestrate channel enable/disable toggles atomically, we guarantee mutual exclusion.