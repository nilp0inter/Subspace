## Context

The completed pre-release plugin stack already has one production path for external Lua code:

```text
exact GitHub release asset bytes
    → PackageValidator
    → ValidatedPackageRevision + SHA-256 revision
    → immutable InstalledPackageStore/index
    → LuaPackageMaterializer
    → package-specific LuaChannelImplementationProvider
    → ChannelImplementationProviderRegistry installed snapshot
    → ChannelRuntimeRegistry generation
    → LuaAdapterRuntime
    → one generation-owned Lua actor/state
```

The external Diagnostics package proves that path, including exact-byte inspection, installation, update, rollback, provider publication, independent instances, unselected timer work, structured logging, restart, and removal. It intentionally uses an empty configuration object and no host capabilities.

The product Debug Channel still follows a separate built-in path:

```text
BuiltInChannelDescriptors.debug
    → DebugBuiltInProvider
    → DebugRuntime
    → internal ChannelCapabilityScope operations
```

`DebugRuntime` has five modes. `ECHO` and `DELAYED_ECHO` convert terminal capture into host-owned deferred playback; `STT` transcribes without playback; `TTS` synthesizes fixed diagnostic speech and schedules playback; `STT_TTS` transcribes, synthesizes the transcript, and schedules playback. The host owns capture, route selection, half-duplex admission, playback, selection policy, cancellation, and cleanup.

The current Lua/provider contract cannot express that behavior:

- package manifest v1 has no configuration or capability declarations;
- `LuaPackageMaterializer` constructs `LuaChannelImplementationProvider` with `LuaEmptyConfigurationProvider`, no fields, and no required capabilities;
- mandatory `startup()` receives no configuration;
- `handle_input` receives session identity and bounded scalar metadata but no captured audio;
- all event callbacks are synchronous and non-yielding;
- no Lua module adapts transcription, synthesis, or deferred playback;
- opaque host audio values cannot cross the current normalized Lua-data boundary;
- the application still registers `DebugBuiltInProvider` during foreground-service composition.

Lua API v1, package-format v1, and the published Diagnostics artifacts are development-stage contracts, not released compatibility promises. This change therefore evolves the single v1 contract directly and moves every current caller and development package through one clean cutover. It does not create v2 or preserve the intermediate empty-provider and metadata-only callback shapes.

The external Debug package will be published from `nilp0inter/debug-channel`. The configured Official publisher remains GitHub owner database ID `1224006`. The repository's durable positive database ID is resolved after repository creation and becomes the package/provider identity; it is not invented by this design.

## Goals / Non-Goals

**Goals:**

- Evolve the single unreleased manifest and Lua API v1 contracts through one fail-closed clean cutover.
- Add a bounded, declarative, statically validated configuration contract that compiles into the existing generic provider/editor/catalogue pipeline.
- Preserve independent configuration, state, handles, tasks, readiness, and status for multiple instances of one installed provider.
- Add stable public semantic capability identifiers without exposing Kotlin capability keys or host implementation classes.
- Deliver captured and synthesized audio to Lua as unforgeable, non-serializable, generation-owned opaque values.
- Add yielding transcription, synthesis, and selection-aware deferred-playback operations with typed errors, cancellation, revocation, and late-effect suppression.
- Reproduce all five current Debug modes, their default, mode-dependent readiness, status, fixed constants, delayed eligibility, and host-routed playback behavior in one external Lua package.
- Exercise only generic GitHub package installation, provider publication, catalogue creation, runtime reconciliation, and host capability paths; ship no Debug package or special provider inside the APK.
- Remove the built-in Debug provider, implementation identifier, descriptor, runtime, registration, legacy seed, configuration model, specialized UI, and tests that defend a Kotlin path.
- Republish Diagnostics against the evolved v1 contract while retaining its empty configuration and no-capability behavior.
- Prove package, configuration, actor, capability, routing, and clean-cutover behavior with layered automated checks and physical Work, On-a-pinch, and On-the-road evidence.

**Non-Goals:**

