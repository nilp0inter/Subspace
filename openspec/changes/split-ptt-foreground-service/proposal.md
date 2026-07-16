## Why

`PttForegroundService` contains 1,645 Kotlin code lines and currently combines Android service lifecycle, dependency composition, Bluetooth serial recovery, PTT and Telecom event bridging, channel management, model/bootstrap ownership, navigation announcements, foreground notification policy, and UI state projection. This concentration makes otherwise-local changes difficult to review and increases the chance that modifications to one responsibility alter unrelated lifecycle, cancellation, routing, or state-publication behavior.

## What Changes

- Retain `PttForegroundService` as the stable Android `Service`, binder surface, composition root, and sole top-level owner of service lifetime.
- Introduce focused internal collaborators for channel management, Bluetooth serial/reconnect orchestration, foreground-notification/readiness-loop coordination, model and controller bootstrap ownership, navigation announcement dispatch, and service state projection where each collaborator can have one explicit responsibility and one mutable-state owner.
- Migrate one responsibility at a time through the existing service entrypoints; callers continue to observe the same methods, results, state values, callbacks, logs, and side-effect ordering during migration.
- Preserve existing PTT, SCO, Telecom, channel runtime, bootstrap, persistence, foreground-service, Bluetooth reconnect, and Compose behavior without adding replacement feature flags or executing legacy and extracted side effects in parallel.
- Add focused characterization coverage before moving behavior-bearing lifecycle or coroutine ownership, including callback order, exactly-once cleanup, state publication, reconnect retention, and service teardown.
- Remove migrated service-local state and obsolete delegation only after every caller uses the extracted owner and focused verification establishes equivalent behavior.
- No user-visible or breaking API changes are intended.

## Capabilities

### New Capabilities
- `ptt-foreground-service-decomposition`: Structural ownership, stable-shell migration, and behavioral-equivalence requirements for decomposing `PttForegroundService`.

### Modified Capabilities

None. Existing product requirements remain unchanged; this change reorganizes their implementation without changing their contracts.

## Impact

- Primary code: `app/src/main/java/dev/nilp0inter/subspace/service/PttForegroundService.kt` and new focused service collaborators in the same production package.
- Direct integration surfaces: `MainActivity`, `PttUiActions`, `PttDispatcher`, `PttAudioSessionManager`, `ChannelRuntimeRegistry`, `ServiceAgentRuntimeGraph`, `BootstrapCoordinator`, `NavigationTtsEngine`, `ReconnectPolicy`, `ReadinessProbe`, Bluetooth SPP handling, Telecom callbacks, and foreground notifications.
- Verification: focused service, reconnect, input-mode, channel, bootstrap, PTT/audio, and Android lifecycle tests, followed by the established physical-device acceptance flow for RSM, SCO, Telecom, background operation, and disconnect teardown.
- Dependencies, persisted schemas, SDK levels, permissions, and release behavior remain unchanged.
