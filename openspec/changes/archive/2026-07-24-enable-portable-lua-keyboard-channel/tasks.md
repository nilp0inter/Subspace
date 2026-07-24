## 1. Baseline and superseded work

- [x] 1.1 Mark `add-lua-audio-echo-and-debug-example` abandoned or rewrite it so no implementation targets the superseded Lua callback architecture.
- [x] 1.2 Record the current built-in Keyboard unit and instrumentation results as the side-by-side behavior baseline.
- [x] 1.3 Identify every production construction site for the Lua package validator, materializer, provider registry, runtime registry, capability host, configuration editor, and shared Sleepwalker output service.
- [x] 1.4 Define shared positive limits and deadlines for profile IDs, text bytes, per-generation output admission, process output admission, readiness preparation, keyboard delivery, and SOS execution.
- [x] 1.5 Add failing contract tests for the new manifest capability, dynamic-choice control, readiness preparation result, keyboard-output module, and yielded SOS path before their production implementations.

## 2. Package capability and configuration declarations

- [x] 2.1 Add public package capability identifier `keyboard.output` to the exact manifest capability allowlist and immutable validated-package model.
- [x] 2.2 Extend manifest capability validation tests for accepted `keyboard.output`, duplicate declarations, unknown identifiers, wrong types, missing members, and exact-key rejection.
- [x] 2.3 Add generic `dynamic-choice` UI declarations with a bounded source identifier to the validated configuration model.
- [x] 2.4 Extend the exact-key configuration decoder for the `dynamic-choice` control and reject static choices, missing source, extra keys, constrained fields, non-string fields, and unknown sources.
- [x] 2.5 Preserve dynamic-choice declarations through validated package revisions, defensive copies, equality, hashing, storage metadata, and reparse-from-artifact recovery.
- [x] 2.6 Add package validation and recovery tests proving that cached metadata cannot add, remove, or alter `keyboard.output` eligibility or dynamic source declarations.
- [x] 2.7 Extend existing Debug, Diagnostics, and Journal fixtures to the evolved strict manifest/configuration shape without changing their behavior.

## 3. Generic host dynamic-choice resolution

- [x] 3.1 Extract the current host profile/model choice logic into a bounded `DynamicConfigurationChoiceResolver` registry keyed by public source ID.
- [x] 3.2 Register existing OpenAI connection-profile and model sources through the generic resolver without changing editor behavior.
- [x] 3.3 Register `keyboard-output-profiles` as a generic source exposing only stable scalar IDs and bounded display labels.
- [x] 3.4 Compile validated dynamic-choice declarations into generic provider configuration fields without resolving a source or executing Lua.
- [x] 3.5 Update the configuration editor to resolve dynamic choices on display and preserve the persisted scalar unchanged when resolution is unavailable.
- [x] 3.6 Add exact validation of resolved choice bounds, nonblank IDs and labels, uniqueness, source deadlines, and all-or-nothing publication.
- [x] 3.7 Add editor tests for available, empty, missing, failed, timed-out, duplicate, malformed, and stale dynamic sources.
- [x] 3.8 Add runtime reference-state projection for every required dynamic field using detached `available` or `unavailable` scalar states.
- [x] 3.9 Re-resolve required dynamic references during readiness refresh and revalidate the selected profile immediately before every keyboard effect.
- [x] 3.10 Add multi-instance and restart tests proving independent scalar profiles survive unchanged without retaining host profile objects.

## 4. Generic capability preparation contracts

- [x] 4.1 Add a public-capability-to-host-capability mapping for `keyboard.output` without package-name, repository, label, or implementation-ID branches.
- [x] 4.2 Introduce a bounded generic capability-preparer registry keyed by public capability ID.
- [x] 4.3 Register the host keyboard-output preparer using the existing shared Sleepwalker preparation facility.
- [x] 4.4 Bind each preparation request to input attempt, channel instance, runtime generation, and `PREPARE_INPUT` invocation gate.
- [x] 4.5 Implement compatible in-flight preparation joining while preventing duplicate connection attempts and cross-attempt commitment.
- [x] 4.6 Enforce preparation deadlines, cancellation, release, replacement, shutdown, idempotent cleanup, and stale-completion suppression.
- [x] 4.7 Add preparer-registry tests for unknown, undeclared, non-preparable, duplicate, over-bound, cancelled, timed-out, and stale requests.
- [x] 4.8 Prove through tests that capability declaration and provider construction perform no preparation or platform effect.

