## Context

Subspace now has a production installed-package path:

```text
exact GitHub release asset
    → PackageValidator
    → immutable InstalledPackageStore revision
    → LuaPackageMaterializer
    → repository-derived LuaChannelImplementationProvider
    → ChannelRuntimeRegistry generation
    → LuaAdapterRuntime
    → one isolated Lua actor/state
```

The current external Debug and Journal packages prove configuration, capabilities, opaque audio, mounted resources, yielded host operations, managed tasks, and update/replacement. The current built-in Keyboard path remains Kotlin:

```text
BuiltInChannelDescriptors.keyboard
    → KeyboardBuiltInProvider
    → KeyboardRuntime
    → TranscriptionCapability
    → TextOutputCapability
    → SleepwalkerTextOutputService
    → keymap + BLE/GATT/HID
```

The lower half is already the right host boundary. `SleepwalkerTextOutputService` accepts semantic text or constrained keys plus `TextOutputProfile`, validates profiles, compiles keymaps, serializes with an operation mutex, prepares one shared connection, returns typed `Delivered`/`Rejected`/`Failed`/`Indeterminate` outcomes, and owns cleanup. The channel-specific policy is concentrated in `KeyboardRuntime`: readiness depends on recoverable text output, input transcribes and appends a trailing space, and SOS emits Enter.

The current Lua contract cannot express this channel without Kotlin help:

- package capability IDs do not include keyboard output;
- package configuration supports only static controls even though the host already has `DynamicConfigurationChoiceSource.TEXT_OUTPUT_PROFILES`;
- `LuaPackageMaterializer` cannot compile dynamic choices and marks every Lua descriptor as not supporting recoverable preparation;
- Lua readiness returns only `ready` and optional `status`;
- `LuaAdapterRuntime.prepareInput()` refuses cached not-ready state and cannot run host preparation before the ready beep/capture;
- `handle_sos` is synchronous and rejects yielded operations;
- no reserved Lua module maps typed text/key requests to `TextOutputCapability`;
- the shared output mutex has no explicit bounded waiter admission suitable for generation-authorized proactive plugins.

This change is an API-enablement and side-by-side evaluation leg. It does not remove `builtin:keyboard`. The external package must be an ordinary client of general public contracts. If its repository and Lua source vanished, the remaining host APIs must still make sense for unrelated plugins.

## Goals / Non-Goals

**Goals:**

- Add general public capability `keyboard.output` and module `subspace.keyboard_output` for future Lua developers.
- Preserve host ownership of profiles, keymaps, connection preparation, transport, acknowledgement, serialization, and cleanup.
- Support semantic text and bounded semantic key operations without exposing raw HID/BLE/GATT values.
- Authorize keyboard output from input, yielded SOS, and managed-task owners of any live declared generation, independent of active channel selection.
- Add generic bounded dependent dynamic scalar choices, including keyboard platform, layout, and final-profile sources.
- Add generic readiness-declared capability preparation that completes before input commitment, ready beep, or capture.
- Make SOS a bounded yield-capable callback while preserving existing synchronous SOS packages.
- Extend the typed host-operation broker and actor continuation model rather than adding a second JNI path.
- Build, validate, publish, install, and exercise an external Keyboard package containing all Keyboard policy in Lua.
- Retain built-in Keyboard unchanged as a side-by-side baseline and share one host-owned Sleepwalker transport safely.
- Keep existing Debug, Diagnostics, Journal, and built-in providers operational through the evolved pre-release v1.

**Non-Goals:**

- No removal, deprecation, alias, or behavior change for `builtin:keyboard` in this leg.
- No automatic package installation, bundled external source/archive, automatic instance creation, configuration copying, identity rebinding, or active-selection change.
- No API v2, compatibility range, legacy parser/callback shim, deprecated name, or parallel API generation.
- No raw Android key events, USB usages, modifiers, chords, arbitrary HID scripts, mouse, clipboard, arm/disarm/kill, BLE/GATT, acknowledgement-token, or device-address API.
- No Keyboard package identity, label, repository, configuration, readiness, trailing-space, transcription, or SOS policy in Kotlin.
- No requirement that proactive keyboard output be selected at execution time; installation/configuration and a live declared generation are the authority.
- No OpenAI Agent migration, built-in Journal removal, plugin signing/attestation, discovery, or update-policy expansion.
- No broad plugin-controlled status API or transcript publication in runtime summaries.
- No final cleanup of legacy names such as `KeyboardConnectionState`; that belongs to the later built-in-removal leg.

