## Context

The application already centralizes PTT admission, route acquisition, capture, terminal ownership, and playback in `PttDispatcher`, `PttAudioSessionManager`, and `CaptureService`. A selected channel is admitted through `ChannelRouter.prepareInput(channelId)` and becomes a committed `ChannelInputTarget` for the lifetime of that PTT session.

The channel model above that boundary is closed over three singleton built-ins. `AppState` has one field per type, `ChannelRepository` uses per-type preference keys, ordering is an exhaustive subtype switch, the dashboard renders three dedicated cards, and `PttForegroundService` routes IDs to typed controller slots. Several controllers also retain legacy direct capture and route APIs alongside the host-controlled input-target path.

This change must generalize catalogue and execution ownership without changing Android audio-route policy, input-mode behavior, Telecom/RSM integration, PTT timing, or existing built-in results. It must preserve existing installations and leave a narrow future insertion point for Lua without introducing a scripting runtime now.

## Goals / Non-Goals

**Goals:**

- Establish one authoritative, persisted, ordered catalogue of channel instances and one valid active instance.
- Support add, edit, remove, and reorder operations for supported built-in channel kinds.
- Preserve existing Journal, Debug, and Keyboard configuration during a one-time migration.
- Derive phone, hardware, and Android Auto channel order from one catalogue snapshot.
- Resolve channel execution by opaque instance ID through a host-owned runtime registry.
- Run every existing built-in through the generic `ChannelInputAcceptance` / committed `ChannelInputTarget` path.
- Keep route acquisition, capture, playback ordering, terminal claims, and route release exclusively host-owned.
- Keep a committed target alive across selection, reorder, definition update, or removal until its PTT session terminates.
- Expose generic per-instance readiness and execution status while retaining typed built-in configuration.
- Remove obsolete closed-world model, routing, ordering, and duplicate controller-owned capture paths after migration.

**Non-Goals:**

- Embedding Lua or defining a scripting API, sandbox, capability grants, quotas, or script editor.
- Implementing generic inbound/outbound message streams, conversation history, pending backlog, or replay semantics.
- Adding network-defined channels or a generic processing-pipeline editor.
- Replacing kind-specific configuration editors with a universal schema-driven UI.
- Redesigning `PttAudioSessionManager`, `CaptureService`, audio-route resolution, Bluetooth, Telecom, or phone PTT gesture semantics.
- Allowing an empty catalogue; the application continues to maintain exactly one active channel.

## Decisions

### D1: Persist ordered channel definitions as the source of truth

Store one schema-versioned catalogue document containing the ordered definition list and active instance ID. List position is the order; no independent `orderIndex` is persisted, avoiding rank gaps and competing order authorities.

Each definition contains:

- stable opaque instance ID;
- user-visible name;
- built-in kind identifier;
- enabled state;
- configuration schema version;
- kind-specific typed configuration payload.

The catalogue repository validates schema version, unique nonblank IDs, supported kinds, configuration validity, nonempty definitions, and membership of the active ID before publishing a snapshot. Mutations operate under one repository lock and commit the complete next snapshot atomically using a temporary file followed by replacement. `org.json` is sufficient; no new persistence dependency is introduced.

**Alternatives considered:**

- Continue independent SharedPreferences keys: rejected because list mutations and active selection cannot commit atomically and multiple instances collide on keys.
- Persist integer ranks per item: rejected because moves require rank repair and can create duplicate/gapped order.
- Introduce Room or DataStore: deferred because the catalogue is a small document and the repository already contains atomic JSON-file precedents.

### D2: Migrate legacy built-ins once with stable compatibility IDs

When no catalogue document exists, read legacy Journal, Debug, Keyboard, and active-ID preferences, create three ordered definitions, and atomically persist them. Seeded definitions reuse the current built-in IDs so saved selection, media IDs, tests, and external observations remain stable. New instances receive generated opaque IDs independent of kind, name, and position.

After a successful catalogue commit, only the catalogue is read and written. Legacy keys are not dual-written. A failed migration leaves legacy preferences intact and retries on the next start.

**Alternatives considered:** permanent dual-read/dual-write compatibility was rejected because it creates two sources of truth and prevents independent instances.

### D3: Preserve exactly one active channel

The repository rejects removal of the final remaining definition. Removing the active definition selects the definition that occupied the next position before removal; if none exists, it selects the preceding definition. Add, update, reorder, remove, and selection each publish a snapshot whose active ID is valid.

**Alternatives considered:** allowing no active channel would require nullable selection and NotReady behavior across the entire PTT/car/hardware stack; prohibiting deletion of any active channel would force an unnecessary manual selection step. The chosen rule preserves current invariants and ordered-radio semantics.

### D4: Separate persisted definitions from ephemeral runtime snapshots

`ChannelDefinition` contains durable user configuration. `ChannelRuntimeSnapshot` contains instance ID, display name, readiness, generic execution status, and any presentation summary needed by consumers. Runtime status is never written into the definition document.

`AppState` carries the ordered runtime snapshots plus active ID instead of one typed field per built-in. Kind-specific editors load/update typed definition configuration by instance ID through service actions; they do not own catalogue state.

Configuration navigation and mutations retain the selected instance ID end to end, including asynchronous directory selection. A mutation replaces only the addressed definition; same-kind siblings retain their configuration and position. Runtime readiness represents valid configuration plus available dependencies, not whether a shared controller is currently activated for the selected instance.

**Alternatives considered:** storing readiness inside definitions was rejected because readiness depends on live resources such as model and BLE state.

### D5: Construct runtimes through kind factories and resolve them by instance ID

A `ChannelRuntimeFactory` is registered for each supported kind. The host-owned `ChannelRuntimeRegistry` reconciles the catalogue into `Map<ChannelId, ChannelRuntimeEntry>` and implements `ChannelRouter` by looking up the selected instance and asking its runtime to prepare input.