## 5. Lua readiness and input preflight

- [x] 5.1 Extend the exact `handle_readiness` result decoder with optional bounded duplicate-free `prepare` capability IDs.
- [x] 5.2 Reject readiness results containing unknown, duplicate, undeclared, non-preparable, malformed, over-bound, metatable-backed, or ready-plus-nonempty preparation data.
- [x] 5.3 Cache readiness status and preparation requests atomically with detached capability, resource, and dynamic-reference context.
- [x] 5.4 Extend `LuaAdapterRuntime.prepareInput` to execute only the cached readiness-declared preparers under the current input attempt.
- [x] 5.5 Refresh Lua readiness exactly once after successful preparation and accept only if the refreshed result is ready.
- [x] 5.6 Keep target commitment, ready beep, audio capture, transcription, and later effects after successful preflight acceptance only.
- [x] 5.7 Suppress target commitment and all later input effects when preparation fails, times out, is cancelled, completes stale, or refresh remains not ready.
- [x] 5.8 Add race tests for release, cancellation, disconnect, replacement, disablement, runtime close, and service shutdown during preparation.
- [x] 5.9 Add tests proving audio-transcription unavailability is package-selectable readiness policy rather than an unconditional Kotlin gate.

## 6. Generic host keyboard-output facility

- [x] 6.1 Rename or extract the semantic Kotlin port to generic `KeyboardOutputCapability`, `KeyboardOutputProfile`, request, key, and delivery-outcome contracts.
- [x] 6.2 Preserve the existing Sleepwalker service as the sole owner of profile validation, keymap compilation, connection preparation, BLE/GATT/HID effects, acknowledgement, force release, disarm, and cleanup.
- [x] 6.3 Add a generation-scoped keyboard-output adapter that acquires and releases host capabilities without transferring shared-service ownership to runtimes.
- [x] 6.4 Accept bounded UTF-8 text and exactly semantic keys `enter` and `escape`; reject all raw keycodes, usages, modifiers, chords, transport controls, and extra data.
- [x] 6.5 Add one host-wide bounded FIFO admission scheduler shared by built-in and Lua output operations.
- [x] 6.6 Enforce per-instance, per-generation, and process limits for active operations, queued operations, retained payload bytes, and waiters before physical effect.
- [x] 6.7 Retain instance, generation, and execution-owner attribution for each admitted operation while excluding content from queue labels and metadata.
- [x] 6.8 Map host results exactly to `delivered`, `rejected`, `failed`, or `indeterminate` with bounded stable reasons and no automatic replay.
- [x] 6.9 Ensure queued revocation proves no effect began, while post-effect cancellation, timeout, disconnect, acknowledgement loss, and uncertain cleanup become `indeterminate` when partial output cannot be excluded.
- [x] 6.10 Make cleanup and terminal completion idempotent so exactly one outcome wins and sibling operations remain untouched.
- [x] 6.11 Add scheduler tests for deterministic ordering, bounded capacity, fairness, cross-instance isolation, revocation, timeout, shutdown, and terminal races.
- [x] 6.12 Add privacy tests proving text, keys, profiles, compiled operations, acknowledgements, addresses, and device identities never enter generic diagnostics, snapshots, or exported evidence.
- [x] 6.13 Route the existing built-in Keyboard runtime through the same bounded host-wide admission policy without changing its public behavior.

## 7. Actor broker keyboard-output operations

