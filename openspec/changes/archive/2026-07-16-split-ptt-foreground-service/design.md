## Context

`PttForegroundService` is the process-lifetime Android host for the application. It is currently 1,645 Kotlin code lines and directly owns or composes nearly every long-lived subsystem. The class is simultaneously:

- the Android `Service`, binder target, and foreground-notification host;
- the dependency-composition root;
- the owner of the public `AppState` projection;
- the Bluetooth HEADSET-profile and SPP/reconnect event bridge;
- the PTT, Telecom, channel-router, and Android Auto callback bridge;
- the `CoreInit` implementation for native STT, TTS, journal, and navigation-TTS resources;
- the channel-management and profile-management UI façade.

The underlying domain subsystems are already substantially separated (`PttDispatcher`, `PttAudioSessionManager`, `ChannelRuntimeRegistry`, `RuntimeInvocationBoundary`, `BootstrapCoordinator`, `ReconnectPolicy`, `ReadinessProbe`, `ServiceAgentRuntimeGraph`, and `NavigationTtsEngine`). The remaining problem is concentrated composition, adapter logic, mutable ownership, and lifecycle coordination in the service itself.

This refactor crosses Android lifecycle, Bluetooth, Telecom, coroutine, native-resource, and UI state boundaries. Observable behavior includes not only final values but callback order, `StateFlow` emission order, coroutine cancellation, route ownership, foreground retention, logging around effects, and exactly-once cleanup. A side-effectful legacy and replacement path cannot safely run in parallel because it would duplicate audio, Bluetooth, notification, filesystem, or native-engine operations.

## Goals / Non-Goals

**Goals:**

- Keep `PttForegroundService` as the stable Android component, binder surface, and composition root while moving cohesive responsibilities to focused internal collaborators.
- Establish one authoritative mutable owner for each extracted responsibility and its coroutine jobs.
- Preserve every existing service entrypoint and caller-visible result during incremental migration.
- Preserve callback order, timeout values, dispatcher/parent-job topology, `StateFlow` publication, log timing, foreground lifetime, reconnect decisions, and resource-release multiplicity.
- Make extracted policy and orchestration independently characterizable with recording fakes or deterministic inputs.
- Reduce `PttForegroundService` to Android lifecycle integration, dependency assembly, narrow callback forwarding, and stable public delegation rather than replacing it with another all-purpose coordinator.

**Non-Goals:**

- No product behavior, UI behavior, permission, SDK, persisted-schema, channel contract, audio format, route policy, reconnect policy, bootstrap predicate, or release-pipeline changes.
- No rewrite of `PttAudioSessionManager`, `ChannelRuntimeRegistry`, `RuntimeInvocationBoundary`, `BootstrapCoordinator`, `NavigationTtsEngine`, or other existing cohesive state machines.
- No new dependency-injection framework, service locator, global singleton, event bus, runtime feature flag, or dual execution of legacy and extracted side effects.
- No conversion of the Android service to another component model.
- No broad naming, formatting, logging, timeout, coroutine, or error-classification cleanup mixed into responsibility migration.
- No requirement that every resulting file be below an arbitrary line-count threshold.

## Decisions

### D1. Use a stable-shell strangler, not parallel implementations

`PttForegroundService` remains the Android shell throughout the migration. Existing methods initially delegate to an extracted collaborator with the same inputs and outputs. Callers migrate only after delegation behavior is established; obsolete service-local code and state are removed immediately after the cutover is complete.

```text
existing caller
      |
      v
PttForegroundService stable method
      |
      v
focused internal collaborator
```

The old and new implementations SHALL NOT both perform production side effects. For deterministic policy, old/new differential comparison is allowed in tests. For side-effectful orchestration, tests compare recorded event traces from isolated executions.

**Alternative considered:** runtime flags routing between complete legacy and replacement implementations. Rejected because duplicate state owners and long-lived Android/audio resources would increase the migration surface and leave a second operational mode to maintain.

### D2. Preserve a small Android shell and composition root

The service retains:

- `Service` inheritance, binder creation, `onCreate`, `onStartCommand`, `onBind`, and `onDestroy`;
- foreground-service API calls and Android component registration at the outer edge;
- construction order and top-level shutdown order;
- callback-interface implementations where Android or process-global APIs require the service instance;
- stable UI-facing properties and methods, delegating internally as responsibilities move.

