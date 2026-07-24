## Context

The current built-in OpenAI Agent is intentionally host-heavy. `OpenAiAgentRuntime` transcribes and admits turns, `AgentRunCoordinator` owns volatile conversation and the completion/tool loop, `DurableAgentRunStore` records provider-shaped run evidence, `AgentToolBroker` maps OpenAI tool calls to keyboard output, and `openai/**` owns profiles, credentials, model discovery, and the Java SDK. That implementation remains the side-by-side baseline in this change.

The Lua platform already provides isolated source-only Lua 5.4 actors, package-local `require`, exact manifests, static configuration UI, semantic audio/filesystem/keyboard APIs, managed tasks, generation replacement, installed-package provenance, and selection-aware delayed playback. It does not provide plugin networking, JSON, secrets, reusable global profiles, plugin-executed configuration choices, or crash-safe asynchronous work. Adding an OpenAI-specific Lua bridge would reproduce the current coupling and fail the platform goal.

The intended split is:

```text
External Lua package                     Kotlin/Rust host
────────────────────────────────────     ──────────────────────────────
OpenAI profile semantics                 package/profile persistence
model discovery policy                  protected secret storage
Chat Completions requests                bounded HTTPS + TLS
volatile conversation                    bounded JSON codec
completion/tool loop                     durable opaque work/effects
OpenAI tool schemas                      semantic audio/playback
provider error interpretation            semantic keyboard output
vendored Lua client/dependencies          actor isolation and quotas
```

The package system is pre-release. Revised v1 manifests and modules cut over exactly; no compatibility parser, omitted-field default, module alias, or API-version fork is retained. Existing official packages must publish compatible exact artifacts.

## Goals / Non-Goals

**Goals:**

- Introduce low-level provider-neutral HTTP, JSON, secret, profile, dynamic-choice, and durable-work APIs suitable for future Lua packages.
- Keep Android TLS, protected storage, durable evidence, audio routing, playback admission, keyboard transport, cancellation, quotas, and lifecycle ownership host-controlled.
- Let explicitly authorized Lua receive plaintext profile secrets and send them to any HTTPS origin.
- Give plugin authors an idiomatic coroutine-oriented work API while keeping crash/replay mechanics internal.
- Support short-lived package choice resolvers without constructing or authorizing an ordinary channel runtime.
- Publish a real external OpenAI Agent package using an unmodified pinned off-the-shelf OpenAI protocol client and package-local compatibility adapters.
- Preserve current asynchronous PTT, bounded tool-loop, keyboard-tool, volatile-conversation, synthesis, and delayed-playback behavior closely enough for side-by-side device comparison.
- Keep the built-in, legacy profiles, Java SDK, and their tests operational until a later clean-removal change.

**Non-Goals:**

- Removing, aliasing, or automatically migrating `builtin:openai-agent` or legacy profiles.
- Making Kotlin fully OpenAI-agnostic in this additive evaluation change.
- Cross-package profile-type dependencies or an organization profile-type repository.
- Raw sockets, plain HTTP, plugin TLS policy, native Lua modules, LuaRocks at runtime, or binary response streaming.
- Responses, Assistants, Realtime, server-owned conversation state, token streaming, streaming playback, or parallel tool execution.
- Durable conversation history, general key-value/database access, arbitrary queue creation, automatic retries of ambiguous effects, or exactly-once external delivery claims.
- Per-tool-call approval, camera tools, additional agent tools, or provider-specific Kotlin adapters for the Lua package.

## Decisions

### D1. Public host APIs remain low-level and provider-neutral

The host adds `subspace.http`, `subspace.json`, `subspace.profiles`, `subspace.secrets`, and `subspace.work`. None contains OpenAI endpoint paths, request/response classes, model filtering, tool schemas, client state, or retry policy. Android/Kotlin handles platform I/O and persistence; Rust owns Lua-facing validation, userdata, JSON conversion, and coroutine continuation where state-local behavior is required.

```text
Lua OpenAI client
    │ LuaSocket-shaped package adapter
    ▼
subspace.http.request
    │ typed opaque actor request
    ▼
Kotlin generic HTTP adapter
    │
    ▼
OkHttp / Android TLS
```

**Alternative considered:** expose a host `subspace.openai` completion API. Rejected because Kotlin would remain OpenAI-aware and other network plugins could not reuse the work.

**Alternative considered:** expose raw sockets or LuaSec. Rejected because native modules are prohibited and plugin-owned TLS/certificate policy would weaken containment.

### D2. Revised v1 manifest has explicit profile, resolver, and queue declarations

The exact root becomes:

```text
manifestVersion
repositoryId
packageVersion
entryModule
presentation
runtime
configuration
resources
profileTypes
choiceResolvers
workQueues
capabilities
```

