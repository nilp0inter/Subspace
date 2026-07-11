## Context

Subspace already centralizes PTT admission, audio-route acquisition, capture, playback, and terminal ownership in `PttDispatcher` and `PttAudioSessionManager`. A selected channel is resolved by stable instance ID through `ChannelRuntimeRegistry`, which leases a committed `ChannelInputTarget` across catalogue replacement or removal.

The channel-facing boundary is useful, but the implementation around it is not yet open or safe enough for externally supplied runtimes:

- `ChannelKind`, sealed `ChannelConfig`, the catalogue validator/codec, service factory wiring, and UI switches define a closed Kotlin type universe.
- `configSchemaVersion` is persisted but neither validated nor migrated by an implementation owner.
- a definition without a registered factory is silently omitted from runtime projections;
- Keyboard recovery and SOS dispatch are identified by built-in kind or singleton ID;
- shared built-in controllers still own obsolete capture/route state and can be cancelled by one runtime instance;
- factory creation, readiness refresh, runtime close, and shutdown callbacks execute while the registry monitor is held;
- several audio-session failure/cancellation paths can skip route release or active-session clearance when callbacks throw;
- runtime work can execute on Android main, has no host deadline, and uses non-child coroutine jobs;
- runtime snapshot changes are sampled rather than continuously aggregated.

The intended future platform will load shareable Lua packages, allow multiple independently configured instances of one package, render package-declared configuration through native UI, expose audio through opaque handles, provide instance-scoped persistent state, and keep every hardware lifecycle under Subspace host ownership. This change does not embed Lua; it makes current Kotlin built-ins prove the provider, capability, and invocation boundaries that Lua will later use.

## Goals / Non-Goals

**Goals:**

- Preserve stable instance IDs, catalogue order, active-selection repair, atomic persistence, and existing built-in behavior.
- Replace closed built-in kind/config ownership with registered implementation providers and opaque, versioned configuration payloads.
- Keep a structurally valid definition visible and ordered when its provider is unavailable.
- Make provider/runtime invocation serialized, off-main, bounded, cancellable, exception-contained, and deterministically closable.
- Guarantee exact-once target notification, capture termination, route release, runtime-lease release, session clearance, and completion publication on every terminal path.
- Keep Android resources, hardware transports, connection/reconnection policy, shared engines, and cleanup in host capabilities rather than channel implementations.
- Move Sleepwalker discovery, BLE/GATT, reconnect, HID compilation/transport, ACK handling, and cleanup behind a semantic text-output capability.
- Preserve independent configuration and status for multiple instances using the same provider and shared host resources.
- Remove obsolete closed-world and controller-owned lifecycle paths after all callers use the hardened boundary.
- Add composition-level tests that exercise persisted definitions through provider resolution, routing, terminal cleanup, and shutdown.

**Non-Goals:**

- Embedding or selecting a Lua engine.
- Defining Lua callback names, VM topology, instruction/memory quotas, or coroutine syntax.
- Implementing a script package archive, installer, registry, signature authority, trust store, or update policy.
- Implementing script-owned persistent key-value state.
- Reimplementing Journal, Debug, or Keyboard in Lua.
- Exposing live PCM frames, Android objects, raw filesystem paths, Bluetooth/GATT objects, HID operations, SCO routes, capture sessions, or coroutine primitives to future scripts.
- Redesigning audio-route selection, capture devices, Telecom, Bluetooth reconnect outside Sleepwalker text output, Android Auto navigation, bootstrap, or model loading.
- Broadly decomposing `PttForegroundService`, rewriting `MainActivity` around a new state framework, or introducing a dependency-injection framework.

## Decisions

### D1: Separate channel instance identity from implementation identity

Replace `ChannelDefinition.kind: ChannelKind` and typed `ChannelConfig` with a stable implementation reference and an opaque configuration envelope. The durable shape is conceptually:

```text
ChannelDefinition
├── id: stable instance ID
├── name
├── implementationId
├── enabled
├── configSchemaVersion
└── configPayload: immutable JSON object
```

Built-in implementation IDs are stable namespaced strings such as `builtin:journal`, `builtin:debug`, and `builtin:keyboard`. The exact Kotlin wrapper shall reject blank or malformed identifiers but shall not enumerate installed implementations.

The catalogue owns only generic invariants: nonempty ordered definitions, unique nonblank instance IDs, a valid active ID, valid envelope fields, and lossless payload persistence. It does not decode provider-specific fields.