- No API v2, compatibility ranges, feature negotiation, optional legacy parser, alias, deprecated callback, or simultaneous API generation.
- No built-in Debug fallback, feature flag, parallel Debug provider, bundled Debug source, automatic package installation, or automatic instance creation.
- No automatic rebinding, payload copying, ID reuse, or active-selection migration from `builtin:debug` to the installed GitHub provider.
- No arbitrary JSON Schema dialect, nested objects, arrays, optional fields, cross-field expressions, Lua-defined validation, or Lua-defined configuration migration.
- No dynamic host-resource choice, directory reference, secret field, credential, profile/model reference, or sensitive-value UI.
- No general HTTP, JSON, filesystem, socket, database, event-loop, package-writable state, cache, or temporary-directory API.
- No durable channel message/run API, unsolicited output policy, RSM custom menu, phone/car custom control declaration, or foreground-service-retention revision.
- No automatic updates, topic/website discovery, QR/App Link flow, prerelease installation, package signing, attestation, or transparency-log claim.
- No Journal, Keyboard, or OpenAI Agent migration.

## Decisions

### D1. Evolve one pre-release v1 contract by clean cutover

The evolved package keeps:

```text
manifestVersion   = 1
runtime.luaVersion = "Lua 5.4"
runtime.apiVersion = "subspace-lua-v1"
```

The revised manifest members, callback signatures, input shape, module set, and value rules are mandatory for every accepted v1 artifact. The host rejects an old artifact that omits the newly required manifest members before storing, registering, or executing it. All in-tree package fixtures and the Diagnostics development package are rebuilt. There is no parser default that treats an omitted declaration as an empty declaration.

The installed-package index is a host cache, not an accepted package artifact. During this cutover, the index decoder MAY recognize only the exact pre-cutover cached-manifest shape whose otherwise-valid manifest omits both `configuration` and `capabilities`. It marks that revision as untrusted legacy state, preserves the omissions when rewriting the index, and requires exact stored archive bytes to be rehashed and reparsed by the evolved validator before any materialization. The old archive therefore remains unavailable and non-executable, but its repository/source/digest record remains addressable so an explicit update can atomically replace it with a fully validated evolved revision. Missing only one declaration, unknown keys, malformed identity/source/digest data, and every other index deviation still fail closed.

The public v1 surface is still one target contract under construction. Archived changes retain their value as verified development stages, but do not require runtime compatibility with the superseded intermediate stage.

**Alternative considered:** introduce `subspace-lua-v2` and retain v1. Rejected because v1 has not shipped and no existing product channel has migrated; parallel version dispatch would preserve development scaffolding rather than a released contract.

**Alternative considered:** accept both `startup()` and `startup(configuration)`, or both metadata-only and audio-bearing input events. Rejected because optional legacy shapes spread compatibility branches through validation, callbacks, tests, and packages.

### D2. Manifest configuration is a flat bounded data declaration plus a separate UI declaration

Manifest v1 gains two required root members: `configuration` and `capabilities`. The Debug declaration has this logical shape:

```json
{
  "configuration": {
    "schemaVersion": 1,
    "data": {
      "additionalProperties": false,
      "fields": [
        {
          "id": "mode",
          "type": "string",
          "default": "ECHO",
          "allowedValues": [
            "ECHO",
            "DELAYED_ECHO",
            "STT",
            "TTS",
            "STT_TTS"
          ]
        }
      ]
    },
    "ui": {
      "fields": [
        {
          "field": "mode",
          "control": "choice",
          "label": "Mode",
          "choices": [
            { "value": "ECHO", "label": "ECHO" },
            { "value": "DELAYED_ECHO", "label": "DELAYED_ECHO" },
            { "value": "STT", "label": "STT" },
            { "value": "TTS", "label": "TTS" },
            { "value": "STT_TTS", "label": "STT_TTS" }
          ]
        }
      ]
    }
  },
  "capabilities": [
    "audio.transcription",
    "audio.synthesis",
    "audio.playback"
  ]
}
```

This is a Subspace package declaration, not JSON Schema and not a serialization of Kotlin sealed classes.

Data schema v1 supports only flat required scalar fields:

- `string` with a UTF-8 string default and optional nonempty `allowedValues`;
- `boolean` with a Boolean default;
- signed 64-bit `integer` with an integer default and optional inclusive `minimum`/`maximum`.

Every field has a default of exactly its declared type. `additionalProperties` is present and exactly `false`. Configuration payloads contain every declared field and no undeclared field. No coercion occurs between JSON strings, booleans, integers, floating-point numbers, or null.

UI schema v1 supports exactly:

- `text` for an unconstrained string;
- `toggle` for a Boolean;
- `number` for an integer;
- `choice` for a string with `allowedValues`.

Every data field appears exactly once in `ui.fields`; the array defines render order. Each UI entry contains a canonical `field`, a nonblank bounded `label`, an optional bounded `help`, and a type-compatible `control`. A `choice` entry contains one label for every allowed value, in the same set, with no duplicate value. Data and UI field identifiers match `[a-z][a-z0-9_]*`.

