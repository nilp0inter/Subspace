## Why

The externally published Diagnostics package proves the package, installation, actor, and logging spine, but Lua v1 still cannot implement any existing product channel because installed providers are configuration-empty, capability-free, and receive only capture metadata. Migrating the complete Debug Channel now is the smallest real vertical slice that can evolve the single pre-release v1 contract around an actual channel while establishing configuration, opaque audio, transcription, synthesis, and host-routed playback foundations reused by later Keyboard, Journal, and OpenAI Agent migrations.

## What Changes

- **BREAKING** Evolve the single pre-release package-format and Lua API v1 contracts in place; intermediate Diagnostics artifacts, fixtures, and callers move through the same clean cutover, with no v2 surface, compatibility shim, range negotiation, or deprecated path.
- Add bounded declarative provider configuration to installed Lua packages: separate data and UI declarations, static defaults and validation, host-rendered editing, lossless per-instance persistence, and a generation-local validated configuration snapshot visible to Lua.
- Add package-declared semantic capability eligibility and fail-closed validation without exposing Kotlin capability keys, Android objects, or host implementation classes.
- Add public Lua v1 opaque-audio and asynchronous semantic operations sufficient for Debug parity: captured-audio delivery, transcription, speech synthesis, and selection-aware deferred host playback with typed results, cancellation, generation revocation, and late-effect suppression.
- Publish the complete Debug Channel as one external official Lua package through the existing GitHub release/install path, implementing `ECHO`, `DELAYED_ECHO`, `STT`, `TTS`, and `STT_TTS` with the existing default, readiness, routing, and status behavior.
- **BREAKING** Remove the Kotlin `DebugBuiltInProvider`, `builtin:debug` descriptor/runtime construction, Debug-specific in-app widgets, and ordinary startup registration. Existing pre-release `builtin:debug` catalogue definitions remain preserved but unavailable through the generic missing-provider contract; the user installs the external package and creates an instance through existing package and catalogue management.
- Update the external Diagnostics package, immutable fixtures, device acceptance evidence, and current v1 specifications to the evolved manifest/API contract while retaining its empty configuration, no-capability behavior.
- Keep the host authoritative for capture, routes, half-duplex admission, synthesis engines, playback selection, interruption, cancellation, and cleanup. Lua receives only normalized values and opaque lifecycle-bound audio references.
- Do not add general HTTP, JSON, filesystem, plugin-owned durable state, credentials, dynamic host-resource choices, durable channel messages, RSM custom menus, automatic updates, discovery website/App Links, signing/attestation, or another built-in-channel migration.

## Capabilities

### New Capabilities

- `lua-channel-configuration`: Pre-release v1 package declarations, host rendering, validation, persistence, runtime snapshots, instance isolation, and configuration replacement semantics for installed Lua providers.
- `lua-audio-api`: Public Lua v1 opaque captured/synthesized audio values and semantic transcription, synthesis, and deferred playback operations with typed asynchronous lifecycle behavior.

### Modified Capabilities

- `lua-package-format`: Extend manifest v1 through a clean cutover to carry bounded declarative configuration and semantic capability declarations while retaining static fail-closed validation and source-only execution.
- `lua-runtime-api`: Extend the pre-release v1 value and asynchronous-operation model with host-created opaque audio references that cannot be forged, inspected, persisted, or used after generation revocation.
- `lua-channel-api`: Materialize package-declared configuration and capability eligibility instead of the empty installed-package provider, and bind runtime construction to the existing generation execution context and revocable capability scope.
- `lua-channel-provider`: Deliver validated configuration and captured audio to Lua callbacks, expose mode-dependent readiness and status behavior, and inject the semantic audio modules without preserving the metadata-only intermediate callback contract.
- `channel-implementation-providers`: Permit installed descriptors to carry validated package-declared configuration fields and semantic capability eligibility while removing the built-in Debug registration.
- `channel-host-capabilities`: Adapt existing transcription, synthesis, audio-operation, and deferred-playback capabilities to Lua through language-neutral opaque operations without weakening host ownership or revocation.
- `channel-catalogue`: Stop seeding or resolving a built-in Debug provider while preserving any pre-release `builtin:debug` definitions as unavailable rather than silently rebinding them to an installed repository identity.
- `debug-channel`: Replace the Kotlin built-in implementation with an external official Lua package while preserving all five modes, default configuration, readiness, status, delayed playback, route ordering, and failure behavior.
- `diagnostics-channel`: Rebuild and republish the development package against the evolved single v1 manifest/API contract without adding configuration or capabilities.

## Impact

- Package domain: manifest decoding, validation, immutable revision materialization, package fixtures, and published Diagnostics/Debug artifacts.
- Provider/configuration domain: installed descriptor construction, generic host-rendered fields, opaque catalogue payloads, validation, per-instance isolation, and configuration-triggered generation replacement.
- Lua runtime/API: callback arguments, normalized values, opaque audio ownership, module injection, yielding operations, typed errors, cancellation, and compatibility constants within the still-unreleased v1 contract.
- Host capability adapters: transcription, synthesis, playback-operation creation, deferred playback scheduling, selection, half-duplex admission, and generation-safe completion.
- Product composition: removal of `DebugBuiltInProvider`, built-in Debug descriptor/registration and specialized UI, external package publication, generic package installation, and generic instance creation.
- Verification: package adversarial tests, configuration UI/persistence tests, black-box Lua actor tests, capability race/revocation tests, complete Debug mode parity tests, release-artifact checks, and physical-device Work/On-a-pinch/On-the-road routing evidence.
- No SDK, Android permission, release-signing, Journal, Keyboard, OpenAI Agent, GitHub discovery, or foreground-service-retention change is intended.