All three new arrays are required even when empty. Capability IDs add `network.http`, `profiles.read`, `secrets.read`, and `work.queue`. The validator cross-checks source modules, configuration sources, resolver capability subsets, secret/profile relationships, and work declarations without creating a state.

Existing package bytes remain immutable and become incompatible when missing the new members. Their repositories publish explicit revisions with empty declarations and no new capabilities.

**Alternative considered:** make new fields optional. Rejected because optional omission becomes a permanent legacy grammar in an unreleased exact format.

### D3. Profile types are repository-scoped data schemas

Profile-type identity is `(repositoryDatabaseId, localTypeId)`. A generic profile record contains:

```text
profileId                 host-generated stable ID
typeRepositoryId         durable GitHub repository database ID
typeLocalId               canonical package-local type ID
displayName               host-owned bounded label
schemaVersion             1
scalarPayload             exact validated non-secret fields
secretReferences          field ID -> protected-store reference/presence
revision                  monotonic host revision
availability              current validation/storage state
```

Profile data/UI schemas reuse the bounded flat scalar configuration machinery and add `secret` plus protected `secret` control. Secret fields have no plaintext defaults. Profile storage is app-private, versioned, atomic, and independent of package artifacts and channel definitions. Package removal unpublishes types and revokes access but preserves records for same-repository reinstall or explicit deletion.

Only the declaring repository consumes its types in this change. The tuple identity does not encode “same currently installed package” as the permanent authority model, leaving room for a future explicit external-type dependency without implementing one.

**Alternative considered:** store endpoint/key/model in every channel. Rejected because users need reusable connection profiles and secret rotation across instances.

**Alternative considered:** public string type IDs shared across repositories. Rejected because name collision would become credential authority.

### D4. Profile selection grants exact profile revision authority

A channel `dynamic-choice` may source same-package profiles by type. Configuration persists only profile ID. At runtime construction, the provider identifies every profile-backed field, validates type/ownership/revision, and creates a detached grant snapshot for exactly those selected profiles. `subspace.profiles.get` is synchronous over that detached snapshot and returns scalar fields plus state-local opaque SecretReference values. Profile edit/delete replaces dependent runtime generations, so the snapshot never silently changes in place.

A resolver invocation receives a separate grant for only its dependency profile. Guessing another profile ID is denied even when the same package owns it.

### D5. Plaintext secret access is explicit and intentionally powerful

`subspace.secrets.read(reference)` accepts only an opaque SecretReference produced by the current state's profile grant. It yields through a typed host request to a generic Android keystore-backed secret store and returns bounded UTF-8 plaintext. The returned Lua string has no durable taint: host code can prevent its own logging/persistence but cannot recognize every copied string. Package contracts and trust UI therefore state that `secrets.read` plus `network.http` permits intentional exfiltration to any HTTPS origin.

The generic protected store extracts reusable encryption/alias mechanics from the existing OpenAI credential implementation while the legacy OpenAI repository continues through an adapter. Profile metadata and secret values commit atomically at the domain operation level; cleanup failure is explicit and retryable without exposing plaintext.

**Alternative considered:** opaque credentials applied only inside HTTP. Rejected by decision because ordinary Lua libraries expect plaintext keys and explicitly granted Lua access is acceptable.

### D6. HTTP is one complete bounded UTF-8 request/response operation

`subspace.http.request` accepts exact method, absolute HTTPS URL, headers, optional UTF-8 body, and bounded timeout. Kotlin uses the existing OkHttp 4.12.0 dependency and a dedicated generic adapter. It enforces HTTPS, platform trust, hostname verification, finite redirects, per-hop URL checks, removal of sensitive headers on cross-origin redirects, deadlines, cancellation, and request/response bounds. It returns complete status/headers/body; non-2xx status is a transport success. Binary/oversized responses fail atomically.

Any HTTPS origin is allowed once `network.http` is granted. Network and secret capabilities remain separate. No automatic provider retry is added.

The Lua package's `socket.http` module drains its LTN12 request source into a string, calls `subspace.http`, feeds the complete response body into the supplied sink, and returns LuaSocket-compatible status/header values required by `lua-openai`. This adapter is package code, not a host compatibility promise.

### D7. JSON lives in Rust and exposes an explicit null sentinel

The actor crate already pins `serde_json`. `subspace.json` performs bounded state-local encode/decode in Rust, preserving representable signed integers as Lua integers and rejecting cycles, sparse/mixed tables, non-finite numbers, invalid UTF-8, excessive depth/entries/bytes, and unsupported userdata. JSON null maps to a locked state-local Null userdata accepted only by JSON APIs.

The package-local `cjson` adapter exports `null = subspace.json.null` and wraps encode/decode. Host does not reserve `cjson` or claim CJSON ABI compatibility.

