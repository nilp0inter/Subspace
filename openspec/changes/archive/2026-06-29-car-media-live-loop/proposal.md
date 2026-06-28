## Why

The Android Auto surface today is a single MediaBrowser row titled "Subspace ready / recording / finalizing". The user has no way to see which channel is active, which channels have pending unheard backlog, or to switch channels and skip/replay inbound messages from the steering wheel. `PRODUCT_VISION.md` defines these as first-class live activities — none of them currently surface on the car.

The opportunity: project the live-loop portion of the product model onto Android Auto using only the Media template that already works (`CarMediaSessionService` + Telecom self-call). Channel configuration and history review stay on the phone, so the car becomes a dedicated live-loop surface.

## What Changes

- The Android Auto Media browse tree exposes one media item per Subspace channel (Journal, Debug, Priority Ass't placeholder, future channels). Each item's subtitle carries the per-channel state (ACTIVE / READY / STANDBY / N pending).
- Selecting a media item sets that channel as the single active Subspace channel. The now-playing card reflects the active channel's name, live PTT state, and pending-backlog summary.
- The `MediaSession` playback-state bitmap now varies with `CarMediaPttState` (NotReady / Ready / Recording / Finalizing), using the Subspace visual identity palette.
- `MediaSession.Callback.onSkipToNext` / `onSkipToPrevious` become contextual:
  - While idle (`Ready`), Next/Prev switches the active channel (radio-dial).
  - While audio is playing or finalizing a response, Next skips the current inbound message and Prev replays the last heard message.
- The Priority channel is exposed as a regular top-listed browse row. There is no car-side long-press shortcut; a long Play/Pause hold is not relied on.
- `AndroidAutoPresenceBus` connection transitions auto-switch `InputMode` to `OnTheRoad` when available, and back to `Work` when Auto disconnects while the RSM is bonded.
- The existing single-row `ROOT_ID = "subspace-car-ptt"` browse contract is removed. **BREAKING**: any external consumer (test fixtures included) that relied on the single root item being `subspace-car-ptt` will need to enumerate channel items instead.
- History review, channel configuration, and journal directory selection are explicitly NOT moved to the car. They remain on the phone's existing screens.

## Capabilities

### New Capabilities
- `car-media-channel-browse`: One media item per channel in the Android Auto Media browse tree, with per-channel status subtitle and selection → active-channel navigation.
- `car-contextual-skip-controls`: Contextual semantics for steering-wheel Next/Prev on the Subspace MediaSession — channel switching while idle, response skip/replay while audio is active.
- `car-input-mode-auto-switch`: Auto-switch `InputMode` to `OnTheRoad` on Android Auto connect and back to `Work` on disconnect when the RSM is bonded.

### Modified Capabilities
- `android-auto-virtual-ptt`: Enriches the existing "Virtual PTT provides driver-safe feedback" requirement with the now-playing surface contract (active channel name in title, live state pill + pending backlog summary in subtitle, state-driven bitmap).

## Impact

- Affected Kotlin files: `CarMediaSessionService`, `CarPttCommandBus`, `CarMediaStateBus`, `AndroidAutoPresenceBus`, `MainActivity` (or the service that owns `InputMode`), `PttForegroundService` (channel-list + active-channel subscriptions exposed to the media service), `ChannelRepository` / `Channel` model (channel display metadata for browse subtitles).
- New code path: a channel-list observable consumed by `CarMediaSessionService.onLoadChildren` and an active-channel selection path that calls into the existing `setActiveChannelId` action.
- New resources: `MediaMetadata` bitmaps per `CarMediaPttState` (and optionally per active channel identity); state-tinted drawables duplicating `VISUAL_IDENTITY.md` already-defined colors.
- Existing tests that assert the legacy single-row browse contract will need updating; new unit tests cover the contextual skip decision function and the channel-to-browse-item mapping.
- No new Android permissions. No `CarAppService` is introduced.
- Existing `TelecomCarPttCoordinator` / `SubspaceConnectionService` / `on-the-road-ptt-session` lifecycle is unchanged. The change consumes their behavior.
- Distraction-optimization: no parked-only screens are added, no custom templates, no keyboard use — the surfaces stay driveable under default Android Auto restrictions.