- [x] 7.1 Add typed `KEYBOARD_SEND_TEXT` and `KEYBOARD_SEND_KEY` host-operation kinds to the Lua actor kernel.
- [x] 7.2 Validate exact keyboard-output request tables and all byte/value bounds inside the kernel before registering an operation.
- [x] 7.3 Store payloads only in opaque typed request records bound to Lua state, runtime generation, and execution owner.
- [x] 7.4 Yield only opaque claim identities and prohibit text, profile, key, JSON, or transport data in yielded labels.
- [x] 7.5 Extend exactly-once typed claiming for keyboard requests and reject unknown, duplicate, foreign, malformed, cancelled, closed, and stale claims before capability acquisition.
- [x] 7.6 Race host completion, timeout, execution-owner cancellation, task cancellation, generation revocation, and actor close through one idempotent terminal gate.
- [x] 7.7 Queue continuation resumption through the serialized actor slot instead of re-entering Lua from a host completion thread.
- [x] 7.8 Discard cancelled or revoked coroutines without Lua re-entry while allowing the host operation to finish safety cleanup.
- [x] 7.9 Add actor tests for valid text/key claims, duplicate/foreign claims, concurrent actor work, completion-timeout races, task cancellation, close, and late completion.

## 8. Public Lua keyboard-output module

- [x] 8.1 Reserve and inject `subspace.keyboard_output` into every Lua state without granting capability authority.
- [x] 8.2 Reject package source that shadows `subspace.keyboard_output` before Lua state creation.
- [x] 8.3 Expose exactly `send_text(request)` and `send_key(request)` with the normalized result/error contract from `lua-keyboard-output-api`.
- [x] 8.4 Reject callable module use during entry or lazy-module evaluation through the existing effect-during-load guard.
- [x] 8.5 Require validated `keyboard.output` declaration before profile lookup, queue admission, capability acquisition, preparation, compilation, or effect.
- [x] 8.6 Authorize calls only from current host-managed input, yielded SOS, or runtime-managed task execution owners belonging to the live generation.
- [x] 8.7 Return `E_INVALID_CONTEXT` for startup, lifecycle, readiness, source evaluation, and unmanaged coroutine calls before suspension or effect.
- [x] 8.8 Keep managed-task output authorized after channel deselection while revoking it on disablement, replacement, removal, generation close/failure, or shutdown.
- [x] 8.9 Add module tests for require/shadow/load behavior, declaration checks, every execution context, selection changes, generation replacement, normalized outcomes, and content privacy.

## 9. Yield-capable SOS and invocation ownership

- [x] 9.1 Add a bounded SOS execution-owner kind to actor and invocation state without permitting spawn, defer, sleep, or raw yield.
- [x] 9.2 Run `handle_sos` as a host-managed coroutine capable of yielding only explicitly authorized typed operations.
- [x] 9.3 Preserve existing synchronous SOS callbacks as the no-yield subset of the evolved contract.
- [x] 9.4 Enforce the exact SOS terminal result shape, phase deadline, operation deadline, and locally contained failure projection.
- [x] 9.5 Revoke suspended SOS owners on replacement, removal, close, failure, or shutdown and suppress every late resume or successor mutation.
- [x] 9.6 Ensure SOS dispatch holds no catalogue/runtime-registry locks and never executes blocking work on the Android main thread.
- [x] 9.7 Add SOS tests for delivered output, application failure, malformed return, throw, raw yield, forbidden APIs, timeout, cancellation, revocation, and late completion.

## 10. Provider, registry, and lifecycle integration

- [x] 10.1 Compile `keyboard.output` eligibility and dynamic-choice fields generically in `LuaPackageMaterializer` without Lua execution.
- [x] 10.2 Mark materialized descriptors as recoverably preparable only when their declared public capabilities map to registered preparers.
- [x] 10.3 Inject the generic choice resolver, preparer registry, and keyboard-output adapter through normal production Lua provider construction.
- [x] 10.4 Keep provider inspection, installation, registration, editor rendering, and catalogue restoration free of Lua state creation and keyboard effects.
- [x] 10.5 Reconcile update, rollback, removal, reinstall, disablement, and configuration replacement through the existing atomic provider/runtime generation path.
- [x] 10.6 Stop predecessor admission, revoke predecessor keyboard leases and queued operations, close actor state, and suppress late completion before successor readiness.
- [x] 10.7 Preserve compatible scalar profile payloads and retain incompatible payloads unchanged as explicitly unavailable.
- [x] 10.8 Keep missing external providers unresolved and never substitute, alias, migrate, or rebind `builtin:keyboard`.
- [x] 10.9 Add restart and reconciliation tests covering update, rollback, removal, reinstall, provider disappearance, configuration replacement, and process restoration.

