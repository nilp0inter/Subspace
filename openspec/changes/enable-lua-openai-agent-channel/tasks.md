## 1. Revised Package-v1 Domain and Validation

- [ ] 1.1 Add immutable profile-type, choice-resolver, work-queue, secret-field, multiline-control, and new capability declarations to `PackageDomain.kt` with finite bounds and exact constructor invariants
- [ ] 1.2 Extend dynamic-choice declarations to distinguish host sources, same-repository profile types, and same-repository Lua resolvers while retaining exact scalar dependency ordering
- [ ] 1.3 Update `PackageValidator` to require the revised exact manifest root and reject predecessor manifests that omit any new declaration array
- [ ] 1.4 Implement static graph validation for profile schemas/UI, resolver module and capability subsets, work queues, configuration sources, secret eligibility, uniqueness, and source-map presence without executing Lua
- [ ] 1.5 Extend installed-package index encoding/decoding to round-trip every new declaration, source kind, multiline control, capability, and bound exactly
- [ ] 1.6 Update package integrity comparison and rollback revalidation so declaration changes are detected without rewriting predecessor artifact bytes
- [ ] 1.7 Add validator contract tests for valid empty/new declarations and every missing, unknown, duplicate, cross-package, capability-mismatch, dependency-order, schema, and module-reference rejection
- [ ] 1.8 Add installed-store transaction tests proving exact round trips, failed-candidate isolation, rollback restoration, corruption handling, and immutable predecessor behavior

## 2. Existing Official Package Cutover

- [ ] 2.1 Publish a Debug package revision with explicit empty profile, resolver, and work arrays and no added authority
- [ ] 2.2 Publish a Diagnostics package revision with explicit empty profile, resolver, and work arrays and no added authority
- [ ] 2.3 Publish a Journal package revision with explicit empty profile, resolver, and work arrays and no added authority
- [ ] 2.4 Publish a Keyboard package revision with explicit empty profile, resolver, and work arrays while preserving its existing configuration and capabilities
- [ ] 2.5 Record each compatible release's immutable repository/release/asset identity, digest, size, and package version in its existing provenance source
- [ ] 2.6 Verify ordinary production-path update and rollback for all four packages and prove one package update does not alter sibling provider identity or authority

## 3. Generic Profile Types and Persistence

- [ ] 3.1 Define repository-scoped profile-type identity, stable profile ID, monotonic revision, availability, scalar payload, secret-reference state, failures, and finite profile bounds
- [ ] 3.2 Implement one exact profile-schema validator shared by package inspection, profile mutation, package update, rollback, reinstall, and runtime grant construction
- [ ] 3.3 Add a versioned app-private profile metadata store with atomic replace, strict decoding, bounded records, and corruption isolation by repository/type where possible
- [ ] 3.4 Implement generic profile creation with full candidate validation, stable host-generated identity, display-name bounds, and all-or-nothing metadata/secret commit
- [ ] 3.5 Implement generic profile editing with revision advancement, exact scalar preservation, protected secret retain/replace/clear semantics, and no partial visibility
- [ ] 3.6 Implement explicit profile deletion and package-removal unpublication while preserving repository-owned records and protected references until authorized deletion
- [ ] 3.7 Implement update, rollback, and same-repository reinstall revalidation that preserves incompatible payloads unchanged and projects typed unavailability without coercion
- [ ] 3.8 Add reverse dependency indexing from selected profile IDs to channel instances and invalidate only affected resolver requests, references, and runtime generations after committed mutation
- [ ] 3.9 Add profile repository tests for shared selection, create/edit/delete atomicity, required fields, bounds, schema incompatibility, repository rename, foreign repository denial, and storage corruption
- [ ] 3.10 Add lifecycle tests proving package removal/reinstall/update/rollback preserve profile identity, revoke access, and never reassign profiles to another repository

## 4. Generic Protected Secret Storage

