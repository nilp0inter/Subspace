## Context

`builtin:keyboard` is a Kotlin channel provider layered over the same host-owned `SleepwalkerTextOutputService` now used by the external Lua Keyboard package through `keyboard.output`. The built-in still owns a provider descriptor, configuration codec, legacy catalogue seed, runtime implementation, dashboard creation entry, and tests. Separately, the shared BLE/output monitor still uses `KeyboardConnectionState`, even though the connection and semantic output capability are host facilities used by arbitrary channel implementations.

The external package has completed side-by-side evaluation across Linux, macOS, and Windows. The public Lua API, profile hierarchy, preparation contract, execution ownership, typed outcomes, and installed-package lifecycle are fixed inputs to this cleanup.

## Goals / Non-Goals

**Goals:**

- Remove every production construction and registration path for `builtin:keyboard`.
- Keep already persisted `builtin:keyboard` definitions byte-for-byte intact and visibly unavailable through the generic missing-provider path.
- Stop creating a built-in Keyboard definition when migrating legacy preferences or initializing a new catalogue.
- Rename shared output transport state and monitor fields to implementation-neutral terminology across all production and test callsites.
- Retain the host-owned Sleepwalker connection, keymap compilation, admission, and delivery facility for external packages.
- Leave no alias, compatibility provider, automatic package installer, or repository-specific migration.

**Non-Goals:**

- No change to `keyboard.output`, dynamic profile sources, preparation, SOS, authorization, typed outcomes, or package format.
- No automatic conversion of built-in Keyboard configuration into an external package instance.
- No removal or generalization of Sleepwalker-specific transport implementation classes where the implementation is genuinely device-specific.
- No changes to Journal, OpenAI Agent, Diagnostics, Debug, or other channel behavior.
- No migration that rewrites an already valid catalogue merely to delete unavailable definitions.

## Decisions

### D1. Retire the provider by clean removal

Delete the built-in provider/runtime/configuration implementation and remove its registry construction site. Remove `BuiltInChannelImplementationIds.KEYBOARD` after every callsite is migrated. Do not leave a provider that only reports unavailable and do not alias the ID to the installed package.

This makes the ordinary missing-provider result authoritative and prevents two implementations from competing for the same product behavior.

**Alternative considered:** keep a disabled compatibility descriptor. Rejected because it preserves configuration and runtime code that no longer has an execution purpose and weakens the generic missing-provider contract.

### D2. Preserve persisted definitions; change only future seeding

A catalogue already containing `builtin:keyboard` remains structurally valid. It retains the exact instance ID, name, enabled state, schema version, and opaque payload, while provider resolution reports unavailable. Installation of any external package does not mutate or rebind that definition.

When no catalogue exists, legacy preference migration creates only the still-supported built-in seed definitions. A legacy active selection of Keyboard or Debug falls back to a valid seeded instance before the catalogue is committed. No external package is installed or instantiated as part of migration.

**Alternative considered:** migrate each built-in Keyboard definition to the official external package. Rejected because repository-derived provider identity, package installation consent, package configuration shape, and instance ownership are not equivalent to a built-in implementation ID.

### D3. Generalize only shared transport-state names

Rename `KeyboardConnectionState` to `TextOutputTransportState` and corresponding `keyboardConnectionState` monitor fields to `textOutputTransportState`. Preserve the existing states and transitions (`Disconnected`, `Scanning`, `Connecting`, `Connected`) and update all callers through symbol-aware refactoring. Sleepwalker-specific classes retain Sleepwalker names because they implement that concrete transport.

The semantic capability remains the public channel boundary; the renamed enum is internal host monitoring state and is not added to Lua values, package metadata, diagnostics payloads, or provider contracts.

**Alternative considered:** collapse the transport enum into `TextOutputAvailability`. Rejected because low-level scan/connect progress and semantic delivery availability have different consumers and state-transition meaning.

### D4. Separate host-facility tests from retired channel tests

Delete tests whose observable contract is construction, configuration, status, PTT lifecycle, or dispatch of `builtin:keyboard`. Keep and rename tests for Sleepwalker transport, semantic capability acquisition, keymap/profile resolution, output admission, serialization, typed outcomes, preparation, cleanup, and external package behavior. Provider-composition and legacy-catalogue tests assert that `builtin:keyboard` is absent and old definitions resolve unavailable.

**Alternative considered:** delete every test with Keyboard in its name. Rejected because many of those tests defend the host-owned output facility now required exclusively by external clients.

## Risks / Trade-offs

- Existing users with only a persisted built-in Keyboard definition can have no ready active channel after upgrade. Mitigation: retain the definition visibly unavailable and require explicit external package installation and instance creation; do not conceal the transition with automatic mutation.
- Legacy-migration fallback can accidentally select a removed provider. Mitigation: build the initial catalogue only from registered seeds and assert the active ID belongs to that committed set.
- Mechanical state renaming can miss serialized or Compose field callsites. Mitigation: use Kotlin LSP rename for symbols, then run focused model, BLE, service, dashboard, and external-package tests.
- Removing built-in tests can erase host-output coverage. Mitigation: classify each test by observable owner before deletion and preserve host capability and transport contracts.
- Rolling back to an older app leaves external instances unavailable to that older binary. This is existing installed-provider API compatibility behavior; package bytes and catalogue definitions remain intact.
