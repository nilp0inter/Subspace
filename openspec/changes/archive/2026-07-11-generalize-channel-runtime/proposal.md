## Why

Channels are currently represented as three compile-time singleton types whose identity, order, persistence, UI, and execution routing are duplicated across the application. Generalizing them now into persisted instances and a host-owned runtime registry creates the stable boundary required for user-managed channel lists and future Lua-backed channels without destabilizing the existing audio-route and PTT session machinery.

## What Changes

- Replace the fixed Journal, Debug, and Keyboard state slots with one authoritative, persisted, ordered catalogue of channel definitions.
- Give every channel definition a stable opaque instance ID, display name, built-in kind, enabled state, schema-versioned typed configuration, and persisted list position.
- Seed the catalogue from existing per-channel preferences exactly once, preserving existing built-in configuration and active selection.
- Add atomic catalogue operations for creating supported built-in instances, updating definitions, removing instances, and reordering instances.
- Repair active selection deterministically when the active channel is removed by selecting the next remaining channel, or the previous channel when no next channel exists.
- Derive the phone dashboard, Android Auto browse tree, and hardware next/previous traversal from the same ordered catalogue snapshot.
- Replace fixed channel-ID and debug-mode routing branches with an ID-keyed runtime registry and factories for all existing built-in channel kinds.
- Move built-in execution onto one host-controlled input contract so the PTT audio session manager remains the sole owner of route acquisition, capture, playback ordering, and route release.
- Reconcile runtime creation, definition updates, removal, and service shutdown without interrupting a target already committed to an active PTT session.
- Remove obsolete fixed-channel state, type-based ordering, typed controller registry slots, and duplicate controller-owned PTT capture paths after all callers migrate.
- Add generic per-instance readiness and execution status projections while retaining type-specific configuration editors for the existing built-ins.
- Enforce catalogue order and stable instance identity through runtime projection, configuration navigation, per-instance mutation, and repeated creation of every supported built-in kind.
- Exclude Lua execution, scripting APIs, generic inbound/outbound message history, pending-message backlog, network-defined channels, and a universal configuration renderer from this change.

## Capabilities

### New Capabilities

- `channel-catalogue`: Defines persisted channel instances, stable identity, ordered catalogue mutations, legacy migration, active-selection validity, and shared cross-surface ordering.
- `channel-runtime-registry`: Defines ID-keyed runtime construction, lookup, readiness/status projection, committed-session lifetime, runtime reconciliation, and host-owned input lifecycle boundaries.

### Modified Capabilities

- `channel-framework`: Generalizes channel identity, configuration, selection, and input acceptance from three singleton types to catalogue-backed channel instances while preserving committed-target semantics.
- `channel-routing`: Routes PTT and channel traversal through catalogue lookup and runtime readiness instead of fixed IDs and compile-time order.
- `main-device-dashboard`: Renders the ordered functional channel catalogue and provides instance management and kind-specific configuration access instead of fixed cards and mock entries.
- `car-media-channel-browse`: Propagates catalogue additions, removals, reorders, renames, readiness changes, and active-selection repair to the Android Auto browse surface.

## Impact

- Affects channel models and application state, `ChannelRepository`, channel browse projection, dashboard rendering and navigation, Android Auto browse updates, active-channel traversal, PTT dispatch decisions, foreground-service controller composition, and built-in controller lifecycle code.
- Reuses `PttAudioSessionManager`, `CaptureService`, `ChannelInputAcceptance`, `ChannelInputTarget`, audio route resolution, phone PTT gesture semantics, and Android Auto command transport.
- Introduces a versioned persisted catalogue format and a one-time migration from existing SharedPreferences keys; no new external dependency is required by the change contract.
- Existing built-in channel IDs and configuration are migration inputs, but runtime code no longer treats those IDs as the exhaustive channel universe.