- [ ] 4.1 Define provider-neutral protected-secret references, bounded UTF-8 operations, normalized failures, and a storage interface that exposes no keystore alias or platform object
- [ ] 4.2 Extract reusable Android Keystore encryption and alias management behind the generic store while keeping the legacy OpenAI credential repository operational through an adapter
- [ ] 4.3 Implement transactional secret create/replace/clear/delete preparation and rollback so profile metadata never commits against a failed protected mutation
- [ ] 4.4 Enforce per-secret bytes, valid UTF-8, concurrent-operation limits, prompt temporary-buffer release, and nondisclosing not-found/denied/storage failures
- [ ] 4.5 Define generation/state-local secret grants bound to package, profile, field, and profile revision without placing protected aliases in Lua-visible data
- [ ] 4.6 Add generic protected-store tests for round trips, replacement failure, cleanup retry, missing/corrupt/oversized/non-UTF-8 values, alias privacy, and cross-profile denial
- [ ] 4.7 Run and extend legacy OpenAI credential/profile tests to prove the extraction changes no built-in behavior or persisted legacy profile identity

## 5. Generic Profile and Trust UI

- [ ] 5.1 Add generic profile-management state and coordinator operations sourced from the atomic installed-package/profile snapshots
- [ ] 5.2 Extend package management UI to list published profile types and their source repository identity without executing package Lua
- [ ] 5.3 Implement generic profile create/edit forms for text, toggle, number, choice, and protected secret controls with exact render order and no plaintext redisplay
- [ ] 5.4 Implement profile deletion, missing-package, incompatible-schema, protected-storage-failure, and retained-profile unavailable states without silently changing channel configuration
- [ ] 5.5 Present profile/resolver/work authority and the explicit `secrets.read` plus arbitrary-HTTPS exfiltration warning before trust activation and profile binding
- [ ] 5.6 Add Compose/state tests for form validation, secret presence, failed saves, profile sharing, deletion, unavailable schemas, repository isolation, and authority disclosure

## 6. Dynamic Configuration Reference Model

- [ ] 6.1 Generalize dynamic-choice request identity to carry source kind, active package revision, requesting field, dependency field/value, profile revision, and caller request identity without provider objects
- [ ] 6.2 Update materialized configuration fields to preserve multiline controls and exact host/profile/resolver source metadata without resolving them during materialization
- [ ] 6.3 Update the editor to clear all transitive dependent draft scalars when an upstream value changes and suppress late results from predecessor dependencies
- [ ] 6.4 Update readiness resolution to revalidate persisted profile and resolver choices against current revisions while preserving unavailable selected IDs and prohibiting fallback selection
- [ ] 6.5 Add dynamic-choice model/editor/readiness tests for profile lists, resolver results, transitive clearing, stale completion, removed choice, unavailable dependency, bounds, and unchanged keyboard host sources

## 7. Bounded Rust JSON Module

- [ ] 7.1 Extend native actor policy/configuration with finite JSON input, output, depth, entry, key, string, and numeric-token bounds charged to the owning state
- [ ] 7.2 Implement locked state-local JSON Null userdata with nonrevealing stringification, self-only equality, foreign-state rejection, and no ordinary normalization path
- [ ] 7.3 Implement `subspace.json.encode` over `serde_json` with deterministic integer/number handling and atomic rejection of cycles, metatables, sparse/mixed tables, invalid strings, non-finite numbers, unsupported userdata, and over-bound output
- [ ] 7.4 Implement `subspace.json.decode` with one-document/trailing-data validation, exact null mapping, integer preservation, deterministic object/array conversion, allocation accounting, and atomic malformed/over-bound failure
- [ ] 7.5 Reserve and inject `subspace.json` in ordinary and resolver states while permitting only bounded pure calls during source evaluation
- [ ] 7.6 Add Rust conformance tests for every JSON value class, integer versus float encoding, null round trip, Unicode, trailing data, sparse/mixed/cyclic structures, unsupported values, and foreign null
- [ ] 7.7 Add adversarial JSON tests for depth, entries, bytes, huge numeric tokens, malformed UTF-8, allocation pressure, state reuse after failure, and sibling-state isolation

## 8. Lua Profile and Secret Modules