## Decisions

### D1. Use public `keyboard.output` and `subspace.keyboard_output`

The public manifest capability is `keyboard.output`; readiness and preparation use the same ID. The reserved module is `subspace.keyboard_output`.

```lua
local keyboard_output = require("subspace.keyboard_output")
```

The public API names the reusable semantic facility, not the external Keyboard Channel package. The host compiles `keyboard.output` to the existing internal `ChannelCapability.TextOutput`/`TextOutputCapability`. Those internal names are already channel-neutral and allow built-in and Lua clients to share the same service during evaluation. Renaming internal contracts merely to mirror public spelling would add churn without improving isolation.

**Alternative considered:** retain public `text.output`/`subspace.text_output`. Rejected by product naming decision; keyboard output communicates the user-visible effect and still remains generic across plugins.

**Alternative considered:** add a Keyboard-package-specific host module. Rejected because it would embed transcription, trailing-space, readiness, or SOS policy in Kotlin and would not serve future plugin authors.

### D2. Expose semantic text and constrained key requests

The module exposes:

```lua
send_text({ text = <bounded UTF-8>, profile = <logical ID> })
send_key({ key = "enter" | "escape", profile = <logical ID> })
```

`enter` and `escape` mirror the existing semantic `TextOutputKey` vocabulary. A host adapter may return a policy rejection for a semantic key it cannot currently render; accepting the public enum does not promise every backend can deliver every key. Adding future semantic keys evolves the same bounded enum.

Arguments are exact-key tables. Native validation checks types, UTF-8, lengths, exact keys, execution context, and request capacity before yielding. Kotlin rechecks manifest eligibility, generation ownership, live profile, capability availability, and host queue capacity before effect.

The semantic operation returns one exact result table on terminal delivery:

```lua
{ status = "delivered" }
{ status = "rejected", reason = <stable reason> }
{ status = "failed", reason = <stable reason> }
{ status = "indeterminate", reason = <stable reason> }
```

The second Lua return value is nil for these completed semantic outcomes. Contract, context, ownership, declaration, availability-before-admission, capacity, closed, and stale failures use the established `(nil, {error = <stable code>, reason? = ...})` convention. Only `status == "delivered"` is Keyboard input/SOS success. Operation and acknowledgement IDs remain host-only.

**Alternative considered:** expose compiled operations or arbitrary keys. Rejected because plugins would bind to HID/keymap details and bypass host safety policy.

**Alternative considered:** return non-delivered semantic outcomes only as generic Lua errors. Rejected because `rejected`, `failed`, and especially `indeterminate` are part of the non-replay contract and must remain explicit.

### D3. Resolve logical profile IDs through a dependent hierarchy

Manifest UI gains a `dynamic-choice` control with exact `source` and optional `dependsOn` field ID. A dependency must name an earlier unconstrained string field, so the declaration graph is acyclic and every resolver still receives at most one bounded scalar dependency.

The external Keyboard package uses three fields:

```json
[
  {
    "field": "host_os",
    "control": "dynamic-choice",
    "source": "keyboard-output-platforms",
    "label": "Host platform"
  },
  {
    "field": "host_layout",
    "control": "dynamic-choice",
    "source": "keyboard-output-layouts",
    "dependsOn": "host_os",
    "label": "Keyboard layout"
  },
  {
    "field": "host_profile",
    "control": "dynamic-choice",
    "source": "keyboard-output-profiles",
    "dependsOn": "host_layout",
    "label": "Host profile"
  }
]
```

