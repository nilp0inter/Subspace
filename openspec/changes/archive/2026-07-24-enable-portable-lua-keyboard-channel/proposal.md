## Why

Subspace needs a general Lua keyboard-output facility that future plugin authors can use without binding to Kotlin channel implementations or Sleepwalker transport details. The external Keyboard Channel is the first real client: it will prove dynamic host-profile selection, recoverable pre-input preparation, generation-authorized keyboard output, and yield-capable SOS while the existing built-in remains available for side-by-side evaluation.

## What Changes

- Add public manifest capability `keyboard.output` and host-injected Lua module `subspace.keyboard_output` with bounded semantic `send_text` and `send_key` operations, logical host profiles, typed non-replay terminal outcomes, and no raw HID/BLE/GATT exposure.
- Authorize declared keyboard-output operations from `handle_input`, yield-capable `handle_sos`, and runtime-managed tasks. Authorization belongs to the live channel instance and runtime generation and is not revoked by channel deselection.
- Add generic readiness-declared capability preparation: Lua may name declared host-preparable capabilities in a bounded `prepare` result, and `prepareInput` performs host-owned bounded preparation, refreshes readiness, and accepts capture only after readiness becomes true.
- Add generic manifest-declared dependent dynamic scalar choices and expose the host keyboard catalogue through bounded platform, layout, and final-profile sources; persisted configuration contains only stable scalar IDs, with the final profile ID used for output.
- Extend the typed Lua host-operation broker, generation ownership, deadlines, quotas, cancellation, revocation, serialization, and late-completion suppression to keyboard text and semantic key operations.
- Make `handle_sos` yield-capable under the same managed execution and exact terminal-result discipline as other authorized callbacks while preserving existing synchronous SOS implementations.
- Create and publish an external, non-bundled Lua Keyboard Channel package using only generic public APIs. Its Lua code owns Keyboard-specific readiness, transcription, trailing-space policy, output delivery, and SOS-to-Enter behavior.
- Keep `builtin:keyboard`, its catalogue seed, configuration, Kotlin runtime, and tests operational for side-by-side evaluation. Do not automatically install the external package, create an instance, copy configuration, change active selection, or rebind provider identity.
- Keep Sleepwalker discovery, connection preparation, keymap compilation, BLE/GATT/HID delivery, acknowledgement, serialization, and safety cleanup host-owned behind generic keyboard-output contracts.
- Evolve the unreleased Lua/package v1 contracts directly; no compatibility version, alias, shim, or legacy dispatch is added.

## Capabilities

### New Capabilities

- `lua-keyboard-output-api`: Public `keyboard.output` eligibility, `subspace.keyboard_output` functions, dynamic profile references, execution contexts, typed delivery outcomes, operation ownership, bounds, serialization, cancellation, revocation, and transport isolation.
- `lua-keyboard-channel`: External package identity, configuration, readiness, preparation, PTT transcription/output behavior, trailing-space policy, SOS key behavior, publication, installation, independent instances, and side-by-side acceptance with `builtin:keyboard`.

### Modified Capabilities

- `lua-package-format`: Accept the new public capability identifier and bounded dynamic-choice UI declarations without embedding host objects or Keyboard-channel identities.
- `lua-channel-configuration`: Resolve manifest-declared dynamic scalar choice sources through host-owned registries, persist only stable scalar IDs, and expose current reference validity to runtime readiness.
- `lua-channel-provider`: Compile dynamic choices and `keyboard.output` eligibility into package-specific providers without Lua execution or channel/package special cases.
- `lua-channel-api`: Add bounded readiness-declared preparation and yield-capable SOS while preserving exact callback results and synchronous callbacks.
- `lua-runtime-api`: Reserve and inject `subspace.keyboard_output` and authorize its yielding operations from input, SOS, and managed-task execution owners.
- `lua-actor-runtime`: Carry typed keyboard text/key requests through the opaque host-operation broker with exact-once claim, suspension, resumption, cancellation, and close semantics.
- `channel-host-capabilities`: Generalize keyboard output and capability preparation as instance/generation-scoped semantic host facilities while retaining platform and transport ownership.
- `channel-runtime-invocation`: Run recoverable pre-input preparation and yielded SOS operations under bounded generation gates without ready-beep, capture, or late-effect races.
- `installed-lua-packages`: Preserve, validate, materialize, update, roll back, and restart packages using dynamic choice declarations and `keyboard.output` through the ordinary installed-provider path.

## Impact

- Android/Kotlin: package domain and validator, configuration field materialization and dynamic-choice resolution, Lua provider/runtime, typed operation broker, runtime invocation boundary, capability host, keyboard-output scheduler/adapter, Sleepwalker service composition, and generic configuration UI.
- Rust/JNI Lua actor: reserved module, exact argument/result validation, typed request payloads, coroutine context eligibility, operation ownership, and terminal resumption.
- External repositories: create `nilp0inter/keyboard-channel`, resolve its durable repository identity, build deterministic source-only package bytes, publish an immutable release, and record exact provenance.
- Existing packages: Debug, Diagnostics, and Journal remain ordinary packages and must continue to execute through the evolved pre-release v1 contract; no Keyboard-specific branch is added for them.
- Existing built-in Keyboard: retained unchanged as an evaluation baseline in this change; complete removal and generalization of remaining Keyboard-named host state belong to a later change.
- Security and privacy: package declaration grants generation-authorized keyboard output even while unselected; all content, platform objects, transport details, acknowledgement identities, and raw hardware operations remain absent from logs and public Lua values.