**Rationale:** Future package identity must not require an APK enum change, while multiple instances of one implementation retain independent IDs and configuration.

**Alternatives considered:**

- Add `LUA` to `ChannelKind`: rejected because all packages would still share one closed discriminator and package/config validation would leak into host switches.
- Keep typed configs alongside an opaque script config: rejected because it creates two provider models and forces later migration of all callers.
- Store package source inside each definition: rejected because reusable packages and multiple instances would duplicate implementation artifacts and couple catalogue mutations to package installation.

### D2: Resolve definitions through one implementation-provider registry

Introduce a `ChannelImplementationProvider` contract registered at the composition root. A provider descriptor owns:

- stable implementation ID;
- display label, summary, and unavailable/configuration presentation metadata;
- current configuration schema version;
- default opaque configuration;
- validation and stepwise migration of configuration payloads;
- native host-rendered configuration field schema;
- required host-capability declarations and generic preparation traits;
- suspendable runtime construction from one validated instance definition and an instance-scoped capability set.

Journal, Debug, and Keyboard become providers before any Lua provider exists. `ChannelRuntimeRegistry` remains the sole instance-to-runtime registry; no parallel controller or provider-runtime registry is added.

Provider runtime construction occurs outside registry locks and off Android main. A failed or missing provider creates an explicit unavailable runtime projection carrying the instance ID, name, implementation ID, enabled state, and actionable reason. It accepts no input but remains ordered and visible.

**Rationale:** This is the smallest seam that lets built-ins and future packages share persistence, presentation, validation, and runtime construction without spreading implementation switches across the host.

**Alternatives considered:**

- Put configuration decoding in the catalogue codec: rejected because unknown providers could not round-trip and each implementation would require host edits.
- Let the registry silently skip missing providers: rejected because persisted active instances disappear and users cannot diagnose, rename, reorder, or remove them.
- Add a second plugin registry beside `ChannelRuntimeRegistry`: rejected because runtime ownership, order, retirement, and lease semantics would diverge.

### D3: Persist a versioned generic catalogue and atomically migrate document v1

Introduce catalogue document v2. Loading supports both current v1 and v2:

- v1 `JOURNAL`, `DEBUG`, and `KEYBOARD` values map to the corresponding built-in implementation IDs;
- existing config objects are preserved as opaque payloads and validated by the matching built-in provider;
- seeded instance IDs, names, enabled values, order, and active selection are unchanged;
- the test-only fourth kind is not a production provider or persisted production type;
- after successful validation, the complete v2 snapshot is atomically written before publication;
- a failed migration leaves the original file and published state unchanged and surfaces an actionable bootstrap/load error.

The generic codec preserves unknown provider payload fields when reading and rewriting a v2 definition. Document-envelope versions remain distinct from provider config-schema versions.

Provider-specific migration runs stepwise from the stored configuration schema version to the provider's current version. Missing providers do not migrate or reinterpret their payloads; the original payload and version remain intact.

**Rationale:** A forward migration is necessary to remove the enum without losing existing installations. Lossless preservation is necessary for unavailable or newer providers.

**Alternatives considered:**

- Continue writing v1 with arbitrary string kinds: rejected because it would blur document compatibility and retain closed decoder assumptions.
- Silently default malformed provider config: rejected because it destroys user configuration and hides incompatibility.
- Reject the whole catalogue when one provider is absent: rejected because implementation availability is not structural catalogue corruption.

### D4: Use host-rendered provider configuration schemas

Define a small native configuration-schema model sufficient for existing built-ins: boolean, text, enum/choice, numeric values where needed, and host-mediated directory selection. Field IDs are stable; defaults and constraints belong to the provider descriptor. The provider validator remains authoritative after UI validation.

The dashboard discovers creatable providers and configuration metadata from the provider registry. Configuration navigation is addressed by instance ID and provider ID. An unavailable provider permits generic rename, move, enable/disable if supported, and remove operations, but does not present an implementation-specific editor.

**Rationale:** This proves the future package-manifest boundary while retaining native Compose rendering, validation, accessibility, and navigation ownership.

**Alternatives considered:**

- Keep one configuration screen and action interface per built-in: rejected because every package would require host UI code.
- Let providers render Compose directly: rejected because future scripts must not receive Android UI authority.
- Build a universal arbitrary UI tree: deferred; current built-ins need forms, not script-controlled application surfaces.