The platform source publishes stable platform IDs. The layout source receives the selected platform and publishes stable `platform:layout` IDs. The final profile source receives that layout ID and publishes exact complete profile IDs such as `linux:us` or `linux:us:dvorak`. Publications use the shared 256-choice bound: production resource analysis found 96 Linux, 247 macOS, and 216 Windows layouts, while the largest final-profile slice contains 44 profiles and the flat catalogue remains over one thousand.

Validation accepts only supported public source IDs, validates an acyclic earlier-field dependency, and does not resolve sources, execute Lua, or access host profile objects. Materialization compiles the declaration to generic `DynamicChoiceField` metadata. The catalogue and detached startup snapshot retain only the three scalar IDs; only `host_profile` is used for output. Runtime readiness projects reference state for every required dynamic field, and every output operation still revalidates the final profile in `SleepwalkerTextOutputService`.

The editor preserves persisted scalars when a source passively becomes unavailable. An explicit parent edit is different user intent: it clears every transitive dependent scalar before resolving the new child choices, preventing stale layout/profile IDs from being presented as the current selection.

**Alternative considered:** one flat `keyboard-output-profiles` publication. Rejected because the actual catalogue exceeds the defensive 256-choice publication bound and a four-figure dropdown is not usable.

**Alternative considered:** retain the original 64-choice bound or the provisional 128 correction. Rejected after production-device acceptance and complete AAPT resource parsing proved both lower bounds reject valid platform slices; 256 remains bounded, admits every measured platform/layout slice, and still rejects the flat profile catalogue all-or-nothing.

**Alternative considered:** persist a resource binding and opaque profile handle like a directory mount. Rejected because a logical profile ID is neither authority nor a platform grant; a separate binding lifecycle would be disproportionate.

### D4. Readiness declares preparation; Lua does not perform it

Readiness result gains optional exact `prepare`:

```lua
{
  ready = false,
  status = "Keyboard unavailable",
  prepare = { "keyboard.output" },
}
```

The list is bounded, duplicate-free, and contains only declared capabilities registered by the host as preparable. A nonempty list with `ready = true` is malformed. Invalid readiness caches not-ready with no preparation.

`LuaAdapterRuntime` caches readiness and the preparation request atomically. `prepareInput()` behaves as follows:

```text
closed/disabled/not activated/no handle_input → existing refusal
cached ready=true                         → accept immediately
cached ready=false + no prepare           → refuse
cached ready=false + prepare              → invoke generic preparers sequentially
                                            under PREPARE_INPUT deadline
                                          → refresh readiness once
                                          → accept only if ready=true
```

The generic preparer registry maps public IDs to preparation adapters. `keyboard.output` maps to `ChannelCapabilityScope.acquire(TextOutput, PrepareRecoverable(host timeout))`, releases the acquired lease immediately after proof, and lets later operations acquire their own leases. The host owns joining the existing shared Sleepwalker preparation, cancellation of the input attempt, and stale-result suppression.

PTT release/cancellation while preparation is pending terminates the attempt. Later preparation completion may improve global availability but cannot create an accepted target, ready beep, or capture for that attempt.

**Alternative considered:** return `ready=true` for recoverable state and prepare all declared capabilities implicitly. Rejected because readiness would be false and optional/configuration-dependent capabilities would cause unwanted work.

**Alternative considered:** add a yielding `handle_prepare` callback. Rejected because Lua does not choose transport preparation policy and an additional effect callback enlarges cancellation/state semantics unnecessarily.

### D5. Make SOS a managed yield-capable callback

`handle_sos` runs as a host-managed coroutine and returns exactly `{ok=true}` or the existing exact application failure object. It may yield only through APIs that explicitly admit an SOS execution owner. This change initially admits `subspace.keyboard_output`; raw yield, sleep, spawn, and defer remain invalid.

The actor broker creates an SOS owner and routes chained yielded operations through the same typed request/continuation path used by input and managed tasks. `HANDLE_SOS` keeps a bounded host phase deadline. Success/failure remains locally contained; it does not become a new public durable message or status channel. Existing synchronous Diagnostics-style callbacks remain valid because a coroutine that never yields completes in one slice.

**Alternative considered:** enqueue Enter asynchronously and let synchronous SOS return immediately. Rejected because it loses exact terminal ownership, complicates revocation, and can emit late unobserved keys.