- [ ] 8.1 Add Rust/Kotlin opaque-handle contracts for state-local profile grants and SecretReference tokens with package/profile/field/revision ownership
- [ ] 8.2 Build detached selected-profile snapshots during runtime/resolver construction containing scalar fields and protected reference grants but no plaintext or mutable repository objects
- [ ] 8.3 Implement synchronous `subspace.profiles.get` with exact detached return shape, selected-profile-only lookup, mutation isolation, and normalized foreign/missing/stale denial
- [ ] 8.4 Implement yielding `subspace.secrets.read` as a typed actor request that resolves only a current opaque reference and returns bounded plaintext without observability content
- [ ] 8.5 Enforce module capability, execution-owner, generation, resolver, managed-task, input, effect, startup, lifecycle, readiness, SOS, and source-load context rules at mediation and call time
- [ ] 8.6 Add native/JVM conformance tests for profile detachment, guessed IDs, opaque-reference confinement, secret read success/failure, capability denial, stale revision, close, resolver grant narrowing, and content-free diagnostics

## 9. Generic HTTPS Capability

- [ ] 9.1 Define exact provider-neutral HTTP request/response/failure models and finite URL, header, body, redirect, timeout, concurrency, and retained-byte bounds
- [ ] 9.2 Implement native `subspace.http.request` argument validation, capability/context checks, typed actor suspension, response normalization, cancellation, and late-completion suppression
- [ ] 9.3 Implement a Kotlin OkHttp adapter that accepts only normalized absolute HTTPS URLs, allowed methods, valid headers, bounded UTF-8 bodies, and finite deadlines before DNS or connection work
- [ ] 9.4 Implement bounded per-hop redirects with HTTPS revalidation, loop/count protection, and sensitive-header removal on cross-origin changes
- [ ] 9.5 Implement complete bounded UTF-8 response admission, deterministic header normalization, non-2xx transport success, and atomic binary/oversized failure
- [ ] 9.6 Add per-owner/generation/package/process admission accounting, prompt OkHttp cancellation, terminal race gating, and content-private request diagnostics
- [ ] 9.7 Wire `network.http` through package validation, materialization, capability acquisition/revocation, actor mediation, resolver policy, and service composition without any OpenAI branch
- [ ] 9.8 Add MockWebServer tests for all methods, custom origins, status handling, headers/body, invalid URLs, HTTP rejection, TLS policy, redirects, sensitive headers, timeouts, cancellation, bounds, quotas, and no partial response
- [ ] 9.9 Add actor/JNI tests proving multi-suspension ownership, source/startup/SOS denial, resolver and work-effect authorization, close races, stale completions, and sibling isolation

## 10. Restricted Dynamic Resolver Actors

- [ ] 10.1 Add a one-shot resolver execution mode to native actor creation that bypasses channel startup/mailbox/readiness and always closes after one terminal result
- [ ] 10.2 Validate and load exactly the declared resolver module under the source-load effect guard and require a plain exact `{resolve=function}` export
- [ ] 10.3 Invoke `resolve` once with the exact detached request shape and validate all-or-nothing choices or normalized package error under finite result bounds
- [ ] 10.4 Enforce the resolver-specific module/capability allowlist and deny spawn, defer, sleep, work, audio, filesystem, keyboard, lifecycle, and unrelated profile authority
- [ ] 10.5 Implement a Kotlin resolver factory/orchestrator bound to active package, declaration, dependency, selected profile revision, caller identity, and one deadline
- [ ] 10.6 Add resolver concurrency quotas, cancellation, update/rollback/removal/profile-change invalidation, stale-result suppression, and content-private diagnostics
- [ ] 10.7 Publish resolver factories atomically with installed provider/profile metadata and route package-resolver sources through the generic dynamic-choice source registry
- [ ] 10.8 Integrate one-shot results into editor and readiness unavailable/reference states without making editor cache a runtime authorization
- [ ] 10.9 Add native resolver tests for module shape, isolation, allowed/denied operations, repeated suspension, timeout, close, malformed return, memory/instruction bounds, and no state leakage
- [ ] 10.10 Add JVM integration tests for concurrent editors, exact dependency grants, package/profile replacement races, result bounds, provider publication/removal, and no channel actor/work creation

## 11. Generic Durable Work Store

