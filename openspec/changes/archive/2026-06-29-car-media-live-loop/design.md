## Context

Subspace already speaks to Android Auto through two services:

- `CarMediaSessionService` (a `MediaBrowserService`) exposes a single Media item with id `subspace-car-ptt` and updates `MediaSession` metadata + playback state from `CarMediaPttState`.
- `SubspaceConnectionService` (a self-managed Telecom `ConnectionService`) implements the on-the-road PTT cycle from `on-the-road-ptt-session/spec.md`. Hang-up is the authoritative release.

The user's phone-side live loop is implemented in `MainActivity` + `PttForegroundService` plus the channel cards in `MainDashboardScreen`. The state machine (`AppState`, `MonitorState`, `InputMode`, `InputModeAvailability`) and the `ChannelRepository` already expose an active channel id (`appState.activeChannelId`) and a per-channel readiness concept. `AndroidAutoPresenceBus` already tracks whether Android Auto is connected.

What does not yet exist:

- Per-channel **pending unheard** counter — `STATUS.md` lists it under "Not implemented yet". An observable per-channel "inbound backlog" of zero is the floor the car UI can render against until that capability is built.
- Channel listing observable consumed by the media service — the existing `CarMediaSessionService` renders one hardcoded row.
- Steering-wheel Skip semantics — `MediaSession.Callback.onSkipToNext/Previous` are not implemented; the existing `onPlay` callback delegates to `CarPttCommandBus.startTelecomCapture()`.

The change is consciously scoped to reshaping the Media-template surface. No `CarAppService`, no parked templates, no Notification messaging extender, no new permissions.

## Goals / Non-Goals

**Goals:**
- Project the live loop onto the Media template: see active channel, switch channel, talk, hear responses, skip/replay responses — all from steering-wheel controls or one-tap browse selections.
- Reuse `CarMediaSessionService`, `CarPttCommandBus`, `CarMediaStateBus`, `AndroidAutoPresenceBus`, `TelecomCarPttCoordinator`, and the on-the-road Telecom self-call lifecycle unchanged.
- Make the channel list observable to the media service without coupling MediaSession lifecycle to Compose UI.
- Auto-switch `InputMode` to `OnTheRoad` when Android Auto connects, on the same code path that already manages RSM availability.
- Provide a deterministic mapping from `CarMediaPttState` to MediaSession playback state + bitmap + now-playing metadata, and from steering-wheel Next/Prev events to channel-skip vs message-skip.

**Non-Goals:**
- Channel configuration on the car.
- Channel history review on the car (parked or moving). History stays on the phone's existing Journal screen.
- Notification-based async inbound delivery to the car. Assistant-style async channels are not implemented product-wide; when they ship, a follow-up change can introduce the messaging extender surface.
- An OEM-rendered custom Bitmap with live waveform animation. V1 ships state-tinted static drawables copied from `VISUAL_IDENTITY.md` palette. Animated waveform is a separate future change.
- Pending unheard backlog accuracy on the first cut. Per-channel pending counter is 0 until inbound backlog tracking lands; the subtitle simply renders the count when available and omits it when 0.

## Decisions

### D1 — Channels live in the Media browse tree, not in a CarAppService parked template

**Rationale.** The live loop is the foreground activity; the car contributes drivable surfaces. The Media template's browse-list + now-playing + steering-wheel transport is a complete projection of {active channel, talk, response playback, channel switching, contextual skip} without adding a new SDK or permission. Channel configuration and history review are parked-equivalent operations that already have phone homes.

**Alternatives considered.**
- Three-surface projection (Media + parked CarAppService + messaging notifications). Rejected because the user scoped config to phone-only (no parked review car surface) and async inbound channels are not yet implemented.
- Single row with channel name only in subtitle. Rejected because channel switching and contextual skip are first-class vision activities; without browse items the steering wheel cannot switch channels.

### D2 — Channel model becomes the MediaSession "track"