The registry tracks catalogue order explicitly rather than relying on runtime-map insertion order. Pure reorder reconciliation preserves runtime objects while immediately publishing snapshots in the latest catalogue order.

A runtime owns only channel-domain state and a structured child scope. Its contract covers readiness/status observation, definition updates, input preparation, and idempotent close. It does not receive route objects or own the recorder. Built-in-specific dependencies are supplied by their factories at the composition root.

Adding another kind may add a definition/config type, factory, and editor, but must not change `AppState`, PTT dispatch policy, browse projection, dashboard enumeration, or registry lookup.

**Alternatives considered:** a registry of existing controller singletons was rejected because it cannot support multiple instances or clean lifecycle disposal; a service `when (id)` merely relocates the current closed-world design.

### D6: Lease committed targets across registry reconciliation

Preparing input acquires a runtime-entry lease and returns a host wrapper around the committed target. The wrapper releases the lease exactly once after terminal release, cancellation, failure, or failed start. Selection and reorder do not retire runtimes. Updating or removing a definition retires the old runtime for new preparations but defers `close()` until all committed target leases finish.

Registry shutdown prevents new preparation, cancels the host PTT session, then closes every live or retired runtime exactly once. Runtime callback exceptions are converted into typed failure status; session cleanup and route release execute in host `finally` paths.

**Alternatives considered:** immediately closing on removal violates committed-target semantics; mutating one runtime in place for every configuration change risks mixing old and new per-session state. Factories may support safe in-place updates, but replacement-and-retirement is the default.

### D7: Make the host the sole PTT/audio lifecycle owner

All production built-ins execute through `PttAudioSessionManager` and `ChannelInputTarget`. Remove direct controller APIs that start/stop `CaptureService`, acquire/release routes, or retain separate PTT session state after every caller has migrated. Channel processing may use narrow domain capabilities such as transcription, synthesis, encoding, storage, or HID, but cannot receive `ResolvedAudioRoute`, `ScoRoute`, `CaptureSource`, or route cleanup authority.

Optional playback remains a validated `ChannelInputResult` executed by the session manager. Route cleanup remains exact-once even when a runtime callback fails.

### D8: Project every channel surface from one ordered snapshot

The phone dashboard repeats a generic channel card over the ordered runtime snapshots. Each card preserves tap activation, phone PTT, readiness/status, and a dedicated configuration action. A catalogue-management surface reachable from the channel panel supplies add, move, rename, and remove actions; kind-specific editors remain separate and are addressed by instance ID.

The management form exposes every supported production kind using controls that remain reachable at constrained phone widths. Built-in kinds are repeatable: removing a migrated seed instance does not remove that kind from creation or reserve its legacy ID as a singleton.

Android Auto browse entries and hardware offset selection consume the same ordered snapshot. Catalogue changes trigger browse invalidation. Fixed visual ordinals and the nonfunctional Command Uplink mock are removed.

### D9: Prove the boundary with built-ins and contract tests

Migrate Journal, Debug, and Keyboard factories before removing old paths. Tests must cover repository migration and corruption handling, catalogue mutations and active repair, registry reconciliation and deferred close, callback failure cleanup, all built-ins through generic routing, and identical phone/car/hardware ordering. A test-only fourth runtime kind must route without changes to core selection or projection code.

## Risks / Trade-offs

- **[Risk] Catalogue corruption could make channels unavailable at startup.** → Validate before publication, retain the last valid atomic file, fail closed with actionable bootstrap/recovery state, and never partially apply a mutation.
- **[Risk] Migration could lose existing configuration or active selection.** → Preserve legacy keys until a validated catalogue has been atomically committed and test every legacy built-in configuration combination relevant to current behavior.
- **[Risk] Runtime removal could race a committed PTT target.** → Retire entries, deny new leases, and close only after the final lease terminates.
- **[Risk] Built-in behavior could diverge while duplicate direct and generic paths coexist.** → Keep coexistence only during the cutover, test each built-in through the registry, then delete obsolete callers and APIs in the same change.
- **[Risk] Multiple instances may contend for singleton external resources such as the keyboard bridge or native STT/TTS engines.** → Factories share process resources through existing serialized services while keeping per-instance configuration/status separate; the global PTT host still permits only one active capture.
- **[Risk] Generic status may erase useful built-in diagnostics.** → Define a small common phase/readiness model and retain kind-specific detail in configuration/diagnostic projections rather than adding type switches to routing.
- **[Trade-off] Atomic JSON is intentionally simpler than a database.** → Revisit storage only when catalogue size, querying, or independent concurrent writers exceed the small single-owner document model.

## Migration Plan

1. Add the versioned catalogue model, validator, atomic store, mutation policy, and legacy migration while retaining current runtime consumers.
2. Project existing built-ins from catalogue snapshots and migrate active selection/order consumers.
3. Add the runtime/factory contracts and registry with lease-based retirement.
4. Adapt Journal, Debug, and Keyboard to registry-backed host input and generic status.
5. Move phone, hardware, and Android Auto projections and configuration actions to instance IDs.
6. Enable add/remove/reorder UI and verify persistence and active repair end to end.
7. Remove legacy preference writes, fixed state fields, ID/type switches, typed registry slots, fixed cards/routes, and direct controller-owned capture paths.

Rollback during development consists of reverting before catalogue activation; legacy keys remain available until migration succeeds. After release, rollback to a version unaware of the catalogue would read stale legacy values, so release notes and deployment must treat catalogue activation as a forward data migration.

## Open Questions

None. Lua, message history, backlog, and script capability policy are intentionally deferred to subsequent changes built on this boundary.