Empty `data.fields`, empty `ui.fields`, and empty `capabilities` are valid and compile to the Diagnostics `{}` payload. Omission is invalid.

Validation is bounded by host policy. Initial implementation bounds are at most 32 fields, 64 UTF-8 bytes per ID, 128 bytes per label, 512 bytes per help string, 64 values per choice field, 16 KiB per string value, and 64 KiB for the canonical serialized configuration object. The existing manifest-byte and complete-package limits remain authoritative. These numeric policy values are not Lua compatibility promises.

The parser rejects unknown declaration keys, duplicate IDs or values, blank or noncanonical IDs, dangling UI references, duplicate UI references, mismatched controls, invalid defaults, invalid ranges, incomplete choices, nulls, arrays, nested configuration values, dynamic choices, host-resource references, secret declarations, and unsupported schema versions.

**Alternative considered:** expose full JSON Schema. Rejected because dialect selection, reference resolution, composition, annotations, and partial-support semantics create substantially more compatibility surface than the first migration needs.

**Alternative considered:** serialize `ChannelConfigurationField` directly. Rejected because it would expose a Kotlin/Compose-oriented internal algebra and current hard-coded dynamic-choice sources as the package ABI.

### D3. Materialization compiles declarations into the existing provider contract without executing Lua

`PackageValidator` parses and validates configuration/capability declarations with the rest of the manifest and stores immutable host-domain values in `PackageManifest` and `ValidatedPackageRevision`. `InstalledPackageStore` persists the exact artifact and reparses the evolved manifest on materialization; it does not cache unchecked configuration models.

`LuaPackageMaterializer` compiles the validated declaration into:

- a package-specific `ChannelConfigurationProvider`;
- a host-domain `ChannelConfigurationField` list for the generic editor;
- one complete default `OpaqueJsonObject`;
- a public capability eligibility set compiled to internal capability requirements;
- the existing immutable program image and provider revision fingerprint.

The declarative configuration provider accepts only schema version `1`. It validates exact keys, exact JSON scalar types, allowed values, integer ranges, per-value bounds, and total payload bounds. It does not repair, fill, drop, rename, or coerce fields in a submitted or persisted payload. Default production is the only place defaults are assembled.

Configuration validation and migration perform no Lua execution, package module loading, network access, hardware access, Android access, or Compose work. No migration language is present in this change. A provider update whose revised declaration does not accept an existing payload makes the affected instance explicitly unavailable while preserving its exact stored payload. The installed package's retained rollback revision remains a manual user action; the host does not automatically roll back code or transform configuration.

`ServiceChannelManager`, `ChannelRepository`, `ChannelCatalogueCodec`, and `ChannelRuntimeRegistry` remain authoritative for generic creation/editing, lossless persistence, atomic commit, migration/validation, and runtime reconstruction. Package code does not receive a second editor or persistence path.

### D4. Lua receives one detached configuration snapshot at startup

The mandatory callback becomes:

```lua
startup = function(configuration)
    -- configuration = {
    --   schema_version = 1,
    --   values = { ...validated flat scalar values... }
    -- }
end
```

The host creates a fresh normalized Lua table for each runtime generation. It contains no metatable, provider object, `OpaqueJsonObject`, Kotlin object, Android value, or host reference. Lua may mutate its private copy, but mutation cannot alter persisted configuration, another instance, or a later generation.

Diagnostics receives `{schema_version=1, values={}}`. Debug captures its mode from `configuration.values.mode` during startup.

A configuration edit uses the existing atomic catalogue update. Runtime reconciliation stops predecessor admission, completes any committed terminal callback, drains/cancels descendants, revokes effects, closes the predecessor, and starts a fresh generation with a new detached snapshot. There is no live mutable configuration object, getter module, polling API, `on_configuration_changed`, or in-place actor mutation.

**Alternative considered:** a global `subspace.config` getter. Rejected because startup delivery naturally binds configuration to generation construction and avoids an ambient mutable-looking service.

**Alternative considered:** pass configuration to every callback. Rejected because callbacks can close over the startup snapshot and repeated conversion/copying has no semantic benefit.

### D5. Public package capability IDs are stable semantic names

This change defines exactly three public capability IDs:

```text
audio.transcription
audio.synthesis
audio.playback
```

Manifest parsing rejects unknown or duplicate IDs. Public names do not expose `CapabilityKey`, `ChannelCapability`, lease classes, ports, actor identities, Android resources, or routing objects.

Internal compilation is:

```text
audio.transcription
    → transcription eligibility/lease

audio.synthesis
    → synthesis eligibility/lease

audio.playback
    → audio-operation creation + deferred-playback eligibility/leases
```

The two internal mechanisms behind `audio.playback` are not separate public capabilities and may be reorganized without changing package manifests.

The compiled descriptor set is an eligibility allowlist. It does not assert that every declared capability is currently available or that every configuration mode needs every declared capability. A Lua call without the corresponding manifest declaration returns `E_CAPABILITY_UNDECLARED` before acquisition or effect.

### D6. Readiness receives a capability-availability snapshot

`handle_readiness` evolves to:

```lua
handle_readiness = function(context)
    -- context.capabilities[id] is exactly one of:
    -- "available", "recoverable", "unavailable"
    return { ready = true, status = "ECHO" }
end
```

The host computes the flat capability map immediately before each readiness callback using only declared public IDs. Internal failure classes, Android state, routes, clients, and diagnostic detail do not cross the boundary. The callback remains synchronous, non-yielding, and unable to spawn work.

The exact return shape is `{ready=<boolean>}` with optional bounded UTF-8 `status=<string>`. Unknown fields, wrong types, throw, yield, or malformed return fail closed to not-ready and record a bounded diagnostic. The optional status becomes `ChannelRuntimeSnapshot.summary`; omission retains an empty package-provided summary. `ChannelExecutionStatus` remains host-owned.

The host keeps the generation in initialising/not-ready state until authorized startup, lifecycle-ready delivery, capability snapshot creation, and the first readiness callback finish. Periodic refresh rebuilds the snapshot and invokes the callback again. Enabled/live host state is always combined with plugin readiness, and capability calls recheck authorization and availability even when Lua reports ready.

Debug declares all three capabilities but evaluates only its selected mode's dependency set:

```text
ECHO, DELAYED_ECHO  → audio.playback
STT                 → audio.transcription
TTS                 → audio.synthesis + audio.playback
STT_TTS             → all three
```

There is no recoverable Debug preparation in this change.

**Alternative considered:** treat every declared capability as universally required. Rejected because it would make `STT` unavailable merely because synthesis is unavailable and would conflate eligibility with mode policy.

### D7. Audio uses unforgeable execution- and generation-owned userdata

Lua receives audio as a private native full-userdata value with a locked metatable and no public fields or methods. It contains only a Lua-state-local opaque token and kind tag. It never contains PCM bytes, a filesystem path, device or route identity, JVM/Kotlin pointer, native pointer, capability lease, or public operation identity.

A host registry scoped to one Lua state and runtime generation maps each token to exactly one host-owned `OpaqueAudioRecording` or `OpaqueSynthesizedAudio` plus one host execution owner. The owner is either a specific `handle_input` invocation or a specific runtime-managed spawned task. Tokens cannot resolve in another state, instance, generation, or execution owner.

The registry enforces finite host-configured bounds on individual artifact bytes and duration plus live token count and retained bytes per execution owner, generation, and process. Capacity exhaustion rejects and disposes a new artifact before userdata delivery; an oversized backend artifact is disposed without token publication. Lua heap limits are not relied upon to bound host-owned audio.

Captured audio is created only for a `handle_input` invocation and remains valid while that invocation and generation remain authorized. It cannot be transferred to another callback or task. `subspace.transcription` may borrow a captured handle without consuming it. `subspace.playback.schedule` accepts captured or synthesized audio and atomically consumes the handle only after host queue admission succeeds. A second use of a consumed handle fails. Failed admission leaves the handle available until its execution owner terminates.

Synthesized audio is owned by whichever authorized execution called `subspace.synthesis`: the current `handle_input` invocation or runtime-managed spawned task. It follows the same state, generation, execution-owner, and consumption rules and is not persistable across callbacks, tasks, or process restart. This gives background tasks a coherent synthesis-to-playback path without making audio transferable between concurrent Lua executions.

All normalized serialization paths reject audio userdata: configuration, logs, callback terminal results, structured errors, source-map module return normalization, and any future ordinary data encoder. Lua cannot inspect or forge it. Garbage collection only drops a Lua reference; it never selects or releases an audio route and never initiates playback.

Termination of the owning callback invocation or spawned task disposes its unconsumed host artifacts. Generation close or revocation invalidates every registry entry and releases unscheduled resources. Successfully queued playback retains only host-owned audio plus the original instance and generation authorization; it no longer depends on Lua userdata or execution-owner lifetime.