Each channel maps to a `MediaItem` with `FLAG_PLAYABLE`. Selecting the item (the head unit's "Play" on a browse item) calls `setActiveChannelId(channel.id)` and is equivalent to the phone dashboard's tap-to-activate. After selection, the now-playing card moves to that channel. The previously hardcoded `ROOT_ID = "subspace-car-ptt"` single row is removed.

**Why this over a single-state card.** It matches the radio-channel metaphor in `PRODUCT_VISION.md` exactly. The phone dashboard already exposes active-vs-standby via cards; the car honors the same model with `MediaItem` subtitles.

### D3 — `onLoadChildren` returns a fresh snapshot from a channel-list observable

`PttForegroundService` exposes a Flow<List<ChannelBrowseEntry>> where `ChannelBrowseEntry { id, name, statusKind, pendingCount }` is the media-session-facing projection of a channel. The media service subscribes on `onGetRoot`, calls `notifyChildrenChanged` on updates, and rebuilds the list on `onLoadChildren`.

**Why not read directly from `AppState` inside the service.** `AppState` is owned by `PttForegroundService` and already exposed via `appState: StateFlow`. The browse projection is a deterministic map from `AppState` to `List<ChannelBrowseEntry>`, computed as a Flow inside the service. The media service binds to `PttForegroundService` (already binds for the existing realisation); once bound it collects the browse flow.

### D4 — Contextual steering-wheel Next/Prev via playback-state predicate

`MediaSession.Callback.onSkipToNext` / `onSkipToPrevious` decide behavior based on the current `CarMediaPttState`:

| `CarMediaPttState` | `onSkipToNext` | `onSkipToPrevious` |
|---|---|---|
| `NotReady` | no-op | no-op |
| `Ready` (idle) | advance active channel one row | retreat active channel one row |
| `Recording` | no-op (cannot skip mid-capture) | no-op |
| `Finalizing` while inbound audio queued/playing | skip current inbound message | replay last heard message |
| `Finalizing` while idle | advance / retreat active channel | advance / retreat |

The decision is encapsulated in a pure function `CarSkipDecision.fromState(s: CarMediaPttState): CarSkipAction` so it is unit-testable without a `MediaSession`.

**Why not "double Next = skip".** Two-press idioms require state across callbacks, are OEM-fragile (some head units debounce repeated presses), and contradict the vision's "predictable controls" principle.

**Why allow Next during Finalizing.** `Finalizing` already encloses the post-capture playback window on the on-the-road path; this is when message skip is most actionable. `Recording` blocks Next because skip-during-capture disrupts the Telecom lifecycle.

### D5 —`PttForegroundService` exposes a channel-skip command

Active-channel switching is operationalized via the existing `setActiveChannelId(id)` path used by the phone dashboard. The media callback calls `CarPttCommandBus.setActiveChannel(relativeOffset)` which forwards to `PttForegroundService.setActiveChannelOffset(offset)`; this selects a channel ID adjacent to the current active in the stable channel ordering.

The ordering is driven from `ChannelRepository` (existing) so future channels inherit the same ordering as the phone dashboard.

### D6 — Now-playing bitmap is state-tinted, not channel-tinted

V1 ships four drawables (`car_art_not_ready`, `car_art_ready`, `car_art_recording`, `car_art_finalizing`) tinted with the existing palette from `VISUAL_IDENTITY.md`:

- Cyan `#00E5FF` on `Recording` (transmitting color)
- Amber `#FFB300` on `Finalizing` (playback color)
- Muted surface color on `Ready`
- Outline color on `NotReady`

This is state-only V1. Channel-identity artwork is deferred to V2 (uses the active channel's tag color, future change) and animated waveform is deferred to V3 (timeline-segmented bitmap redraws).

**Rationale.** State-only bitmap minimizes new art + avoids the per-channel-artwork problem when channels are user-created. Cost: subtitle carries the active channel name, which Android Auto can truncate on long channel names. Mitigation below.

### D7 — Now-playing metadata is the active-channel billboard

`MediaMetadata` fields used:

- `METADATA_KEY_TITLE` = active channel's display name
- `METADATA_KEY_ARTIST` = Subspace app name (existing)
- `METADATA_KEY_ALBUM` = one-line status string of shape `"<state pill> · <pending summary>"` (e.g. `"ACTIVE · 0 pending"`, `"RECORDING 04s · 2 pending on Captain's Log"`)
- `METADATA_KEY_ART_URI` / `METADATA_KEY_DISPLAY_ICON_URI` = state bitmap above (V1)
- `METADATA_KEY_DURATION` = current active inbound message duration when known, otherwise unknown

**Rationale.** Subtitle text is the existing `car_media_subtitle` lookup; replacing it with a synthesized per-channel subtitle is a strict upgrade. Android Auto truncates subtitle at roughly ~40 chars on most OEM cluster UIs; the design rule is to keep "state pill + pending summary" under 40 chars and to truncate pending breakdown ("3 pnlg on Captain's") when space is tight.

### D8 — InputMode auto-switch via `AndroidAutoPresenceBus`

`AndroidAutoPresenceBus` already fires `true` when AA clients subscribe. A new controller component (or extension of `InputModeController`) observes this signal:

- When presence transitions to `true` AND `InputModeAvailability.onTheRoad` is true → `setInputMode(OnTheRoad)`.
- When presence transitions to `false` AND `InputModeAvailability.work` is true → `setInputMode(Work)`.
- When the user has manually chosen a mode, Auto transitions do not override the choice for the remainder of that user-selected session; the auto-switch only fires when the current mode was set by auto-switch previously. This requires tracking `InputMode.selectedBy: User | System` — a small addition to `AppState`.

**Rationale.** The phone-side `InputModeSelector` lets the user override; auto-switch without a "was-system-set" flag would clobber intentional user choices when AA dropped/picked back up.

### D9 — Failure-safe skip semantics

`onSkipToNext/Previous` calling into `CarPttCommandBus` reuses the same failure-safe discipline as `onPlay`: if the underlying PTT session cannot start (mirrors `TelecomCarPttLifecycle.startRequest` returning `false`), no crash, no spurious audio; the existing `NotReady` playback state surfaces the error back to the head unit.

### D10 — Reuse `CarMediaStateBus` as the single state-source

`CarMediaSessionService.updateSessionState` continues to subscribe to `CarMediaStateBus`. The bus already supports a `Listener` pattern. The browse + skip paths read this same bus to decide behavior, ensuring one state datum drives all three surfaces (browse subtitle, now-playing metadata, skip decision).

## Risks / Trade-offs

- **OEM cluster UI subtitle truncation.** [Symptom: "Captain's Log · READY · 2 pending" truncated to "Captain's Log · READY". Mitigation] Keep subtitle under 40 chars by always truncating the pending portion first and falling back to a compact form like `"ACTIVE 2 pnlg"`. The now-playing card on the head unit shows the full title separately, so the active channel name is never lost.

- **Pending count is currently always 0.** [Risk: subtitle claim of pending backlog is misleading until `pending unheard message state` is implemented per `STATUS.md` "Not implemented yet". Mitigation] The render rule is: show `N pending` only when `N > 0`; render nothing about pending when zero. This keeps the car UI honest today and adopts the real pending count with no code change later.

- **OEM cluster lacks separate "Browse" controls.** [Symptom: cars with only `Play/Pause` + `Next/Prev` (Layout B) overload both controls. Mitigation] D4's contextual rule makes Next/Prev do both jobs dependent on state; choosing a channel via the head unit touch (browse tap) is the eyes-free fallback when the user is parked. While driving, Next-in-`Ready` is the only eyes-free channel switch and Prev is symmetric.

- **Active-channel switches mid-PTT.** [Risk: skip-to-active-channel during `Recording` would mutate `activeChannelId` mid-capture. Mitigation] D4 explicitly blocks Next/Prev during `Recording`; `onSkipToNext/Previous` no-op when `Recording`.

- **Breaking change: external consumers of the single `subspace-car-ptt` row.** [Risk: any test or external observer asserts the legacy single-item browse contract. Mitigation] Audit `app/src/test/` and `app/src/androidTest/` for fixtures that pin the root id, update them to enumerate the channel list, and document the new contract in the modified capability spec.

- **Channel ordering ambiguity.** [Risk: the car and phone render channels in different orders, confusing the user's mental model of "Next/Prev" → channel page. Mitigation] Channel ordering emanates solely from `ChannelRepository`; both surfaces consume it verbatim. The auto-switch (D8) introduces no new ordering.

- **`InputMode.selectedBy` flag is a model change.** [Risk: adding a field to `AppState` is a small data-model change. Mitigation] Defaulting to `User` keeps existing manual selection behavior unchanged; the flag only opt-in changes auto-switch behavior, no observable regression.

- **Test for the skip decision without a running MediaSession.** [Risk: contextual logic is OEM-behavior-dependent, hard to integration-test. Mitigation] Encapsulate in `CarSkipDecision.fromState` pure function with unit-test coverage on `Recording`, `Ready`, `Finalizing`, `NotReady` and on the empty-channel-list edge case.

## Migration Plan

Single-PR migration; no shipping artifact outside the APK changes form.

1. Audit existing code for references to the single `ROOT_ID = "subspace-car-ptt"` (the `MediaBrowserService` contract, tests asserting on `MediaItem.mediaId`).
2. Introduce `ChannelBrowseEntry` projection and ChannelRepository-derived Flow on `PttForegroundService`.
3. Add `CarSkipDecision.fromState` + unit tests; wire in `MediaSession.Callback`.
4. Add state-tinted drawables and metadata builder; wire to `CarMediaStateBus`.
5. Extend `InputModeController` with `AndroidAutoPresenceBus` observer and `InputMode.selectedBy` flag.
6. Update `CarMediaSessionService.onLoadChildren` to enumerate channel items; remove legacy root id contract.
7. Update tests; add new tests for browse enumeration, skip decisions, input-mode auto-switch.
8. Manual acceptance: per `AGENTS.md` device test checklist, add steps for: power-on AA head unit, see channel list, select a row, verify PTT begins on selected channel, Next/Prev switches when idle, Next during playback skips. Update `STATUS.md` "Verified On Device" once retested.

No data migration, no schema migration, no storage migration.

## Open Questions

- Channel ordering on the phone today is by `appState.activeChannelId` and is implicit. Should `ChannelRepository` introduce an explicit `orderIndex` per channel so the car browse row order is stable across config changes? Recommended: yes, even if v1 list is short, ordering drift is the single most user-confusing car UX bug.
- Should `Prev` wrap past the first channel or saturate? Recommendation: saturate. Wrapping on a wheel is disorienting.
- Should `Finalizing` still play Incoming response audio even when there's no inbound message but the channel finished a capture (e.g. Journal captures to disk)? Per `on-the-road-ptt-session/spec.md` "Response playback after capture" — already correct ("no response audio" path exists). Confirm the car subtitle is `"ACTIVE"` not `"RECORDING"` in this case so the user perceives "talk done, no reply". This is a one-line subtitle rule, decision deferred to implementation.
- Maximum bitmap size for `MediaMetadata.METADATA_KEY_ART` — the Android Auto spec recommends 320dp × 320dp for the large now-playing card. The drawables should be sized to that for the cluster card; smaller versions are auto-scaled by Android Auto. This is an asset-size decision, not a design decision.