- [ ] 11.1 Define versioned queue, work, effect, epoch, tombstone, terminal-class, lease, and normalized failure models with opaque bounded payloads and no provider semantics
- [ ] 11.2 Implement strict atomic store encoding/decoding and transaction boundaries partitioned by repository, channel instance, declared queue, and work epoch
- [ ] 11.3 Implement FIFO submission that validates payload and quotas, allocates stable work identity/sequence, and commits before returning success
- [ ] 11.4 Implement one-active-job claim, waiter registration, safe claim release, FIFO wakeup, and process-restart reclamation for claims with no started effect
- [ ] 11.5 Implement atomic effect begin and committed normalized result/error memoization keyed within one Job, including same-key replay and incompatible duplicate rejection
- [ ] 11.6 Implement exactly-one terminal complete/fail/indeterminate/cancel transition, delayed-playback handoff ordering, content purge, and bounded non-sensitive tombstones
- [ ] 11.7 Implement cause-aware epoch preservation/retirement for unchanged restart, SOS, configuration/profile/package change, rollback/removal, instance deletion, explicit reset, and shutdown
- [ ] 11.8 Enforce queue/item/payload/effect/key/tombstone/storage/recovery quotas and isolate corrupt queue records without merging or interpreting partial data
- [ ] 11.9 Add a coroutine work coordinator that wakes bounded receives without polling or retaining threads and publishes only generic queue metadata
- [ ] 11.10 Add store state-machine tests for submission order, independent queues/instances, one active claim, committed effects, duplicate keys, terminal purge, tombstone bounds, and capacity rejection
- [ ] 11.11 Add crash-boundary tests at pre-submit, post-submit, post-claim, pre-effect, post-effect-start, post-effect-result, pre-terminal, and post-terminal persistence points
- [ ] 11.12 Add epoch/recovery tests proving safe reclaim, committed-effect memoization, ambiguous non-replay, intentional-replacement cancellation, package isolation, and corruption fail-closed behavior

## 12. Native `subspace.work` Runtime

- [ ] 12.1 Add locked state-local Queue, Job, and Effect userdata with nonrevealing labels and exact state/instance/generation/queue/work/task ownership checks
- [ ] 12.2 Implement synchronous `work.open` for declared queues with startup eligibility and no durable mutation
- [ ] 12.3 Implement yielding `Queue:submit` for input and managed-task owners with normalized payload validation and commit-before-success semantics
- [ ] 12.4 Implement yielding `Queue:receive` for managed tasks with no polling, at-most-one active Job, closure handling, and detached FIFO claims
- [ ] 12.5 Implement nonyielding `Job:payload` detached copies that cannot mutate the durable record
- [ ] 12.6 Extend the actor scheduler to run one protected nested yield-capable `Job:effect` function on the calling task after durable start and before durable result commit
- [ ] 12.7 Implement committed-effect fast return, raw-yield/nesting/concurrency denial, normalized success/error capture, Lua-throw classification, and function-reference release
- [ ] 12.8 Implement `Job:complete` and `Job:fail` terminal calls with exact once-only behavior and no provider-shaped result persistence
- [ ] 12.9 Reconcile active receives/effects exactly once during task failure, generation close, resolver denial, shutdown, and late host completion while leaving durable records host-owned
- [ ] 12.10 Wire all work operations through typed JNI outcomes and Kotlin host adapters without exposing IDs, paths, transactions, leases, or database objects to Lua
- [ ] 12.11 Add public API conformance tests for colon calls, exact methods, wrong arity/type, unsupported payloads, foreign/stale userdata, startup/input/task contexts, and deterministic errors
- [ ] 12.12 Add scheduler conformance tests for multi-yield effect functions, committed replay without invocation, nested effects, raw yields, close at each boundary, instruction/memory failure, and sibling-task isolation

## 13. Provider and Service Integration