**Alternative considered:** strings, numeric handles, light userdata, or ordinary tables. Rejected because they are forgeable, pointer-shaped, serializable, or inspectable and cannot enforce state-local ownership safely.

### D8. Three public modules expose yielding semantic audio operations

The host reserves and injects:

```text
subspace.transcription
subspace.synthesis
subspace.playback
```

The modules are present in the stable v1 module namespace for every package. Invocation checks manifest eligibility; requiring a module does not grant a capability.

#### `subspace.transcription`

```lua
local result, err = transcription.transcribe(captured_audio)
```

Success returns `{text=<bounded UTF-8 string>}, nil`. It accepts only a live captured-audio userdata owned by the current state, generation, and invocation.

#### `subspace.synthesis`

```lua
local audio, err = synthesis.synthesize({
    text = <nonblank bounded UTF-8 string>,
    language = <nonblank bounded language tag>,
    voice = <nonblank bounded logical voice ID>,
    speed = <optional finite positive number, default 1.0>
})
```

Success returns an opaque synthesized-audio userdata and `nil`.

#### `subspace.playback`

```lua
local result, err = playback.schedule(audio, {
    delay_seconds = <optional finite nonnegative number within host maximum, default 0>
})
```

Success returns `{status="scheduled"}, nil` only after the host has atomically accepted the exact audio into the selection-aware deferred queue and consumed its userdata handle. The public operation performs internal playback-operation creation and queue admission as one semantic action. It never returns an internal operation, queue token, route, endpoint, or playback object. Failed admission leaves the handle unconsumed.

The host applies finite positive deadlines to transcription, synthesis, and queue admission. It also enforces a finite maximum delay and finite queued-entry-count and retained-audio-byte limits per instance, generation, and process. Invalid delay returns `E_INVALID_ARGUMENT`; exhausted queue resources return `E_BUSY`. These limits bound retained host audio without exposing their numeric policy values as Lua compatibility promises.

These calls may yield only from the host-managed `handle_input` coroutine or a runtime-managed task admitted by `subspace.runtime.spawn`. A top-level call during source/module evaluation fails the complete load through the effect guard. After loading, a call from startup, lifecycle, readiness, SOS, or a plugin-created unmanaged coroutine returns `E_INVALID_CONTEXT` before suspension or effect; that denied call does not itself fail a callback that handles or ignores it. `handle_input` is the only event callback changed to host-managed yield-capable execution in this change.

Expected failures return `nil, error_table` and do not throw. Public error codes are exactly:

```text
E_INVALID_ARGUMENT
E_INVALID_VALUE
E_INVALID_CONTEXT
E_CAPABILITY_UNDECLARED
E_UNAVAILABLE
E_BUSY
E_TIMEOUT
E_CANCELLED
E_CLOSED
E_STALE
E_HOST_FAILURE
```

An optional bounded language-neutral `reason` may accompany the code. It contains no platform exception text, endpoint/device identity, credential, path, SDK object, or transport detail.

After all yielding work completes, `handle_input` still returns exactly `{ok=true}` or `{error={code=<nonblank string>, detail=<nonblank string>}}`. A malformed terminal return fails the input under the existing fail-closed rule.

**Alternative considered:** expose separate `create_playback_operation` and `schedule` calls matching current Kotlin ports. Rejected because that exports an internal split instead of the user-facing semantic request.

### D9. Semantic operations reuse actor/generation cancellation and authorization

Each semantic call receives an actor operation token, the current execution-owner identity, and the current instance/generation capability identity. The execution owner is either a `handle_input` invocation or runtime-managed spawned task. Yielding releases the serialized adapter/Lua-entry slot so unrelated actor work may progress without making userdata or completion transferable between owners.

Exactly one terminal event wins for each operation: success, typed host failure, finite deadline, explicit live-input cancellation, spawned-task cancellation, or generation revocation. Completion resumes Lua only while the actor, execution owner, applicable audio registry entry, capability lease, and generation remain current.

A deadline winner resumes a live owner once with `E_TIMEOUT`, cancels or detaches host work, applies operation-specific handle cleanup, and suppresses late completion. Live input cancellation similarly resumes pending work once with `E_CANCELLED` where bounded terminal delivery remains possible. Spawned-task cancellation or termination discards its suspended coroutine and unconsumed audio. Generation retirement or close cancels host work, discards suspended executions without re-entering Lua, invalidates audio userdata, revokes leases, removes generation-owned queued playback, and suppresses late completion, status, log, and playback effects.