`onCreate` will become shorter as collaborators are constructed, but it will not be hidden inside a second monolithic composition object. `onDestroy` will continue to express the top-level shutdown sequence explicitly, calling collaborator shutdown methods in the existing order.

**Alternative considered:** one `PttServiceGraph` that constructs and owns every dependency. Rejected because it would relocate rather than eliminate the monolith and obscure lifecycle ordering.

### D3. Extract responsibilities as exclusive ownership units

Each mutable field and `Job` has exactly one authoritative owner after its migration. State does not remain mirrored between the service and collaborator.

Target responsibility units:

1. **`ServiceChannelManager`**
   - Owns provider-backed channel creation and configuration updates.
   - Owns successful channel-selection notifications to immediate and deferred playback coordinators.
   - Preserves repository result types and selection log ordering.
   - Does not own PTT dispatch or `AppState`.

2. **`ServiceStateProjector`**
   - Owns the service-lifetime `MutableStateFlow<AppState>` and exposes a read-only `StateFlow`.
   - Provides explicit connection, monitor, channel/runtime, bootstrap, input-mode, and HFP projection operations.
   - Centralizes compound emission order without exposing the mutable flow to other collaborators.

3. **`RsmSerialConnectionCoordinator`**
   - Owns the target device, SPP client, serial session job, SPP state-collection job, reconnect-delay job, and `ReconnectPolicy`.
   - Accepts readiness/permission/device observations through injected platform seams.
   - Emits typed connection/reconnect/lifetime events to the service instead of mutating `AppState` or calling `stopSelf` directly.
   - Preserves automatic/manual connection distinctions and cancellation caller classifications.

4. **`ForegroundServiceCoordinator`**
   - Owns the logical foreground state and readiness-refresh-loop job.
   - Receives explicit start/stop/monitoring/session events and invokes injected Android-edge callbacks supplied by the service.
   - Preserves notification channel/ID/content and the existing serial-disconnect shutdown predicate.

5. **`ServiceCoreInitializer`**
   - Implements `CoreInit` and owns STT/TTS/journal/navigation-TTS controller references, model directories, and model-status polling jobs.
   - Receives existing audio, model repository, capability, and service-scope dependencies explicitly.
   - Exposes the same bootstrap construction, preparation, discard, and availability behavior to `BootstrapCoordinator`.
   - Provides one idempotent shutdown boundary used from the existing service teardown sequence.

6. **`RsmAnnouncementCoordinator`**
   - Owns navigation text and error-beep request orchestration.
   - Uses the existing catalogue lookup, `NavigationTtsEngine`, host-audio playback, and bootstrap result callbacks.
   - Preserves latest-wins navigation semantics by delegating to the existing engine; it does not introduce its own competing queue.

PTT session ownership remains in `PttAudioSessionManager`; runtime ownership remains in `ChannelRuntimeRegistry`; input-mode policy remains in `InputModeController`. The service continues to bridge Android/Telecom/button callbacks into those owners unless a later extraction can move an entire callback-and-state unit without duplication.

**Alternative considered:** extract methods first while leaving their mutable fields in the service. Rejected because callback-heavy helper objects with remote state ownership create bidirectional coupling and unclear invariants.

### D4. Migrate from low-risk façade logic toward lifecycle ownership

Responsibility migration proceeds in increasing temporal risk:

```text
channel façade
    -> state projection
    -> serial/reconnect ownership
    -> foreground/readiness-loop ownership
    -> core initialization/native resources
    -> navigation announcement orchestration
    -> final shell consolidation
```

Channel management is first because it has typed synchronous results and no Android lifecycle ownership. Serial, foreground, and native-resource cuts occur only after their current event traces are characterized.

The extraction order is not permission to leave duplicate owners between slices: each slice ends with one live implementation and removal of the migrated service-local code.

### D5. Treat temporal behavior and coroutine topology as contracts

The following are part of behavioral equivalence:

- order and multiplicity of playback-selection callbacks;
- PTT press/release/cancellation ownership;
- Telecom capture and route-timeout callback ordering;
- reconnect scheduling, cancellation, and foreground-retention decisions;
- `StateFlow` values and emission sequence;
- parent `Job`, dispatcher, `SupervisorJob`, `NonCancellable`, timeout, and `cancelAndJoin` relationships;
- model poller and native-controller teardown ordering;
- exactly-once route, committed-target, TTS, Bluetooth-profile, and foreground cleanup;
- logs that bracket or diagnose side effects.