### D5: Add an instance-scoped semantic host-capability boundary

A provider declares required capabilities and receives an instance-scoped `ChannelHostCapabilities` bundle containing only semantic ports. Capability invocations carry package/provider identity, channel instance ID, invocation ID when applicable, cancellation/deadline context, and host-owned resource leases.

Initial ports adapt operations already used by built-ins, including transcription, synthesis/playback-result creation, Journal storage/derivation, and text output. Existing narrow stateless ports may be reused behind adapters, but runtimes do not receive Android contexts, concrete controllers, hardware transports, route objects, capture services, JVM file ownership, or shared coroutine scopes.

The host publishes generic capability availability:

```text
Available
Recoverable
Unavailable(reason)
```

Required recoverable capabilities may be acquired during bounded input preparation according to host policy. Channel code does not choose connection or retry policy.

**Rationale:** Channels should express domain intent while the host owns operating-system resources and shared services. Instance-scoped handles prevent one runtime from closing resources used by another instance.

**Alternatives considered:**

- Inject current controller classes into future adapters: rejected because controller lifecycle, Android dependencies, and shared state would become part of the script ABI.
- Expose a general service locator: rejected because it defeats capability review, isolation, and testability.
- Give runtimes direct filesystem or hardware access: rejected because cleanup, authorization, and portability would become runtime responsibilities.

### D6: Make Sleepwalker a host-owned text-output capability

Extract Sleepwalker ownership from `KeyboardPttController` into one host service. The service owns device identity, BLE/GATT connection state, reconnect/backoff, operation serialization, keymap/HID compilation, arm/send/ACK/disarm/kill sequencing, cancellation, and cleanup.

The Keyboard provider retains per-instance logical configuration such as host profile and channel behavior. Its runtime transcribes the committed recording and calls semantic operations equivalent to:

```text
sendText(text, profile) -> Delivered | FailedBeforeSend | Unavailable | Cancelled | Indeterminate
sendKey(key, profile?) -> same typed delivery family
```

The host may reconnect before transmission according to policy. It does not replay a whole text request after an ambiguous disconnect once transmission may have begun. `Indeterminate` reports that some output may have been delivered.

SOS dispatch resolves the active runtime/capability by instance ID; it does not compare against the migrated Keyboard singleton ID.

**Rationale:** Hardware lifecycle is a host responsibility, and non-idempotent text output requires one serialized authority. Per-instance profile remains channel configuration while transport implementation remains host-owned.

**Alternatives considered:**

- Keep reconnect inside `KeyboardRuntime.prepareInput`: rejected because generic routing must not know or delegate hardware policy to one channel kind.
- Expose raw HID operations to channels: rejected because it leaks protocol details and permits unsafe device state.
- Automatically replay after reconnect: rejected because partial delivery can duplicate text.

### D7: Replace readiness booleans and kind exceptions with generic preparation availability

Runtime snapshots expose enough generic state to distinguish ready input, recoverable preparation, and unavailable input with a reason. `decidePttDispatch` and pending-session logic use this state rather than testing `ChannelKind.KEYBOARD`.

During preparation, the host first resolves and, where policy permits, acquires required capabilities within a deadline. Only then does it commit the runtime target and start capture. Failure refuses input before the ready beep and releases any acquired capability leases.

Selection, reorder, rename, configuration replacement, and provider loss do not redirect a target already committed to a PTT session.

**Rationale:** Recovery is a capability property, not a built-in identity property. The same policy must support future host-managed hardware without core dispatch edits.

**Alternatives considered:**

- Keep `isReady` plus special cases: rejected because each recoverable provider would add another core branch.
- Let every runtime attempt arbitrary recovery after capture starts: rejected because failure would occur after the host signals readiness and records audio unnecessarily.

### D8: Interpose one host-owned runtime invocation executor

Every provider/runtime callback—create, prepare, start, release, playback completion, cancel, fail, readiness refresh, and close—runs through a host executor that provides:

- execution off Android main;
- per-runtime serialization unless an explicitly host-owned operation is safe to run concurrently;
- an injected deadline policy with deterministic tests;
- cancellation propagation that runtime code cannot convert into success;
- normalization of non-cancellation failures into typed unavailable/runtime-failure state;
- validated snapshot publication;
- suppression of effects and status publication after retirement/closure;
- invocation identity for capability leases and diagnostics.