**Alternative considered:** ask SOS to spawn a managed task. Rejected because spawn is intentionally unavailable outside startup/managed tasks and would obscure the user action's terminal boundary.

### D6. Authorize input, SOS, and managed tasks by generation, not selection

A valid keyboard-output call must belong to one live input owner, SOS owner, or runtime-managed task owner and one instance/generation capability scope. Current active channel selection is irrelevant. An unselected proactive plugin remains live under the existing runtime model, so ordinary channel switching does not cancel or redirect its output.

Authority is revoked on disablement, configuration replacement, package update/rollback/removal, runtime failure/close, or service shutdown. Every queued and active operation retains instance/generation/owner identity. A predecessor completion never resumes a successor or selected sibling.

This intentionally permits installed/configured proactive packages to type while unselected. The trusted plugin model and explicit capability declaration are the authority boundary; there is no second proactive capability in this pre-release contract.

**Alternative considered:** require current selection for every operation. Rejected because asynchronous Agent/proactive plugins would become timing-dependent and ordinary selection changes would create surprising failures.

**Alternative considered:** separate interactive and proactive capabilities. Rejected because no concrete trust model requires that distinction yet and it would complicate a still-evolving v1.

### D7. Extend the existing typed operation broker

Rust/native gains typed request kinds for keyboard text and semantic keys. Validation stores bounded request fields in the state-local host-request registry and yields only an opaque request ID. Yield labels never contain text, profile, key, JSON, transport data, or token material. Kotlin claims the request exactly once, checks the expected owner, and dispatches it through `LuaAdapterRuntime` to the revocable capability scope.

The runtime supports these owners:

```text
Input(owner UUID, generation)
SOS(owner UUID, generation)
ManagedTask(coroutine/task identity, generation)
```

A host operation terminal gate races completion, operation deadline, owner cancellation, and generation close. Live input/SOS owners may resume once with a normalized terminal. Managed-task cancellation and generation close discard the coroutine without Lua re-entry while the host still terminalizes and cleans a possibly effective physical operation.

No separate JNI bridge, serialized JSON request, Kotlin callback registry, or Keyboard-specific actor path is introduced.

### D8. Add bounded shared output admission before the existing mutex

`SleepwalkerTextOutputService.operationMutex` serializes physical delivery but coroutine mutex waiters are not an explicit bounded admission contract. General proactive plugins require finite admission.

Add a host-owned bounded admission layer around the existing service operation path. It tracks queued/admitted entries by instance, generation, execution owner, payload bytes, and FIFO sequence. Bounds apply per instance, generation, and process. Admission failure returns busy before compilation, connection, arm, or physical output. The active physical delivery remains one-at-a-time under the service mutex and existing delivery timeout/cleanup.

Built-in `TextOutputCapability` clients and the Lua adapter use the same coordinator, so side-by-side instances cannot bypass ordering or quotas. Fairness is deterministic global FIFO among admitted entries plus finite per-instance admission; one producer cannot allocate unbounded entries. Selection does not reorder the queue.

Queued entries revoked before physical admission are effect-not-begun. Active cancellation after output may have begun uses the existing typed failed/indeterminate discipline. No automatic replay occurs.

**Alternative considered:** rely solely on `Mutex`. Rejected because it admits an unbounded coroutine waiter set and lacks generation-addressable queued revocation.

**Alternative considered:** create a Lua-only queue. Rejected because built-in and future native clients would bypass it and physical ownership would split.

### D9. Keep content and transport out of observability

Host operation diagnostics record capability, instance, generation, owner kind, queue/admission phase, bounded text length or semantic key category, and normalized terminal outcome. They never record text, compiled plans, profile IDs when sensitive, device addresses, GATT objects, sequence acknowledgements, or yielded request payloads. Lua cannot serialize or log a host request object because only normalized arguments/results cross its public boundary.

The external Keyboard package does not log transcripts. Acceptance evidence records lengths/outcomes and observes physical typed results on the paired target rather than copying content into general diagnostics.

### D10. Implement the external Keyboard package with no host special case

