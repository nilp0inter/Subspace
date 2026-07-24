## Why

Subspace needs generic Lua networking, secrets, reusable profiles, dynamic configuration, and durable asynchronous work so plugin authors can build real networked agents without Kotlin protocol bridges. An external OpenAI Agent is the proving client, but the intended product is a low-level provider-neutral Lua platform rather than an OpenAI migration layer.

## What Changes

- Add bounded host-injected `subspace.http` and `subspace.json` modules. Lua packages with declared network eligibility may issue generation-owned requests to any HTTPS origin; the host retains TLS, redirect, deadline, cancellation, quota, and response-bound enforcement without understanding application protocols.
- Add package-declared secret eligibility, protected secret fields, and `subspace.secrets` resolution. Profiles and channel configuration persist only secret references; explicitly granted plaintext may enter the owning Lua state and be sent through declared networking, but never enters host logs or ordinary configuration snapshots.
- Let installed packages declare repository-scoped profile types with bounded data/UI schemas. The generic editor creates named global profiles of those types, and channel configuration selects stable profile IDs owned by the declaring package. Cross-package profile consumption is excluded while type identity leaves a future path for organization-owned profile-type repositories.
- Add bounded package-defined dynamic-choice resolvers. A short-lived restricted Lua resolver may resolve the selected profile, read its granted secrets, call HTTPS, and return exact scalar choices without constructing a channel runtime, admitting work, or receiving audio, keyboard, or lifecycle authority.
- Add manifest-declared named durable work queues and idiomatic `subspace.work` queue/job objects. The host owns durable FIFO admission, one active item per queue, generation-safe leases, safe restart recovery, bounded durable effect memoization, non-replay handling of ambiguous effects, epoch retirement, and tombstone-only terminal retention while treating payloads and effect results as opaque normalized data.
- Extend managed Lua execution with `job:effect(key, function)`: committed bounded results are returned without replay, unstarted work may resume, and interruption after an external effect starts but before commit becomes terminally indeterminate.
- Create and publish an external, non-bundled Lua OpenAI Agent package. It declares an OpenAI-compatible profile type, discovers models through a package resolver, uses vendored [`leafo/lua-openai` v1.8.0](https://github.com/leafo/lua-openai/releases/tag/v1.8.0) pinned at commit `d363da696a602b0a966d3942777e587c552363ba`, and owns non-streaming Chat Completions, volatile conversations, bounded client-side tool iteration, synthesis/playback sequencing, and normalized provider failures.
- Vendor the client's pure-Lua dependencies and package-local `socket.http`/`cjson` compatibility adapters over `subspace.http`/`subspace.json`. Do not add LuaRocks resolution, native LuaSocket/LuaSec, C modules, raw sockets, or plugin-owned TLS.
- Expose `type_text` and `press_enter` as OpenAI tools implemented entirely in Lua through the existing declared `keyboard.output` and `subspace.keyboard_output` API, with `parallel_tool_calls=false` and no OpenAI-specific keyboard broker in the new path.
- Keep `builtin:openai-agent`, its OpenAI Java SDK, legacy OpenAI profiles, configuration, Kotlin runtime, and tests operational for side-by-side evaluation. The Lua package uses separate package profiles created manually; this change does not import credentials, automatically install the package, create an instance, change selection, or rebind provider identities.
- Purge durable work payloads, checkpoints, and effect bodies after terminal completion and playback handoff while retaining bounded non-sensitive tombstones. Durable work is not conversation history; delayed playback retains only the data required by its separate pending/heard lifecycle.
- Evolve the unreleased Lua/package v1 contracts directly with no compatibility version, alias, shim, or legacy dispatch. Removing the built-in and all remaining OpenAI-specific Kotlin belongs to a later clean-cutover change.

## Capabilities

### New Capabilities

- `lua-http-api`: Declared HTTPS eligibility, normalized requests and responses, host-owned TLS/redirect policy, coroutine execution contexts, limits, cancellation, privacy, and transport isolation.
- `lua-json-api`: Bounded JSON encoding/decoding, JSON-null representation, normalized Lua value mapping, errors, quotas, and support for package-local `cjson` compatibility.
- `lua-secrets-api`: Package-declared secret fields, protected references, explicit profile-bound grants, plaintext Lua resolution, lifecycle revocation, and log/configuration exclusion.
- `lua-profile-types`: Repository-scoped package profile declarations, global profile creation/editing, protected secret fields, stable identities, package-local consumption, validation, update/removal behavior, and future-safe type identity.
- `lua-dynamic-choice-resolvers`: Package-declared short-lived Lua choice resolution with profile/secret/HTTPS access, strict context isolation, bounded results, deadlines, cancellation, and editor/readiness integration.
- `lua-durable-work-api`: Manifest-declared queues, durable submission and FIFO claims, job/effect objects, memoized external effects, recovery and indeterminate outcomes, epochs, quotas, projections, privacy, and terminal tombstones.
- `lua-openai-agent-channel`: External package identity, profile/model/prompt/keyboard configuration, readiness, asynchronous PTT turns, volatile conversation, Chat Completions/tool behavior, synthesis/playback, publication, independent instances, and side-by-side acceptance with `builtin:openai-agent`.

### Modified Capabilities

- `lua-package-format`: Accept bounded profile-type, named-work-queue, package-resolver, secret-field, and new capability declarations while retaining an exact source-only manifest and static fail-closed validation.
- `lua-channel-configuration`: Render profile references and secret-aware schemas, invoke package dynamic choices only through the resolver boundary, persist stable IDs/references, and expose current profile/choice validity to readiness.
- `lua-channel-provider`: Compile profile, resolver, queue, HTTP, JSON, secret, and work eligibility into package-specific providers without executing Lua or introducing OpenAI/package special cases.
- `lua-channel-api`: Add the restricted dynamic-choice callback contract and bounded effect callback ownership while preserving exact lifecycle callback results and source-load effect prohibition.
- `lua-runtime-api`: Reserve and inject the generic HTTP, JSON, secrets, profiles, and work modules; normalize their values and authorize yielding calls only from their declared execution contexts.
- `lua-actor-runtime`: Execute resolver actors and durable job effects with typed host operations, protected yieldable functions, operation ownership, exact terminal gates, cancellation, close, and late-completion suppression.
- `channel-host-capabilities`: Add provider-neutral network, secret, profile, resolver, and durable-work authorities scoped to package, instance, generation, and execution owner.
- `channel-runtime-invocation`: Permit durable queue submission from input callbacks and queue processing from managed tasks without retaining PTT/audio routes or allowing predecessor-generation effects.
- `installed-lua-packages`: Validate, publish, update, roll back, restart, and remove packages with profile types, resolvers, queues, and new capabilities while preserving unrelated profiles and work safely.

## Impact

- Android/Kotlin: package domain and validator, generic configuration/profile management UI and persistence, protected credential storage, dynamic-choice orchestration, HTTPS transport adapter, durable work store/coordinator, Lua provider/runtime, typed host-operation broker, service composition, and generic channel projections.
- Rust/JNI Lua actor: reserved modules, JSON-null and opaque profile/queue/job/effect values, yieldable effect callback execution, resolver actor mode, exact argument/result normalization, coroutine ownership, limits, and terminal resumption.
- External repository: create and publish an official OpenAI Agent channel with deterministic source-only package bytes, pinned upstream Lua sources and licenses, compatibility adapters, exact durable repository identity, immutable release provenance, and no package bytes bundled in the APK.
- Existing packages: Debug, Diagnostics, Journal, and Keyboard remain ordinary packages and must continue through the evolved pre-release v1 contracts without acquiring undeclared network, secret, profile, resolver, or work authority.
- Existing built-in OpenAI Agent: retained as an independent evaluation baseline with separate profiles. Its later removal must delete the Java SDK and OpenAI-specific Kotlin contracts rather than leaving aliases or compatibility paths.
- Security and privacy: a package granted both secret and unrestricted HTTPS capabilities can intentionally exfiltrate those granted values; installation/profile surfaces must state that authority. Raw sockets, non-HTTPS transport, native modules, ambient secrets, payload logging, automatic ambiguous-effect replay, and unbounded durable retention remain prohibited.