**Alternative considered:** vendor a pure-Lua JSON parser. Rejected because every package would repeat parser allocation/security work and `serde_json` is already in the native boundary.

### D8. Dynamic choice resolvers are one-shot restricted actors

Manifest resolver declaration binds local ID to canonical module and a subset of package capabilities. The module returns exactly `{resolve = function}`. Editor/readiness invocation creates a fresh actor/state, loads only immutable package sources, grants the one dependency profile, allows JSON/profile/secret/HTTP/log operations, invokes resolve once, validates exact choices, and closes.

Resolver actors do not execute channel entry/startup/readiness callbacks, cannot spawn/defer/sleep, cannot use work/audio/filesystem/keyboard, and share no state with channel actors or sibling resolvers. Results bind package, resolver, dependency, and profile revisions; stale completion is dropped. Static package validation still executes no Lua.

**Alternative considered:** invoke resolver in a running channel actor. Rejected because channel creation needs choices before a runtime exists and editor work must not mutate conversation/tasks.

**Alternative considered:** retain long-lived resolver actors. Rejected because cache/lifecycle/secret authority would become hidden global plugin state.

### D9. Durable work is an opaque FIFO and effect ledger

The generic store is app-private, versioned, crash-safe, and partitioned by repository, channel instance, declared queue, and work epoch. Core persisted records are:

```text
QueueRecord
  package repository/revision
  instance/configuration/profile revision
  queue ID and epoch
  next FIFO sequence

WorkRecord
  work ID, sequence
  opaque normalized payload
  state: queued | claimed | terminal
  owning epoch
  terminal class/reason metadata

EffectRecord
  work ID + package key
  state: started | committed | indeterminate
  committed normalized success/error result
```

At most one Job is active per queue. `submit` commits payload and sequence before success. `receive` uses a bounded waiter coordinated by Kotlin coroutines; it does not poll. Claims are task/generation-bound but records are host-owned.

Terminal completion purges payload/effect bodies after any separate delayed-playback admission and retains a bounded tombstone. The store is not exposed as arbitrary keys, history, or database queries.

**Alternative considered:** plugin-private KV/files for the queue. Rejected because every plugin would reimplement crash ordering and ambiguous-effect safety; mounted document storage also makes no sufficient cross-provider atomicity guarantee.

### D10. `job:effect` makes recovery linear and idiomatic

The public API is object-oriented Lua:

```lua
local queue = assert(work.open("turns"))
local job = assert(queue:receive())
local payload = job:payload()
local value = assert(job:effect("completion:1", function()
  return perform_and_normalize_one_external_effect()
end))
assert(job:complete())
```

Kernel/host sequence for a new effect:

```text
job:effect(key, fn)
  │ validate job/key/function and no nested effect
  ├─yield WorkBeginEffect──────────────────────► durable store
  │                                              commit STARTED
  ◄──────────────────────── new | committed result
  │ committed: return cached value; never call fn
  │ new:
  ├─invoke protected yieldable fn
  │   └─zero or more authorized host suspensions
  ├─normalize exact success/error
  ├─yield WorkCommitEffect─────────────────────► durable store
  │                                              commit result
  ◄─────────────────────────────────────────────
  └─return committed result
```

The Rust actor must support a protected nested Lua function continuation within the same managed task, not another mailbox callback/task. Effect function references never cross JNI or enter durable data. Closing after STARTED but before COMMITTED causes one indeterminate reconciliation. A conservative false-positive indeterminate window between marker commit and actual external call is accepted; duplicate external effects are worse.

On unchanged process restart, package policy starts from the Job payload again. Calling a committed stable key returns its durable value without invoking the function, so pure Lua control flow reconstructs continuation. No Lua stack or completed conversation is restored.

### D11. Work epochs encode intentional invalidation

Ordinary process reconstruction with identical package digest, configuration revision, selected profile revisions, and queue declaration preserves the epoch. Safe queued/claimed-without-started-effect work becomes claimable in original order; started-uncommitted work becomes indeterminate.

SOS, configuration edit, selected profile edit/delete, package update/rollback/removal, instance deletion, or explicit reset retires the epoch before successor readiness. Unstarted nonterminal work becomes cancelled; started work becomes indeterminate. New generation uses a new epoch and volatile conversation. This avoids changed Lua source/configuration consuming predecessor payloads.

### D12. Generic projections derive from work metadata only

A work coordinator publishes per-instance queued count, active presence, and normalized terminal class into existing channel snapshots. It does not expose payload/effect content. Delayed playback remains the authority for pending/heard responses. Work completion occurs only after response playback is admitted; then work bodies can purge while delayed playback retains its own bounded content/audio.

### D13. The OpenAI package owns all provider and agent policy