The package manifest declares:

```text
configuration.host_os:
  string, default linux
  UI dynamic-choice source keyboard-output-platforms
configuration.host_layout:
  string, default linux:us
  UI dynamic-choice source keyboard-output-layouts, depends on host_os
configuration.host_profile:
  string, default linux:us
  UI dynamic-choice source keyboard-output-profiles, depends on host_layout
capabilities:
  audio.transcription
  keyboard.output
resources.mounts: []
```

Lua owns:

- detached configuration validation;
- readiness reference/capability checks;
- `prepare={"keyboard.output"}` only for recoverable output;
- deliberate exclusion of transcription availability from admission readiness;
- input transcription;
- empty/malformed transcript rejection;
- one trailing ASCII space only when absent;
- delivery success only for `status="delivered"`;
- no replay for any terminal outcome;
- SOS validation and one semantic Enter operation.

The package uses `subspace.transcription`, `subspace.keyboard_output`, and `subspace.channel` only. It does not inspect Sleepwalker state, own shared transport, retain audio beyond the input owner, or know internal capability types.

The repository is `nilp0inter/keyboard-channel`. Its resolved immutable repository database ID becomes the provider identity. Candidate bytes are deterministic and source-only and pass the real validator/install/materialization/runtime path before publication. The app ships no package bytes or special installer branch.

### D11. Retain built-in Keyboard for explicit side-by-side evaluation

This change does not remove or alter `KeyboardBuiltInProvider`, `KeyboardRuntime`, built-in descriptor/configuration, seed, active behavior, or tests. Installing the external provider does not mutate any `builtin:keyboard` definition or copy its profile. The user explicitly creates and names external instances.

Both built-in and external runtimes acquire the same generic host output capability and therefore share preparation, admission, transport, and cleanup. Evaluation covers multiple external instances with distinct profiles plus the built-in baseline.

The later removal leg will delete the built-in provider/runtime/configuration/ID/seed and implementation-specific tests, preserve old definitions through the generic missing-provider path, and rename remaining channel-shaped monitor state such as `KeyboardConnectionState` where appropriate. That future leg must not change this public Lua API.

**Alternative considered:** remove built-in Keyboard atomically. Rejected for this leg because side-by-side hardware comparison is deliberate and the app is pre-production, so temporary duplicate functionality is acceptable.

### D12. Evolve pre-release v1 additively without compatibility machinery

The exact API string remains `subspace-lua-v1`. The capability allowlist, accepted UI control/source vocabulary, readiness result, SOS yield context, and reserved module set evolve directly. Existing package manifests remain structurally valid because the new capability/control/source are additional permitted values and `prepare` is optional. Existing synchronous SOS and readiness results remain valid. Debug, Diagnostics, and Journal fixtures must pass unchanged behavior under the evolved host; they need publication updates only if their exact artifacts become invalid during implementation, not as an invented compatibility exercise.

No v2, feature negotiation, fallback parser, callback retry, deprecated module alias, or dual execution path is added.

## Risks / Trade-offs