## 11. External Keyboard package and fixtures

- [x] 11.1 Create the official `nilp0inter/keyboard-channel` source repository without Android, Kotlin, Sleepwalker, BLE, HID, or host-private dependencies.
- [x] 11.2 Resolve and record the repository's positive immutable GitHub database ID for the manifest `repositoryId`.
- [x] 11.3 Author a strict v1 manifest declaring dynamic `host_profile`, default `linux:us`, source `keyboard-output-profiles`, capabilities `audio.transcription` and `keyboard.output`, and no mounts.
- [x] 11.4 Implement startup with exact detached configuration validation and no host effect.
- [x] 11.5 Implement readiness using required dynamic-reference state and only `keyboard.output` availability/preparation, deliberately excluding transcription from admission readiness.
- [x] 11.6 Implement input transcription over opaque recording userdata and reject unavailable, failed, malformed, or empty results without output.
- [x] 11.7 Append exactly one ASCII space only when nonempty transcript text does not already end in ASCII space.
- [x] 11.8 Deliver the transcript once through `subspace.keyboard_output.send_text` using the instance profile and return success only for `delivered`.
- [x] 11.9 Implement yielded SOS by sending exactly one semantic `enter` through the configured profile and return success only for `delivered`.
- [x] 11.10 Map rejected, failed, indeterminate, cancelled, timed-out, and malformed host results to bounded application failures without text, transport detail, or replay.
- [x] 11.11 Add deterministic package-local tests for configuration, readiness, transcription, trailing spaces, every delivery outcome, SOS, malformed events, and no-replay behavior.
- [x] 11.12 Build deterministic source-only `subspace-channel.zip` bytes and verify repeated clean builds are byte-identical.
- [x] 11.13 Add an exact-byte app fixture produced from the same package source and validate its size and SHA-256 in tests.

## 12. App contract and coexistence verification

- [x] 12.1 Run all existing Lua package-format, validator, store, materializer, provider, actor, runtime, capability, and registry tests and resolve only real contract regressions.
- [x] 12.2 Prove existing external Debug, Diagnostics, and Journal packages still validate, install, restore, update, execute, and remove under the evolved pre-release v1 contract.
- [x] 12.3 Add JVM end-to-end tests from candidate archive bytes through validator, immutable store, materializer, provider registry, catalogue, runtime registry, actor, and fake keyboard capability.
- [x] 12.4 Verify available output accepts input without preparation and recoverable output performs one preparation before acceptance.
- [x] 12.5 Verify failed, timed-out, cancelled, released, or stale preparation produces no accepted target, beep, capture, transcription, or output.
- [x] 12.6 Verify successful capture transcribes, applies exact trailing-space policy, uses the configured profile, and waits for delivered acknowledgement.
- [x] 12.7 Verify transcription and all non-delivered output outcomes fail without replay or text leakage.
- [x] 12.8 Verify SOS delivers exactly one Enter and generation replacement suppresses late continuation and duplicate output.
- [x] 12.9 Verify two external instances retain independent profiles, states, generations, readiness, outcomes, and lifecycle cleanup.
- [x] 12.10 Verify built-in and external Keyboard instances coexist, preserve active selection and catalogue definitions, and serialize through one shared output facility.
- [x] 12.11 Verify package installation creates no automatic instance, copies no built-in configuration, and contains no repository-identity or Keyboard-name branch.
- [x] 12.12 Run the complete Gradle test/build checks covering the changed permanent contracts.

## 13. Local production-path acceptance

- [x] 13.1 Validate the exact local candidate archive with the real package validator and resolved durable repository identity.
- [x] 13.2 Install the candidate into app-private immutable storage through the same transaction used by production package installation.
- [x] 13.3 Create and configure an external Keyboard instance explicitly through the normal catalogue/editor path.
- [x] 13.4 Exercise restoration, selection, readiness, preflight, capture, transcription, keyboard delivery, SOS, restart, update, rollback, removal, and reinstall through the production provider/runtime path.
- [x] 13.5 Inspect runtime and host diagnostics to prove bounded identity/phase/outcome data is present and keyboard content or transport identity is absent.
- [x] 13.6 Confirm no Lua state, preparation, connection, or output operation occurs during archive validation, storage, provider inspection, registration, or editor rendering.

