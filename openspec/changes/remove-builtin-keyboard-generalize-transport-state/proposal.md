## Why

The external Lua Keyboard package now exercises the generic keyboard-output path across Linux, macOS, and Windows, so retaining the Kotlin `builtin:keyboard` implementation creates duplicate behavior and keeps transport state named after a channel that no longer owns it. A separate cleanup leg preserves the completed public Lua contract while removing that obsolete implementation.

## What Changes

- **BREAKING** Remove the `builtin:keyboard` provider, runtime, configuration codec, legacy catalogue seed, implementation ID, dashboard creation option, and implementation-specific tests.
- Preserve existing definitions that reference `builtin:keyboard` through the ordinary unavailable-provider path; do not migrate, alias, substitute, or automatically install the external package.
- Rename remaining shared transport state such as `KeyboardConnectionState` to implementation-neutral output-transport terminology, migrating every production and test callsite without changing transport behavior.
- Keep the public `keyboard.output`, dynamic profile hierarchy, readiness preparation, yielded SOS, generation authorization, typed outcomes, and external package contracts unchanged.
- Remove dead built-in-only configuration and persistence paths after all callers move to provider-owned external configuration.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `keyboard-channel`: Remove the built-in Keyboard implementation while retaining host-owned output transport behavior.
- `channel-catalogue`: Preserve old built-in definitions as explicitly unavailable without migration or substitution.
- `channel-implementation-providers`: Remove built-in provider registration and keep missing-provider lifecycle behavior authoritative.