- **[Risk] An installed proactive plugin types while unselected.** → Treat manifest declaration plus live generation as explicit authority, retain host bounds/revocation, keep package installation user-driven, and document selection independence; do not pretend selection is an authority boundary.
- **[Risk] A malicious or buggy task floods the output queue.** → Enforce per-instance, generation, and process count/byte bounds before admission, deterministic FIFO, operation deadlines, and monotonic generation revocation.
- **[Risk] Cancellation occurs after partial text output.** → Preserve `indeterminate`, run safety cleanup exactly once, expose uncertainty to Lua, and prohibit automatic replay.
- **[Risk] Dynamic choice validity changes after edit.** → Resolve required references during readiness and revalidate profile at every operation; preserve scalar configuration for repair.
- **[Risk] Preparation success becomes stale before capture.** → Refresh readiness after preparation, bind acceptance to the current input attempt and generation, and let call-time authorization recheck availability.
- **[Risk] SOS yielding introduces actor continuation races.** → Reuse the typed request broker, owner tokens, serialized Lua entry, idempotent terminal gates, and bounded invocation phase rather than adding callback-specific continuation logic.
- **[Risk] Shared admission changes built-in timing.** → Put both built-in and Lua ports through the same focused coordinator, retain physical delivery semantics, and compare the existing built-in path side-by-side on hardware.
- **[Risk] Keyboard text leaks through native request plumbing or logs.** → Store payload only in bounded typed registries/host operations, yield opaque IDs, log lengths/outcomes only, and add adversarial logging/serialization checks.
- **[Risk] The public API overfits Sleepwalker.** → Keep only logical profiles, semantic text/keys, preparation state, and typed outcomes public; connection, keymap, HID, acknowledgement, and cleanup remain adapter details.
- **[Risk] Existing packages depend on SOS being synchronous.** → A non-yielding function completes identically in the managed coroutine; retain existing arguments and application-failure containment and run exact package fixtures.
- **[Trade-off] Built-in and external Keyboard entries duplicate user-visible functionality.** → Accepted for pre-production evaluation; provider/instance identities remain honest and independent.
- **[Trade-off] `escape` may be policy-rejected by the current Sleepwalker adapter.** → Keep the semantic API aligned with the existing host enum; backend support is represented by typed outcome rather than raw capability mutation.
- **[Trade-off] Internal Kotlin retains `TextOutput*` names while public Lua says keyboard output.** → Accepted because internal names are implementation-neutral and shared by existing clients; public terminology does not require a mechanical rename.

## Migration Plan

1. Extend package/configuration domains and strict validation with `keyboard.output`, dependent `dynamic-choice` metadata, and bounded keyboard platform/layout/profile sources; compile them without Lua execution and keep existing unrelated package fixtures valid.
2. Extract a generic dynamic-choice source registry from current service wiring, add detached runtime reference-status projection, and preserve OpenAI profile/model and editor behavior.
3. Add readiness `prepare`, generic preparer registration, cached preparation state, bounded `PREPARE_INPUT` execution, post-preparation refresh, and release/cancel/stale suppression before any beep or capture.
4. Evolve SOS to a host-managed yield-capable owner through the existing actor and invocation boundaries while preserving synchronous callbacks.
5. Add native typed keyboard-output requests and `subspace.keyboard_output`; map exact arguments, owner contexts, terminal outcomes, errors, deadlines, cancellation, and close through the existing broker.
6. Add bounded shared output admission ahead of `SleepwalkerTextOutputService` physical serialization and route both built-in and Lua `TextOutputCapability` clients through it.
7. Exercise generic APIs with anonymous fixture packages from input, SOS, managed tasks, deselected live generations, multiple instances, replacement, cancellation, timeout, queue saturation, and privacy adversaries.
8. Create `nilp0inter/keyboard-channel`, resolve its repository ID, implement the Lua package, assemble deterministic local bytes, and prove exact behavior through ordinary package installation/materialization before publication.
9. Publish immutable Keyboard `v1.0.0`, record exact provenance, anonymously resolve/download/install it, and create instances manually while leaving `builtin:keyboard` unchanged.
10. Run side-by-side automated and physical acceptance with distinct profiles, disconnected preparation, successful text plus trailing space, SOS Enter, failure/indeterminate non-replay, proactive output while unselected, generation replacement, shared serialization, and no cross-instance effects.
11. Inspect debug and release-equivalent APKs for the generic API/runtime only and prove absence of external Keyboard bytes, special provider/installer branches, added raw hardware surface, aliases, or compatibility dispatch.

Rollback before app publication means reverting the complete host change; published source-only package assets remain immutable historical artifacts. After app publication, rolling back to an older app may leave the external provider definition and installed bytes preserved but unavailable to that older exact API. Package rollback uses only another revision valid under the current host and never substitutes or mutates `builtin:keyboard`.

## Open Questions

None. The side-by-side boundary, general API purpose, public names, dynamic scalar profile model, readiness-declared preparation, SOS yielding, input/SOS/managed-task contexts, generation-not-selection authorization, typed outcomes, shared bounds, host ownership, external publication path, and later built-in-removal boundary are fixed by this design.
