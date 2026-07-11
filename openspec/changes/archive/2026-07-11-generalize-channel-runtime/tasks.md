## 1. Catalogue Model and Persistence

- [x] 1.1 Define stable channel instance IDs, supported built-in kind identifiers, typed versioned Journal/Debug/Keyboard configurations, channel definitions, and nonempty ordered catalogue snapshots
- [x] 1.2 Implement catalogue validation for schema versions, unique IDs, supported kinds, valid configurations, nonempty definitions, and active-ID membership
- [x] 1.3 Implement deterministic pure mutation policies for add, update, select, move, remove, and next-otherwise-previous active repair
- [x] 1.4 Implement the strict `org.json` catalogue codec with explicit document and per-kind configuration schema versions
- [x] 1.5 Implement serialized atomic catalogue file commits using temporary-file validation and replacement without partial publication
- [x] 1.6 Implement one-time legacy SharedPreferences migration that preserves built-in IDs, configuration, initial order, and valid active selection
- [x] 1.7 Implement the repository state flow and persistence-before-publication mutation API, including unchanged-state behavior after commit failure
- [x] 1.8 Add focused catalogue tests for codec validation, corruption rejection, mutation invariants, active repair, restart order, atomic failure behavior, and idempotent legacy migration

## 2. Runtime Registry Foundation

- [x] 2.1 Define generic runtime readiness, execution status, presentation snapshot, runtime factory, runtime lifecycle, and input-preparation contracts
- [x] 2.2 Implement the kind-to-factory registry and instance-ID runtime lookup without exhaustive built-in ID routing
- [x] 2.3 Implement catalogue reconciliation that preserves runtimes across selection/reorder and replaces or retires runtimes across configuration changes/removal
- [x] 2.4 Implement committed-target leases that reject new work after retirement and close each retired runtime exactly once after its final target terminates
- [x] 2.5 Implement idempotent registry shutdown that prevents preparation, terminates host PTT ownership, cancels and joins runtime child work, and closes live and retired entries
- [x] 2.6 Harden runtime callback invocation so non-cancellation failures become typed per-instance failures while host session clearance and route release remain exact-once
- [x] 2.7 Add focused registry tests for creation, lookup, refusal, status projection, reorder preservation, replacement, removal, lease termination, late completion suppression, callback failure, and shutdown
- [x] 2.8 Add a test-only fourth runtime kind proving selection, projection, and PTT routing require no core built-in ID or subtype changes

## 3. Application State and Selection Cutover

- [x] 3.1 Replace fixed Journal/Debug/Keyboard `AppState` fields with ordered generic runtime snapshots plus one valid active instance ID
- [x] 3.2 Derive dispatch readiness and selected runtime lookup from the current catalogue/registry snapshot rather than fixed-ID branches
- [x] 3.3 Move active selection and offset traversal onto repository mutations over catalogue order, rejecting unknown IDs and preserving active selection during reorder
- [x] 3.4 Project Android Auto browse entries and now-playing active name/status from the same ordered generic snapshot
- [x] 3.5 Update hardware control-mode next/previous traversal and channel announcements to use catalogue instance order, IDs, and display names
- [x] 3.6 Replace identity-based keyboard/keyguard and SOS policy checks with explicit runtime kind or capability metadata where the existing behavior must remain
- [x] 3.7 Add focused state and selection tests for unknown IDs, cross-surface order identity, multiple same-kind instances, active repair, announcements, and hardware traversal saturation

## 4. Built-in Runtime Migration