Playback queue entries carry authorizing channel instance and runtime generation. Successful scheduling followed by generation replacement, removal, or close cannot produce predecessor playback. Temporary channel selection changes retain authorized entries pending under the bounded queue policy. No operation retries, switches capability, changes mode, or selects a fallback output automatically.

### D10. The external Debug package reproduces all five modes

The package declares schema version `1`, default `mode="ECHO"`, all five exact choices, and all three public audio capabilities.

Its callback behavior is fixed:

```text
startup(configuration)
    validate/capture configuration.values.mode

handle_readiness(context)
    check only the selected mode's dependency set
    return {ready=<boolean>, status=<exact mode token>}

handle_input(capture event)
    ECHO:
        playback.schedule(event.audio, {delay_seconds=0})

    DELAYED_ECHO:
        playback.schedule(event.audio, {delay_seconds=5.0})

    STT:
        transcription.transcribe(event.audio)
        return success without playback

    TTS:
        synthesis.synthesize({
            text="Debug synthesis test",
            language="en",
            voice="default",
            speed=1.0
        })
        playback.schedule(result, {delay_seconds=0})

    STT_TTS:
        transcription.transcribe(event.audio)
        synthesis.synthesize({
            text=<exact successful transcript>,
            language="en",
            voice="default",
            speed=1.0
        })
        playback.schedule(result, {delay_seconds=0})
```

`DELAYED_ECHO` uses host queue eligibility delay, never `subspace.runtime.sleep`, so Lua does not retain captured audio in a sleeping coroutine and later same-channel entries cannot overtake the queue head.

The package omits `handle_sos`, preserving neutral no-op behavior. Host execution status remains:

```text
IDLE → RECORDING → PROCESSING → SUCCESS | FAILED
```

Source/session cancellation returns to `IDLE`. Capability, validation, module, or terminal failures produce `FAILED`. Successful STT means transcription completed. Successful playback modes mean exact audio was accepted into the host deferred queue; they do not claim it was already heard. No failure path changes mode, substitutes fixed text for failed `STT_TTS`, or falls back to local output.

### D11. Playback remains selection-aware and physically host-routed

Lua only requests deferred playback. The host performs actual playback after terminal capture cleanup under the existing half-duplex coordinator and selection policy. Capture, announcements, and channel content remain serialized.

The queue is channel-associated FIFO. It does not play a Debug entry while another channel is selected, does not redirect an entry to another channel, and does not let delayed eligibility reorder entries. It re-evaluates current selection and `InputMode` at admission. Generation revocation/removal discards the authorized entry rather than playing stale audio.

`ModePlaybackRouteResolver` remains authoritative:

- Work reacquires the target RSM Bluetooth SCO output with voice-communication usage;
- On-a-pinch requires normal Android audio mode and uses the built-in speaker with media usage;
- On-the-road waits until Telecom capture routing is released and mode is normal, then uses a validated car media output.

There is no ambient output, device choice, route handle, or local fallback visible to Lua. A route that is temporarily busy remains pending under host policy; a terminal route failure produces a typed host outcome and cleanup.

### D12. Debug publication uses the existing exact GitHub path

The canonical repository is `nilp0inter/debug-channel`. It is published by immutable owner database ID `1224006`, so it receives the existing Official provenance tier without claiming review, audit, signature, or defect freedom.

Implementation first creates the repository, resolves its positive immutable repository database ID through GitHub, and writes that exact value into the manifest. It then builds deterministic source-only bytes, validates them with the app's real `PackageValidator`, and publishes stable non-draft, non-prerelease tag `v1.0.0` with exactly one canonical `subspace-channel.zip` asset.

The archive contains only the evolved `manifest.json` and canonical UTF-8 `lua/*.lua` source. After publication, evidence records exact repository/release/asset IDs, publication timestamp, asset size, and SHA-256. The digest proves exact-byte integrity only. The application repository may retain byte-pinned test evidence but does not bundle production Debug source, install it automatically, or add Debug-specific GitHub/client behavior.

Published assets are immutable. Branches, source archives, workflow artifacts, arbitrary URLs, mutable release replacement, signatures, and attestations remain excluded.

### D13. Diagnostics is republished as the clean-cutover canary

`nilp0inter/diagnostics-channel` publishes stable `v1.2.0` after the immutable historical `v1.0.0` and `v1.1.0` releases. The new package declares explicit empty configuration data/UI field arrays and explicit empty capabilities. It implements `startup(configuration)` and verifies the expected empty snapshot.

The new input event contains opaque audio, but Diagnostics never inspects, retains, serializes, plays, or logs it. Existing heartbeat, lifecycle, readiness, bounded metadata input logging, SOS logging, release marker, update, rollback, removal, reinstall, and restart behavior remains unchanged.