- [ ] 13.1 Permit `handle_input` to suspend for durable submission and detach accepted work from Recording, route, callback, and input execution ownership before exact success
- [ ] 13.2 Permit startup to open declared queues and admit worker closures while deferring `receive` until successful Ready publication
- [ ] 13.3 Extend `LuaPackageMaterializer` to compile profile types, resolver/profile sources, work queues, multiline fields, and new capabilities without executing Lua or constructing unused adapters
- [ ] 13.4 Extend runtime construction to validate exact configuration/profile/dynamic-reference/work revisions and provide only detached grants plus generic adapters
- [ ] 13.5 Wire profile, secret, HTTP, resolver, and work capability leases through `ServiceChannelCapabilityHost`, actor mediation, and generation revocation
- [ ] 13.6 Reconcile selected-profile edits/deletes and package/configuration changes through `ChannelRuntimeRegistry` only after predecessor work authority retires
- [ ] 13.7 Compose generic profile/secret stores, HTTP adapter, resolver orchestrator, work store/coordinator, and package snapshot publication in service startup without changing built-in OpenAI composition
- [ ] 13.8 Implement unchanged-process-restart reconstruction that restores installed metadata, selected-profile grants, preserved work epoch, safe queue claims, and fresh Lua actors in deterministic order
- [ ] 13.9 Add materializer/provider tests proving declaration-only behavior, empty-declaration compatibility, exact grants, unavailable dependencies, resolver separation, and no OpenAI special case
- [ ] 13.10 Add service integration tests for input admission, background worker continuation, profile replacement, package update/removal, SOS, shutdown, restart recovery, and retained built-in operation

## 14. Generic Status, Privacy, and Failure Isolation

- [ ] 14.1 Extend runtime/channel snapshots with generic idle, queued, active, failed, and indeterminate work state plus bounded queued count and active presence
- [ ] 14.2 Project generic work state alongside readiness/preparation and delayed playback without transcript, prompt, response, tool, profile, secret, effect, or ledger content
- [ ] 14.3 Audit and enforce content-private diagnostics for profiles, secrets, HTTP, resolvers, queues, effects, actor failures, crash recovery, and package trust surfaces
- [ ] 14.4 Normalize subsystem failures so one malformed package/profile/queue/resolver/request cannot close sibling actors or corrupt unrelated package state
- [ ] 14.5 Add projection/privacy tests that inject sensitive sentinel content and prove only bounded identity, phase, counts, status class, and normalized outcomes escape host boundaries

## 15. External OpenAI Lua Package

- [ ] 15.1 Create the official `nilp0inter/openai-agent-channel` repository and exact revised-v1 manifest for one ordinary external provider with `openai_compatible`, `models`, `turns`, resources, configuration, and required capabilities
- [ ] 15.2 Vendor unmodified `leafo/lua-openai` v1.8.0 commit `d363da696a602b0a966d3942777e587c552363ba` with source provenance and MIT license
- [ ] 15.3 Vendor `leafo/tableshape` v2.7.0 commit `dc4a3b81a17fd68aa44ba715620ea79adcd84834` and only required pure-Lua LuaSocket v3.1.0 files from commit `95b7efa9da506ef968c1347edf3fc56370f0deed`, retaining exact licenses
- [ ] 15.4 Implement package-local `cjson`, `socket.http`, and required LuaSocket compatibility modules over `subspace.json` and `subspace.http` without host fallback or native modules
- [ ] 15.5 Implement the OpenAI-compatible profile declaration and one-shot model resolver using only package profile/secret/HTTP/JSON APIs with bounded normalized choices
- [ ] 15.6 Implement exact instance configuration for profile, model, multiline prompt, keyboard enablement, and dependent keyboard platform/layout/profile scalars
- [ ] 15.7 Implement startup/readiness/input callbacks that open one `turns` queue, admit one worker, validate generic dependencies, transcribe PTT audio, and durably submit nonblank text
- [ ] 15.8 Implement the serial FIFO worker with one volatile generation conversation, bounded non-streaming Chat Completions, `parallel_tool_calls=false`, stable effect keys, and finite turns/calls/bytes/deadlines
- [ ] 15.9 Implement exactly `type_text` and `press_enter` tool definitions, strict JSON argument validation, stable call-derived effect keys, semantic keyboard dispatch, and normalized paired tool results entirely in Lua
- [ ] 15.10 Implement final assistant-text synthesis, selection-aware delayed-playback admission, terminal completion ordering, bounded failures, and content-private package logs

## 16. OpenAI Package Conformance

