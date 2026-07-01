## Why

The app currently treats channels as a fixed list of singleton channel implementations. That blocks the intended programmable-radio model where the user can create multiple configured instances of the same channel type, give each instance its own spoken/display name, and decide its position in the channel list.

## What Changes

- Add a user-configurable channel list made of ordered channel instances.
- Split channel identity into stable channel instance IDs and reusable channel type IDs.
- Treat the current built-in channels as channel types, with Debug Channel becoming a type that can have multiple independently configured instances.
- Let each channel instance store its display/spoken name, list position, channel type, and type-specific configuration.
- Render, route, navigate, and announce channels from the configured instance list instead of a fixed hardcoded list.
- **BREAKING**: Code and tests that assume singleton IDs such as one global Debug Channel must address channel instances and type-specific config separately.

## Capabilities

### New Capabilities
- `configurable-channel-list`: User-managed ordered channel instances, channel instance metadata, channel type reuse, and persistence semantics.

### Modified Capabilities
- `channel-framework`: Channel identity and persistence change from singleton channels to ordered channel instances backed by channel types.
- `debug-channel`: Debug becomes a reusable channel type with independent configuration per channel instance.
- `channel-routing`: Active-channel routing must dispatch by channel instance ID while resolving behavior from the instance's channel type and config.
- `main-device-dashboard`: The dashboard must render the configured channel instance list and open instance-specific configuration.
- `rsm-audio-navigation`: Hardware channel navigation and spoken announcements must use configured ordering and instance display names.
- `car-media-channel-browse`: Android Auto browse rows and active-channel metadata must use the configured channel instance list.

## Impact

- Affected Kotlin model/state code: channel models, `AppState`, channel repository/persistence, active-channel selection, and readiness projection.
- Affected service code: `PttForegroundService`, routing/dispatch decisions, debug-mode controller lookup, car command bus integration, and RSM announcement cache invalidation.
- Affected UI code: dashboard channel list, channel card actions, debug configuration flow, and any future channel creation/reordering controls.
- Affected Android Auto behavior: `ChannelBrowseEntry` projection and channel ordering consumed by `CarMediaSessionService`.
- Tests must cover duplicate channel types, stable ordering, instance-specific config, routing by instance ID, RSM spoken names, and migration/default seeding from the current fixed channel set.