Current Kotlin runtimes use the executor before a future Lua adapter does. The executor does not define Lua instruction or memory quotas; it supplies the host deadline and cancellation seam where an engine adapter will later enforce them.

`ChannelRuntime.close` becomes suspendable and idempotent. Runtime-owned jobs are explicit children of a runtime job and are cancelled and joined during close. Shared host capabilities are leased, not closed by runtimes.

**Rationale:** Trusted built-ins currently expose the same exception, blocking, and late-callback hazards that untrusted scripts would amplify. Proving the boundary with Kotlin makes Lua an adapter rather than a lifecycle redesign.

**Alternatives considered:**

- Add timeouts only around a future Lua adapter: rejected because host cleanup can still be stranded by Kotlin callbacks and lifecycle semantics would differ by language.
- Continue relying on `Dispatchers.Main.immediate` confinement: rejected because runtime IO/processing blocks UI and does not provide bounded cancellation.
- Let each runtime create arbitrary root scopes: rejected because service teardown cannot await or prove quiescence.

### D9: Keep registry locks structural and use two-phase lifecycle actions

Registry synchronization protects maps, ordered IDs, entry generations, retirement flags, and lease counts only. Reconciliation computes lifecycle actions under synchronization, executes provider creation/close outside synchronization through the invocation executor, then commits results only if the expected entry generation remains current. Stale creations are closed without publication.

Read APIs never wait for provider/runtime code. Readiness refresh schedules executor work outside the lock. Shutdown atomically stops admission, asks the audio-session manager to terminate and await the active session, then closes entries after their target leases terminate or a bounded forced-cleanup policy quarantines them.

**Rationale:** User or provider code under a monitor can block all routing and create lock-order deadlocks. Generation checks preserve current stale-preparation protection without holding the lock across suspension.

**Alternatives considered:**

- Hold the monitor across suspendable operations: rejected because it serializes unrelated reads and permits deadlock.
- Remove synchronization and rely on current main-thread convention: rejected because the contract is not enforced and future adapters resume on worker dispatchers.

### D10: Centralize exact-once audio-session terminal cleanup

Refactor `PttAudioSessionManager` terminal handling into one terminal state machine. Once a terminal claim succeeds, all paths converge on a cleanup operation that independently attempts:

1. setup-job cancellation or capture termination;
2. exactly one target terminal notification;
3. optional playback completion when normal release produced playback;
4. route release exactly once;
5. target/runtime lease release exactly once;
6. active-session clearance exactly once;
7. terminal completion publication exactly once.

Failures in any step are recorded but do not skip later cleanup. Cleanup runs in a host-owned context that is not cancelled when the service's ordinary work scope is cancelled, and has a bounded completion policy. Service shutdown awaits this operation before runtime destruction and final scope cancellation.

A timed-out or cancelled runtime callback cannot publish success or effects afterward. Capability handles are invalidated at terminal completion.

**Rationale:** Current failure/cancellation helpers invoke callbacks before unconditional route/session cleanup. External runtimes require cleanup that is stronger than callback behavior.

**Alternatives considered:**

- Add local `try/catch` blocks to individual helpers: rejected because terminal ownership and cleanup ordering would remain duplicated.
- Swallow every exception: rejected because diagnostics and runtime failure state are required even though cleanup must continue.

### D11: Aggregate runtime projections continuously and generically

`ChannelRuntimeRegistry` exposes an ordered aggregate state derived from catalogue order and each live or unavailable entry's snapshot. The service collects that aggregate rather than sampling individual `StateFlow.value` only on catalogue/readiness events.

Provider metadata supplies generic card subtitle, configuration availability, and preparation/unavailable messages. Phone and Android Auto projections retain every catalogue instance in order. Runtime status is ephemeral and never persisted into the definition.

**Rationale:** Script or Kotlin runtime status must propagate without service knowledge of implementation kinds. Unavailable entries must not disappear from any ordered surface.

**Alternatives considered:**

- Poll snapshots during service refresh: rejected because processing/status changes remain stale and polling frequency becomes UI policy.
- Add implementation-specific status collectors in the service: rejected because it recreates closed-world composition.

### D12: Complete the clean cutover in the same change

After built-ins use providers, host capabilities, generic preparation, and the invocation executor, remove:

- legacy `Channel`, `JournalChannel`, `DebugChannel`, and `KeyboardChannel` runtime/presentation models and conversion helpers;
- `ChannelRepository.loadChannels()` and post-migration legacy active-ID reads;
- production `TEST_FOURTH` persisted kind/config;
- unused alternative `ChannelRuntime.updateDefinition` behavior if replacement remains authoritative;
- singleton-ID SOS and other built-in identity branches;
- first-by-kind Journal recovery and Keyboard profile lookup;
- obsolete controller-owned PTT press/release, capture, route, and duplicated session state;
- built-in factory and editor switches superseded by providers.

No compatibility aliases or dual-read/dual-write paths remain after the v2 migration is committed.

**Rationale:** Retaining old and new ownership models would make later Lua integration choose between inconsistent paths and would preserve current instance-isolation defects.

## Risks / Trade-offs

- **[Risk] The v1-to-v2 catalogue migration could lose configuration or active selection.** → Decode v1 into an immutable intermediate form, map every built-in deterministically, validate through providers, atomically write before publication, and test all seeded configs, order, names, enabled values, and active IDs.
- **[Risk] Provider-specific validation could make one existing definition unavailable.** → Preserve the payload and instance, surface the exact validation reason, and never silently default or drop it.
- **[Risk] Moving factory/close operations outside the registry lock introduces stale results.** → Use entry generations and close stale creations before they become visible.
- **[Risk] Callback deadlines may interrupt currently long Journal derivation work.** → Separate bounded PTT terminal work from host-owned durable background derivation; the committed session must not wait indefinitely for encoding, transcription, or markdown regeneration.
- **[Risk] Centralized terminal cleanup can change route-release timing.** → Preserve endpoint-specific playback ordering and cover local, SCO, and car endpoints with focused exact-once tests before removing old helpers.
- **[Risk] Shared text-output service errors could affect every Keyboard instance.** → Serialize hardware actions, scope requests by instance/invocation, publish typed availability, and never let one runtime close the shared service.
- **[Risk] `Indeterminate` delivery is less convenient than automatic retry.** → Prefer truthful non-duplication semantics; reconnect restores future availability without replaying ambiguous side effects.
- **[Risk] A generic configuration schema may become an accidental full UI framework.** → Implement only field types required by current built-ins and keep rendering host-owned.
- **[Risk] Explicit unavailable entries can leave the active channel unable to accept PTT.** → Retain active identity and show actionable unavailable status; do not silently change user selection or redirect input.
- **[Trade-off] Runtime replacement resets volatile provider state on definition changes.** → Keep replacement semantics deterministic for this change; future persistent script state is separately versioned and out of scope.
- **[Trade-off] Catalogue v2 is a forward data migration.** → Rollback to a v1-only APK requires restoring the pre-migration catalogue backup or reverting before v2 activation; do not dual-write incompatible formats.

## Migration Plan

1. Introduce implementation IDs, opaque config envelopes, provider descriptors, provider registry, and v1 decoding without activating v2 writes.
2. Register built-in providers and prove validation/default/configuration-schema behavior against existing v1 data.
3. Add generic unavailable runtime entries and aggregate runtime projection while the existing built-ins still execute through current adapters.
4. Add the host capability bundle and migrate Journal/Debug domain services and Sleepwalker text output behind instance-scoped semantic ports.
5. Add the invocation executor, suspendable close, structural child jobs, and two-phase registry lifecycle actions.
6. Centralize audio-session terminal cleanup and make service shutdown await target/session cleanup before closing runtimes.
7. Replace kind-specific dispatch, SOS, first-by-kind lookup, dashboard creation/configuration, and browse projection with provider/instance-driven behavior.
8. Enable atomic catalogue v2 migration and verify restart, unavailable-provider preservation, multiple same-provider instances, and active/order identity.
9. Remove the legacy channel model, preference accessors, production test kind, direct controller PTT/capture/route paths, and obsolete switches.
10. Validate the complete persisted-definition → provider → registry → dispatch → terminal cleanup → shutdown path on JVM tests, then build/install and repeat existing device acceptance for Journal, Debug, Keyboard, hardware traversal, phone PTT, and Android Auto.

Rollback before v2 activation is ordinary code reversion. After activation, preserve a pre-migration v1 catalogue backup for operator rollback; normal forward operation reads and writes only v2.

## Open Questions

None for this change. Lua engine selection, package signing/distribution, package version pinning, VM topology, capability approval UX, script KV quotas/migration, and exact Lua API syntax are explicitly deferred to the future Lua-package proposal.