Old Diagnostics assets remain historical exact artifacts and fail the evolved host's mandatory manifest shape. The host does not mutate them, infer empty declarations, or retain legacy callback support. Tests and provenance pin the new exact release.

### D14. Remove built-in Debug and preserve old identities honestly

The clean-cutover removal frontier includes:

- `DebugBuiltInProvider` and `DebugRuntime`;
- `DebugMode`;
- `DebugProviderConfiguration`, its codec/provider, and Debug descriptor;
- `BuiltInChannelImplementationIds.DEBUG`;
- foreground-service Debug registration;
- Debug-only widgets with no remaining callers;
- legacy `debug_channel_mode` seeding;
- built-in Debug tests, fixtures, imports, and branches.

A fresh legacy-to-catalogue seed no longer creates Debug. It seeds the remaining providers and chooses a valid active instance among them when the old active preference named Debug, preserving the catalogue's nonempty/valid-active invariants.

Already-persisted definitions whose implementation ID is `builtin:debug` are not deleted, rewritten, aliased, or rebound. The ordinary missing-provider path preserves their instance ID, name, position, enabled state, schema version, and opaque `{mode:...}` payload while projecting explicit unavailability.

The installed Debug package has durable implementation ID `github-repository:<resolved repository ID>`, which is intentionally different. The user installs it and creates/names an instance through existing package and catalogue management. The host does not copy mode, reuse instance ID, select it, or silently substitute it for an old definition.

### D15. Verification is layered around public behavior

Verification has eight layers:

1. **Manifest and declaration validation:** exact required keys, unknown-key rejection, duplicate/collision handling, bounds, scalar types, defaults, ranges, data/UI matching, capability allowlist, empty Diagnostics declarations, and rejection of old artifacts.
2. **Store/materialization:** exact digest revalidation, evolved manifest round-trip, crash-safe store/index behavior, provider compilation, revision publication, and isolation of one malformed provider.
3. **Generic configuration:** default creation, host rendering, edit validation, opaque persistence, process restart, unknown/malformed payload rejection, two-instance isolation, and configuration-triggered drain-before-ready replacement.
4. **Lua/actor boundary:** startup snapshots, detached mutation, audio userdata non-forgeability/non-serialization, wrong-kind/foreign-state/stale/consumed rejection, module argument/result/error shapes, allowed contexts, yielding, timeouts, cancellation, revocation, and late completion suppression.
5. **Capability/playback ownership:** public-ID mapping, undeclared denial before effects, mode-dependent availability, borrow/consume/dispose behavior, queue generation authorization, exact-once admission, and no route/platform leakage.
6. **Complete Debug parity:** all five modes through the installed package provider and real registries; exact default, readiness sets, constants, delay, operation order, status, failure/cancellation behavior, multiple instances, update/replacement, and absence of built-in registration.
7. **External artifacts:** exact public Debug and Diagnostics repository/release/asset identities, bytes, digests, generic installation, instance creation, update/rollback/removal/reinstall, and restart recovery without package special cases.
8. **Physical device:** Work, On-a-pinch, and On-the-road PTT runs for relevant modes through installed Debug. Evidence records selected instance/provider digest/generation/mode, capture terminal ordering, capability start/terminal events, deferred queue acceptance, route admission, endpoint/device type/usage, Telecom release before car playback, audible result, and absence of local fallback, duplicate playback, stale predecessor effect, or cross-channel playback.

Behavior tests use the generic installed provider, catalogue, runtime registry, actor, capability adapters, and routing components. They do not register a test-only Debug provider, call capability ports directly as the acceptance path, or bypass package installation/materialization.

## Risks / Trade-offs