## 14. Public release and installed acceptance

- [x] 14.1 Publish immutable `v1.0.0` from `nilp0inter/keyboard-channel` only after the exact local candidate passes production-path acceptance.
- [x] 14.2 Resolve the public release and asset anonymously and record exact repository ID, release ID/tag, asset ID/name, byte size, SHA-256, and UTC timestamp.
- [x] 14.3 Download the public asset and prove its bytes exactly match the locally accepted deterministic candidate.
- [x] 14.4 Install the downloaded asset through the app's production GitHub package-management path without bundled bytes or a fixture shortcut.
- [x] 14.5 Repeat instance creation, independent profile configuration, PTT, SOS, restart, update/rollback eligibility, removal, and reinstall acceptance with the public artifact.
- [x] 14.6 Verify package replacement or removal revokes predecessor keyboard authority and preserves unresolved catalogue definitions without built-in substitution.
- [x] 14.7 Record public acceptance evidence without transcript text, keys, logical profiles, hardware addresses, acknowledgements, or transport details.

## 15. Physical device acceptance and final cleanup

- [x] 15.1 Install and launch the debug app on `B02PTT-FF01` through the repository devshell and production package-management UI.
- [x] 15.2 Pair Sleepwalker and verify an already connected output path reports ready without another preparation attempt.
- [x] 15.3 Disconnect Sleepwalker and verify one PTT press performs bounded preparation before the ready beep and audio capture.
- [x] 15.4 Release PTT during preparation and verify no later beep, capture, transcription, or keyboard output occurs.
- [x] 15.5 Verify successful speech reaches the paired computer with exact trailing-space behavior using at least two distinct logical host profiles.
- [x] 15.6 Verify SOS emits exactly one Enter through the selected external instance profile.
- [x] 15.7 Exercise built-in and external instances side by side and verify shared transport serialization without cross-instance status or cleanup.
- [x] 15.8 Exercise external package update, rollback, removal, reinstall, app restart, and foreground-service shutdown on device.
- [x] 15.9 Run final targeted and full repository verification after physical acceptance.
- [x] 15.10 Update `CHANGELOG.md` with the general keyboard-output API, dynamic choices, readiness preparation, yielded SOS, and external Keyboard package.
- [x] 15.11 Confirm production app sources contain no bundled external Keyboard archive, automatic installer, repository special case, or compatibility shim.
- [x] 15.12 Preserve `builtin:keyboard` unchanged and record its later removal and transport-state generalization as a separate OpenSpec change.

## 16. Bounded host-profile hierarchy correction

- [x] 16.1 Revise proposal, design, and delta specs to replace the unusable flat 1,061-profile publication with bounded dependent platform, layout, and final-profile selectors.
- [x] 16.2 Extend strict package UI declarations, immutable storage, and provider materialization with an optional acyclic earlier-string `dependsOn` field.
- [x] 16.3 Register bounded `keyboard-output-platforms`, dependent `keyboard-output-layouts`, and dependent `keyboard-output-profiles` sources without truncating valid choices.
- [x] 16.4 Preserve passive unavailable scalars, clear transitive dependents on explicit parent edits, and cover missing, stale, and changed parent selections.
- [x] 16.5 Update the external Keyboard package to persist platform, layout, and final-profile scalars and use only the final profile for output.
- [x] 16.6 Add focused package, validator, store, materializer, source, editor, readiness, and runtime contract tests for the hierarchy.
- [x] 16.7 Run targeted Gradle and package-local verification plus the final app build.
- [x] 16.8 Publish immutable external Keyboard `v1.0.1` and prove its asset bytes and provenance.
- [x] 16.9 Update the installed package on `B02PTT-FF01`, create an instance through all three selectors, and verify the configured card becomes available.
- [x] 16.10 Resolve stable lowercase profile IDs back to canonical keymap database objects so imported macOS and Windows metadata casing cannot cause `INVALID_PROFILE`.
- [x] 16.11 Project each Lua readiness refresh into host-visible available, recoverable, or unavailable runtime state and cover phone/car catalogue status.
