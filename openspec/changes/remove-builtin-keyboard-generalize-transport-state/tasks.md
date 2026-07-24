## 1. Baseline and state generalization

- [ ] 1.1 Record focused baseline behavior for built-in provider composition, legacy catalogue migration, Sleepwalker transport state, semantic output capability, and external Keyboard execution.
- [ ] 1.2 Rename `KeyboardConnectionState` to `TextOutputTransportState` and migrate all production and test references with Kotlin symbol-aware refactoring.
- [ ] 1.3 Rename `keyboardConnectionState` monitor and projection fields to `textOutputTransportState` without changing state values, transitions, or UI behavior.
- [ ] 1.4 Run focused model, BLE, text-output service, foreground-service, and monitor projection tests for the generalized state names.

## 2. Persisted definition and migration behavior

- [ ] 2.1 Add catalogue contract tests proving persisted `builtin:keyboard` definitions retain exact identity, order, schema version, and opaque payload while resolving unavailable.
- [ ] 2.2 Add tests proving external Keyboard package installation does not rebind, mutate, remove, or copy configuration from a persisted `builtin:keyboard` definition.
- [ ] 2.3 Change first-catalogue migration and default seeding to omit built-in Keyboard while preserving supported registered seeds.
- [ ] 2.4 Change legacy active-Keyboard fallback to select a valid enabled registered seed and persist the complete catalogue atomically.
- [ ] 2.5 Run focused catalogue repository, persistence, migration, selection, and missing-provider tests.

## 3. Built-in provider removal

- [ ] 3.1 Remove `KeyboardBuiltInProvider`, its runtime implementation, and built-in configuration codec/model after all live callers are identified.
- [ ] 3.2 Remove `BuiltInChannelImplementationIds.KEYBOARD` and every built-in Keyboard provider registration and runtime construction site.
- [ ] 3.3 Remove built-in Keyboard creation metadata and editor/catalogue entry points while retaining the installed external provider path.
- [ ] 3.4 Remove obsolete built-in-only preferences and configuration migration code that no supported provider consumes.
- [ ] 3.5 Delete implementation-specific built-in Keyboard tests and fixtures; preserve or relocate host-owned output transport, capability, keymap, admission, and terminal-outcome coverage.
- [ ] 3.6 Update provider-composition and legacy-catalogue tests to assert `builtin:keyboard` is unregistered and old definitions use the generic unavailable-provider path.

## 4. Contract and regression verification

- [ ] 4.1 Prove host-owned Sleepwalker discovery, preparation, profile resolution, keymap compilation, serialization, acknowledgement, cancellation, timeout, force-release, and cleanup remain unchanged.
- [ ] 4.2 Prove installed external Keyboard instances still validate, restore, configure through the platform/layout/profile hierarchy, report readiness, handle PTT and SOS, and deliver typed outcomes.
- [ ] 4.3 Prove Journal, OpenAI Agent, Debug, Diagnostics, and other provider composition and catalogue behavior remain unchanged.
- [ ] 4.4 Confirm production sources contain no `builtin:keyboard` provider, configuration codec, runtime, seed, registration, alias, automatic external-package migration, or compatibility shim.
- [ ] 4.5 Run focused Gradle verification, then the complete Gradle build within the repository devshell.

## 5. Device acceptance and cleanup

- [ ] 5.1 Install and launch the debug app on `B02PTT-FF01`; verify no built-in Keyboard creation option is exposed and an existing built-in definition remains visible as unavailable.
- [ ] 5.2 Install or retain the official external Keyboard package, create an instance explicitly, and verify readiness plus Linux, macOS, and Windows profile delivery through the unchanged host facility.
- [ ] 5.3 Exercise update, rollback, removal, reinstall, app restart, and foreground-service shutdown without mutating the unavailable legacy definition.
- [ ] 5.4 Update `CHANGELOG.md` to record built-in Keyboard removal and implementation-neutral transport-state naming.
- [ ] 5.5 Run final targeted and complete verification after physical acceptance and record content-free acceptance evidence.