- **[Risk]** The flat scalar configuration schema is too narrow for later channels. → Deliberately keep v1's first declaration bounded; later migrations may evolve the still-unreleased contract when nested values, dynamic resources, directories, or secrets have concrete semantics.
- **[Risk]** A package update changes declarations and invalidates stored instance payloads. → Preserve the exact payload, project typed configuration unavailability, and require explicit retained-package rollback; never repair or automatically roll back.
- **[Risk]** Opaque audio retains large PCM while Lua is suspended. → Scope registry tokens to the current generation and execution owner, consume them on successful scheduling, dispose unconsumed artifacts when the owning callback or task terminates, and bound operation deadlines.
- **[Risk]** Userdata or continuation bugs permit use after close or cross-instance/execution access. → Resolve only through state, generation, and execution-owner registries, lock metatables, reject foreign, stale, consumed, or closed tokens before acquisition, revoke monotonically, and test every completion, cancellation, owner-termination, and close race.
- **[Risk]** STT/TTS holds a committed terminal callback longer than metadata-only Lua v1 did. → Use the existing bounded invocation/operation deadlines, release the Lua-entry slot while yielded, propagate cancellation, and schedule playback only after semantic work completes.
- **[Risk]** Queued audio outlives the code generation that authorized it. → Store instance/generation authorization with each queue entry and remove/suppress it on revocation before physical playback.
- **[Risk]** Mode readiness diverges from call eligibility. → Build readiness snapshots and call authorization from the same public capability IDs; every operation rechecks the live capability lease.
- **[Risk]** External Lua playback changes Work/car/phone routing. → Keep route selection entirely in existing host resolvers and require physical endpoint/usage/order evidence for all three modes.
- **[Risk]** Existing Diagnostics installations become unavailable after the clean cutover. → Publish exact v1.2.0 first, expose the typed incompatibility, and require explicit update; do not add a shim.
- **[Risk]** A pre-cutover cached manifest prevents the package repository from opening, which also blocks an explicit evolved update and unrelated package installs. → Recognize only the exact two-declaration omission in the host index as an untrusted legacy carrier; never trust or execute it, and require archive-derived evolved validation for materialization or replacement.
- **[Risk]** External release and app changes are published out of order. → Stage and validate exact evolved packages before final app acceptance; old hosts reject new required fields and new hosts reject old missing fields.
- **[Risk]** Removing built-in Debug surprises development users. → Preserve old definitions and payloads honestly as unavailable, make explicit installation/instance creation actionable, and avoid silent identity mutation.
- **[Risk]** SHA-256 is presented as publisher authentication. → Retain immutable owner-ID trust classification and describe digest only as exact-byte integrity.
- **[Trade-off]** Debug declares three capabilities even when one mode uses fewer. → Treat declarations as eligibility and compute mode-specific readiness in Lua; this avoids configuration-dependent descriptor mutation.
- **[Trade-off]** No automatic old-instance migration is provided. → Accepted because provider identity is repository-derived and pre-release clean cutover must not silently reinterpret `builtin:debug` as another publisher identity.

## Migration Plan

1. Evolve manifest/domain/store parsing so `configuration` and `capabilities` are mandatory, statically bounded, preserved in validated revisions, and old fixtures fail closed. Update all in-tree package builders and adversarial fixtures in the same cutover.
2. Add the declaration compiler and package-specific configuration provider/field list/capability eligibility. Prove generic create/edit/persist/restart/replacement behavior before Lua consumes configuration.
3. Replace the callback contracts with `startup(configuration)`, readiness context/status, and audio-bearing yield-capable input. Add detached snapshot conversion and the state/generation-scoped opaque audio registry.
4. Add public transcription, synthesis, and playback modules over existing semantic host capabilities and actor operation tokens. Close cancellation, timeout, revocation, consume/dispose, queued-authorization, and late-effect races.
5. Create `nilp0inter/debug-channel`, resolve its repository ID, build all five modes, validate exact local package bytes, and exercise them through the generic installed-provider path.
6. Remove the built-in Debug provider, descriptor, model, registration, legacy seed, UI, source, and implementation-specific tests. Preserve existing `builtin:debug` definitions through the generic unavailable-provider path.
7. Publish Diagnostics `v1.2.0`, record exact provenance, update pinned fixtures, and prove existing Diagnostics behavior on the evolved contract.
8. Publish Debug `v1.0.0`, record exact provenance, run every automated layer, then capture physical Work, On-a-pinch, and On-the-road evidence through the public package and production composition.

Before publishing the evolved app, rollback means reverting the complete app change; published package assets are never mutated. If an app version is rolled back after deployment, installed `github-repository:*` definitions and package data remain preserved but may be unavailable to the older parser/runtime. A preserved `builtin:debug` definition resolves only if the entire prior app version restores its former implementation; the migrated version itself contains no fallback. Generic package rollback applies only between already-valid evolved Debug revisions and never rewrites catalogue identities or configuration.

## Open Questions

None. The pre-release clean-cutover policy, manifest declaration shape and bounds, capability IDs/mapping, configuration delivery/replacement, opaque audio ownership, yielding APIs and errors, readiness/status, all five Debug modes, routing, external publication/provenance, Diagnostics update, built-in removal, old-definition behavior, verification layers, and rollback semantics are fixed above.