The official external repository packages:

- `leafo/lua-openai` v1.8.0 commit `d363da696a602b0a966d3942777e587c552363ba`.
- `leafo/tableshape` v2.7.0 commit `dc4a3b81a17fd68aa44ba715620ea79adcd84834`.
- LuaSocket v3.1.0 pure-Lua `ltn12`/`socket.url` sources at commit `95b7efa9da506ef968c1347edf3fc56370f0deed`.
- Package-local `socket.http` and `cjson` adapters and all required licenses.

The package declares `openai_compatible {base_url, api_key}`, resolver `models`, queue `turns`, and configuration for profile, model, multiline prompt, keyboard enablement, and keyboard platform/layout/profile. It uses non-streaming Chat Completions with `parallel_tool_calls=false`, stable effect keys, finite turns/calls/bytes, exactly `type_text` and `press_enter`, existing semantic synthesis, and delayed playback.

Model/provider data never enters Kotlin models. The retained built-in may continue using existing OpenAI capabilities during evaluation; external Lua never falls back to them.

### D14. Side-by-side publication is separate from built-in removal

The change creates/publishes `nilp0inter/openai-agent-channel` through the ordinary external release path. Repository database ID, release/asset IDs, artifact digest, size, and exact licenses are resolved and recorded during publication. The app does not bundle or auto-install it. Users create a new package profile and instance manually. Built-in profiles and definitions remain separate.

A later change removes the built-in, Java SDK, legacy profile UI/store, `OpenAi*` models/capabilities, and agent-specific coordinator/broker/store code. This proposal does not claim Kotlin is already OpenAI-agnostic.

## Risks / Trade-offs

- **[Secret plus unrestricted HTTPS permits exfiltration]** → Display the combined authority before installation/profile binding; grant only same-package selected-profile references; keep host observability content-free.
- **[Dynamic resolver can make editor responsiveness depend on remote service]** → One-shot actor, strict deadline/concurrency/result bounds, cancellation, explicit unavailable/stale states, no automatic retry.
- **[Effect marker may produce false indeterminate without an actual external call]** → Accept conservative ambiguity; never replay after STARTED without COMMITTED evidence.
- **[Committed effect results can retain sensitive provider content until Job terminal]** → Strict per-effect/job bytes and counts; content-free diagnostics; immediate purge at terminal handoff; no terminal history.
- **[Linear replay requires stable package effect keys and deterministic policy around them]** → Package conformance tests cover restart at every boundary, duplicate/conflicting keys, tool-loop reconstruction, and changed-source epoch retirement.
- **[One complete UTF-8 HTTP body excludes binary/streaming libraries]** → Keep first API bounded and auditable; OpenAI JSON uses complete UTF-8 bodies. Future binary/streaming capability requires a separate contract.
- **[Profile update fans out runtime replacements]** → Index reverse dependencies, reconcile only selected-profile consumers, serialize replacement through existing runtime registry.
- **[Revised exact manifest invalidates installed official artifacts]** → Publish compatible explicit-empty revisions for all official repositories and verify update/rollback paths before device acceptance.
- **[Large vendored dependency surface consumes source/module limits]** → Pin exact minimal Lua files, validate package bounds, retain licenses, and test exact artifact through production validator.
- **[Side-by-side duplicates profile setup and leaves OpenAI Kotlin temporarily]** → Manual duplication is explicit evaluation cost; defer migration and delete all legacy paths in the later clean cutover.
- **[Resolver and job-effect continuations deepen actor complexity]** → Extend the existing typed request registry and scheduler rather than add a second bridge; conformance covers nested suspension, close, cancellation, memory/instruction denial, and late completion.

## Migration Plan

1. Evolve exact package/domain schemas and publish compatible no-authority revisions of existing official packages for the revised manifest.
2. Add generic profile/secret stores and profile-management/editor projections without changing built-in OpenAI storage.
3. Add Rust JSON, new opaque userdata, resolver mode, and work-effect continuation support behind no installed consumer.
4. Add Kotlin generic HTTP, resolver orchestration, durable work store/coordinator, provider materialization, and service/runtime composition.
5. Exercise black-box packages for HTTP/JSON/secrets/profiles/resolvers/work, including restart and every ambiguous-effect boundary.
6. Create and publish the external OpenAI package, install it through the production GitHub path, create separate profile/instance manually, and run side-by-side device acceptance.
7. Rollback by removing/rolling back the external package and app change while leaving the built-in operational. New versioned profile/work files remain app-private and are not silently converted or deleted by rollback.

## Open Questions

No product or architectural questions remain. The official OpenAI package's durable GitHub repository, release, asset IDs, and exact artifact digest are operational provenance discovered and pinned during implementation/publication, not unresolved design choices.