- [x] 4.1 Implement a Journal runtime factory whose instances snapshot per-definition directory/save configuration and expose Journal readiness/status through the generic contracts
- [x] 4.2 Route Journal capture writing, terminal metadata, recovery, encoding, and transcription through committed targets without controller-owned route or capture lifecycle
- [x] 4.3 Implement a Debug runtime factory whose instances snapshot mode and relevant STT/TTS parameters before acceptance and expose generic readiness/status
- [x] 4.4 Adapt Echo, STT, TTS, and STT-TTS processing to domain-only committed targets with host-mediated playback
- [x] 4.5 Implement a Keyboard runtime factory whose instances snapshot host profile, share the BLE/HID resources safely, and expose bridge-dependent readiness/status
- [x] 4.6 Adapt Keyboard transcription and HID effects to committed targets without controller-owned route or capture lifecycle
- [x] 4.7 Register all production built-in factories at the foreground-service composition root and replace `PttForegroundService.prepareInput` built-in branches with registry delegation
- [x] 4.8 Add focused built-in contract tests through the registry for readiness refusal, configuration snapshotting, success, cancellation, failure, playback, Journal recovery, and Keyboard HID cleanup

## 5. Instance Configuration and Management UI

- [x] 5.1 Replace fixed configuration navigation with instance-ID and kind-addressed routing while retaining the existing built-in editors
- [x] 5.2 Update Journal, Debug, and Keyboard configuration actions to load and mutate the selected definition rather than singleton state fields
- [x] 5.3 Replace the three fixed dashboard cards with a generic repeated card over the ordered runtime snapshot while preserving tap selection, held PTT, slide-lock, stop, readiness, and config-button isolation
- [x] 5.4 Add a catalogue-management surface reachable from the channel panel for choosing a supported kind, creating an instance, renaming, moving, and confirming removal
- [x] 5.5 Prevent final-instance removal in the management surface and render active-selection repair only from the committed catalogue snapshot
- [x] 5.6 Remove fixed visual channel ordinals and the nonfunctional Command Uplink mock from the dashboard channel list
- [x] 5.7 Add focused UI-state and action tests for dynamic card order, multiple same-kind cards, instance-specific configuration, add, rename, move, remove, final-removal rejection, and phone PTT targeting

## 6. Android Auto and Lifecycle Integration

- [x] 6.1 Invalidate Android Auto browse subscriptions for catalogue add, remove, reorder, rename, readiness, status, and active-selection changes
- [x] 6.2 Verify Android Auto media IDs remain stable instance IDs and active removal updates browse and now-playing metadata from one committed snapshot
- [x] 6.3 Integrate registry reconciliation with service startup, bootstrap resource availability, catalogue collection, and service destruction
- [x] 6.4 Ensure selection or reorder never cancels a committed target, while explicit PTT cancellation, instance retirement, and service shutdown terminate work according to registry leases
- [x] 6.5 Add focused service-extracted integration tests for catalogue-to-registry-to-dispatch flow, committed target survival, removal during PTT, runtime failure isolation, and exact-once teardown

## 7. Clean Cutover and Verification

- [x] 7.1 Remove legacy per-type repository writes, fixed channel list construction, type-based `orderIndex`, fixed dispatch decisions, and typed controller registry slots after all consumers use the catalogue and runtime registry
- [x] 7.2 Remove obsolete direct controller PTT/capture/route APIs and duplicated mutable session state after every built-in uses host-owned committed targets
- [x] 7.3 Remove fixed singleton configuration routes/actions and confirm no production channel enumeration or routing branch depends on Journal, Debug, or Keyboard IDs
- [x] 7.4 Run targeted JVM test classes covering the catalogue, migration, registry, PTT session manager, built-in runtime adapters, selection/projection, dashboard actions, and Android Auto projection through the repository devshell
- [x] 7.5 Build and install the debug application through the repository devshell, then verify on the physical Android device that add, configure, select, reorder, remove, restart persistence, RSM/phone PTT, hardware traversal, and Android Auto ordering preserve existing audio behavior

## 8. Catalogue Regression Corrections

- [x] 8.1 Preserve committed catalogue order in runtime snapshots after pure reorder while retaining runtime object identity
- [x] 8.2 Keep Debug readiness based on configured dependencies rather than active-controller enablement and refresh snapshots when dependencies change
- [x] 8.3 Carry instance IDs through configuration navigation, asynchronous directory selection, and Journal/Debug/Keyboard mutations so same-kind siblings remain isolated
- [x] 8.4 Keep every supported built-in kind reachable in the management UI and verify Keyboard instances remain repeatable after removal of the migrated seed