- [ ] 16.1 Validate the exact package archive, source map, dependency modules, repository identity, manifest declarations, capability graph, bounds, and license inventory through the production package validator
- [ ] 16.2 Add compatibility tests proving the vendored OpenAI client operates unmodified through package `socket.http`/`cjson` adapters against a deterministic OpenAI-compatible test server
- [ ] 16.3 Add resolver tests for valid model lists, custom base URLs, authentication/protocol errors, malformed JSON, duplicate/oversized models, timeout, cancellation, and stale profile/package revisions
- [ ] 16.4 Add channel configuration/readiness tests for profile sharing, exact multiline prompt, model disappearance, unavailable queue/capability/profile/keyboard dependency, and no remote completion during readiness
- [ ] 16.5 Add input/worker tests proving transcription and durable admission precede callback success and later completion/synthesis/playback proceed without PTT ownership
- [ ] 16.6 Add Chat Completion tests for final text, bounded multi-turn tool loop, provider errors, malformed responses, call/turn/byte limits, non-streaming behavior, and no Responses/SDK fallback
- [ ] 16.7 Add keyboard tests for exact enabled tools, valid text/enter delivery, disabled/unknown/duplicate/malformed/parallel calls, normalized tool results, and ambiguous non-replay
- [ ] 16.8 Add restart tests for safe queued work, committed completion/tool effect memoization, started-uncommitted indeterminate outcomes, fresh volatile conversation, and intentional epoch retirement
- [ ] 16.9 Add isolation/privacy tests for sibling instances, separate conversations/queues/configuration, secret exclusion, content-free diagnostics, terminal purge, and independent built-in state

## 17. Publication and Device Acceptance

- [ ] 17.1 Build the OpenAI package deterministically from pinned sources and verify repeatable artifact digest, canonical ZIP layout, source-only content, size bounds, and license files
- [ ] 17.2 Publish `subspace-channel.zip` as a stable official GitHub release and record durable repository/release/asset IDs, package version, SHA-256 digest, and byte size in provenance
- [ ] 17.3 Install the published asset through the production GitHub discovery/download/validation/trust/commit path and prove no bundled or repository-name special case is used
- [ ] 17.4 Create one generic package profile and Lua channel instance while retaining a separately configured `builtin:openai-agent`, then verify independent catalogue identities and selections
- [ ] 17.5 Verify on the physical Android device that profile model discovery, readiness, PTT transcription, immediate durable admission, background completion, synthesis, and headset delayed playback work end to end
- [ ] 17.6 Verify on device that `type_text` and `press_enter` execute exactly once through the selected keyboard profile and return usable model-visible results
- [ ] 17.7 Verify device behavior for queued second turns, app backgrounding, process restart at safe and ambiguous work boundaries, SOS, profile edit, package update/removal, and unselected response retention
- [ ] 17.8 Verify authentication, unreachable endpoint, malformed response, unavailable keyboard, and quota failures expose bounded states without secrets/content and do not impair the built-in or sibling Lua channels
- [ ] 17.9 Remove the external package through the production path and prove its types/access/runtime become unavailable while retained package profiles and the built-in OpenAI Agent remain operational

## 18. Final Verification

- [ ] 18.1 Run the complete Rust `subspace-lua-actor` conformance suite covering revised modules, resolver mode, opaque userdata, durable effects, bounds, cancellation, and teardown
- [ ] 18.2 Run focused JVM suites for dependency validation/store, profiles/secrets, HTTP, dynamic choices/resolvers, Lua bridge/actor/provider, work recovery, service composition, UI state, and legacy OpenAI
- [ ] 18.3 Run the complete Gradle unit test suite and resolve only failures caused by the revised contract without weakening exact validation or security boundaries
- [ ] 18.4 Build the debug and release APK variants with the repository devshell toolchain and confirm the release variant remains unsigned when signing variables are absent
- [ ] 18.5 Re-run production artifact validation and side-by-side smoke acceptance using the exact published OpenAI package bytes and record the final observable evidence
- [ ] 18.6 Validate the OpenSpec change and confirm every proposal/spec acceptance criterion maps to completed implementation and evidence before declaring the change apply-complete
