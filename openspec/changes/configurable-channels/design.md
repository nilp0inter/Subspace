## Context

Subspace currently models channels as singleton Kotlin data classes: `AppState` owns `journal`, `debugChannel`, and one `activeChannelId`; `ChannelRepository.loadChannels()` returns a fixed two-item list; routing and debug control switch on `JournalChannel.ID` and `DebugChannel.ID`. Recent car and RSM work already assumes a stable ordered channel list, but the ordering is still derived from hardcoded singleton channel types.

The requested change promotes the radio-channel metaphor to a user-owned list. A channel entry becomes an instance with its own stable ID, display/spoken name, list position, channel type, and type-specific configuration. The current built-in channels become channel types. Multiple instances can share the same type, so a user can create several Debug channels with different names, positions, and debug modes.

## Goals / Non-Goals

**Goals:**
- Replace fixed singleton channel state with a persisted ordered list of channel instances.
- Separate channel instance identity from channel type identity.
- Preserve exactly one active channel instance at a time.
- Let user-facing surfaces render, announce, select, and route by channel instance name and order.
- Support multiple Debug channel instances, each with independent Debug configuration.
- Seed or migrate existing installs to equivalent default channel instances.

**Non-Goals:**
- No remote sync or cross-device channel configuration backup.
- No Android Auto channel creation or editing surface; car remains browse/select/PTT only.
- No new channel types beyond converting current built-ins into reusable types.
- No user-defined executable channel logic or plugin system.
- No hidden Android APIs or new global tooling.
- No change to audio route semantics, PTT capture lifecycle, or input-mode selection except where they consume the dynamic channel list.

## Decisions

### D1 - Store channel instances as the source of truth

Introduce a persisted `ChannelInstance` model with `id`, `typeId`, `displayName`, `position`, and a typed config payload. `AppState` should expose `channels: List<ChannelInstance>` and `activeChannelId`, with helper projections for readiness and browse entries.

**Rationale.** A list of instances directly represents what the user edits and what RSM/car/dashboard navigate. It also removes the need for singleton fields that cannot represent duplicate Debug channels.

**Alternatives considered:**
- Keep `journal` and `debugChannel` fields and add duplicate-only side lists. Rejected because every routing and UI path would need two code paths.
- Store only channel types and derive instances by type. Rejected because names, order, and independent Debug mode are per-instance state.

### D2 - Use channel type adapters for behavior

Introduce a channel-type registry in code, keyed by stable type IDs such as `journal` and `debug`. Each adapter owns default instance config, readiness evaluation, config UI routing, and PTT dispatch behavior for instances of that type.

**Rationale.** The app needs static Kotlin behavior for each built-in type while allowing dynamic instances. Type adapters keep type-specific controllers out of generic list/repository code.

**Alternatives considered:**
- Encode behavior in the persisted config. Rejected because executable behavior should stay in trusted app code.
- Keep `when (channelId)` routing. Rejected because multiple instances can share one type but must have distinct IDs.

### D3 - Migrate by seeding default instances

On first load of the new schema, create default instances equivalent to the current fixed list: one Journal instance and one Debug instance, ordered by the existing order. Existing Journal and Debug config values are copied into those instances. If the saved active channel points at an old singleton ID, map it to the corresponding seeded instance ID.

**Rationale.** Existing users retain behavior after upgrade while future state uses only the new model.

**Alternatives considered:**
- Keep old singleton IDs as permanent instance IDs. Acceptable for seeded defaults, but not sufficient as a compatibility layer. The implementation should avoid ongoing old/new dual state.
- Reset all channel configuration. Rejected because it loses user-selected Journal paths and Debug mode.

### D4 - Position is explicit and normalized

Persist a numeric list position per instance. Repository load returns instances sorted ascending by position, with deterministic tie-breaking by stable ID. Reordering rewrites positions into a contiguous sequence.

**Rationale.** Phone dashboard, RSM next/previous, and Android Auto browse must share one ordering. Normalization prevents drift after insertions, deletions, and duplicate positions.

**Alternatives considered:**
- Sort by creation time or display name. Rejected because the user explicitly configures list position.
- Store only array order. Rejected for SharedPreferences-style persistence and future migrations where explicit positions are easier to validate.

### D5 - Active sessions capture the channel instance snapshot

When PTT starts, the active session records the selected channel instance ID and enough type/config snapshot data to release through the same type controller even if the user edits the list while recording.

**Rationale.** Press/release pairs must remain coherent. Editing channel config during a recording should affect the next capture, not the in-flight one.

**Alternatives considered:**
- Resolve current config again on release. Rejected because deleting/retyping/reordering during capture could release the wrong controller.

### D6 - UI editing is phone-only for this change

Add phone-side controls for creating channel instances from available types, naming them, editing type-specific config, and placing/reordering them in the list. Android Auto and RSM consume the resulting list but do not edit it.

**Rationale.** Creation and configuration require non-driving interactions. The hardware and car surfaces stay focused on live operation.

**Alternatives considered:**
- Add car-side parked configuration. Rejected as out of scope and inconsistent with prior car-media design constraints.

## Risks / Trade-offs

- [SharedPreferences serialization complexity] -> Use a small versioned repository format with explicit parse failures falling back only to seeded defaults when no valid new-format list exists.
- [Controller state collision between duplicate Debug instances] -> Dispatch by instance ID and pass instance config into the selected Debug mode controller; do not use one global `appState.debugChannel.mode` for all Debug instances.
- [Old active channel ID becomes invalid] -> Repository load clamps active selection to the first configured channel when the stored ID is absent.
- [RSM TTS cache uses stale names] -> Key announcements by instance ID plus display name/config version or invalidate cache when the channel list changes.
- [Order drift across phone and car] -> Make repository-projected sorted instance list the only source for dashboard, RSM, Android Auto, and skip-offset behavior.
- [Large first refactor touches service/UI/tests together] -> Implement through pure model/repository/projection functions first, then adapt service dispatch, then surfaces.

## Migration Plan

1. Add new channel instance, type, config, and repository serialization models with unit tests.
2. Add migration/seeding from existing Journal/Debug preference keys into the new list schema.
3. Replace fixed `AppState` channel fields with ordered channel instances and active instance ID.
4. Adapt routing and Debug dispatch to resolve by instance ID then type ID.
5. Adapt dashboard, RSM, and Android Auto projections to consume the same ordered instance list.
6. Keep old preference keys readable only as migration inputs; new writes go to the new channel-list format.

Rollback is source rollback plus retaining old preference keys. After a user writes only the new schema, rollback may lose channel-list edits made after the migration unless a separate downgrade path is added; no downgrade path is included in this change.

## Open Questions

- Should seeded default instance IDs equal the old singleton IDs (`captains-log`, `debug-channel`) for continuity, or use generated IDs with explicit old-ID migration aliases? Recommended: reuse old IDs for seeded defaults and generate unique IDs for user-created duplicates.
- Should deleting the active channel immediately activate the next item, previous item, or first item? Recommended: next item when available, otherwise previous, otherwise no active channel with PTT error-beep/no-op behavior.
- Should duplicate display names be allowed? Recommended: allow them but append no automatic suffix; instance identity is internal and users may intentionally name channels similarly.