Moving a coroutine to a new source file does not justify changing its scope. A collaborator either receives the existing scope or owns a deliberately equivalent child/detached scope whose shutdown behavior is pinned by tests.

### D6. Use explicit constructor dependencies and typed callbacks

Collaborators use ordinary Kotlin constructors, existing contracts, and narrow typed callbacks. Android/native/filesystem/time seams may be function-injected where deterministic tests require them. Internal one-implementation presentation or policy helpers do not receive ceremonial interfaces.

Callbacks communicate domain events or requested Android-edge effects rather than granting collaborators access to the entire service. No collaborator receives `PttForegroundService` or mutable `AppState` as a dependency.

**Alternative considered:** pass the service as a context/facade to every collaborator. Rejected because it preserves unrestricted coupling and prevents independent ownership tests.

### D7. Verify by observable boundary, then by integrated hardware behavior

Verification has three layers:

1. Pure policies and projections: deterministic examples or old/new differential corpus checks.
2. Coordinators: recording fakes assert exact command, callback, state, error, cancellation, and cleanup traces.
3. Android integration: focused service lifecycle tests plus the established physical-device flow for RSM buttons, SCO capture/playback, Telecom car mode, background operation, notification retention, and disconnect teardown.

A final code-line reduction is evidence of decomposition, not its acceptance criterion. Acceptance is exclusive ownership plus preserved observable behavior.

## Risks / Trade-offs

- **[Risk] Moving a job changes cancellation propagation or dispatcher execution.** → Preserve the existing scope and parent relationship initially; characterize shutdown and late-callback behavior before introducing any scope owner.
- **[Risk] Extracted components create circular callbacks.** → Keep data flow directional: Android callbacks enter the service, coordinators emit typed events, and `ServiceStateProjector` is the only `AppState` mutation owner.
- **[Risk] State emissions remain value-equivalent but occur in a different order.** → Record ordered state traces for compound readiness, input-mode, channel, and disconnect transitions.
- **[Risk] Serial reconnect and foreground lifetime become inconsistent.** → Migrate reconnect jobs as one ownership unit and preserve `shouldRetainMonitoringService` and `shouldStopAfterSerialDisconnect` decisions at a single integration boundary.
- **[Risk] Native model/TTS extraction leaks or double-shuts resources.** → Move controller fields and polling jobs together; assert idempotent discard and preserve top-level `onDestroy` ordering.
- **[Risk] Service methods become a permanent duplicate façade.** → Keep them only where required by the binder/UI surface; remove obsolete internal delegation after all callsites use the intended boundary.
- **[Trade-off] The service will remain larger than a typical class.** → Accepted: explicit Android lifecycle and composition ordering are safer in one visible shell than distributed across hidden managers.
- **[Trade-off] Additional focused classes increase navigation between files.** → Limit extraction to cohesive ownership units; do not split one state machine merely to reduce line counts.

## Migration Plan

1. Establish focused characterization barriers for the channel façade, `AppState` projection, reconnect/lifetime decisions, bootstrap resource ownership, announcement dispatch, and service teardown.
2. Extract `ServiceChannelManager`; retain existing service methods as stable delegates and remove migrated channel logic from the service.
3. Introduce `ServiceStateProjector`; migrate all `AppState` writes and derived projections to one owner without changing emissions.
4. Extract `RsmSerialConnectionCoordinator` with all serial/reconnect mutable fields and jobs in one cut; retain Android Bluetooth-profile callbacks in the service edge.
5. Extract `ForegroundServiceCoordinator`, retaining actual `Service` API calls behind injected callbacks and preserving notification/readiness-loop semantics.
6. Extract `ServiceCoreInitializer`, move all `CoreInit` resource fields and model polling together, then wire `BootstrapCoordinator` to it.
7. Extract `RsmAnnouncementCoordinator` after navigation-engine ownership is available through the core initializer.
8. Remove superseded service-local code, interfaces, imports, and fields; keep the service as the explicit lifecycle/composition shell.
9. Run focused automated verification, debug assembly, Android lifecycle instrumentation, and the physical-device acceptance flow.

Each completed slice is independently revertible. Because the public service surface remains stable and no persisted data changes, rollback is a source-level revert with no data migration.

## Open Questions

None. Detailed private type names may adapt to existing package conventions during implementation, but the responsibility and ownership boundaries above are fixed.
