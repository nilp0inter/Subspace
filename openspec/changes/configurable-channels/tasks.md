## 1. Channel Instance Model And Persistence

- [x] 1.1 Replace singleton channel state with channel instance/type/config models in `app/src/main/java/dev/nilp0inter/subspace/model`.
- [x] 1.2 Add a typed channel-type registry for Journal and Debug behavior defaults, readiness, and dispatch metadata.
- [x] 1.3 Implement versioned channel-list persistence in `ChannelRepository`, including ordered load/save and position normalization.
- [x] 1.4 Implement migration/default seeding from existing Journal and Debug preference keys into the new channel-list schema.
- [x] 1.5 Add repository/model tests for fresh install defaults, existing install migration, duplicate Debug instances, duplicate position tie-breaking, active-channel recovery, and empty-list behavior.

## 2. App State And Projections

- [x] 2.1 Update `AppState` to expose ordered channel instances and active channel instance ID instead of fixed `journal` and `debugChannel` fields.
- [x] 2.2 Update channel readiness and browse projection functions to operate on channel instances and type-specific config.
- [x] 2.3 Update ordered channel ID and offset-selection helpers to use configured instance ordering and saturating bounds.
- [x] 2.4 Update projection tests to cover multiple instances of the same type, configured display names, stable order, unknown type not-ready status, and empty channel lists.

## 3. Routing And Controllers

- [x] 3.1 Update PTT dispatch decisions to resolve the active channel instance, its channel type, and its readiness state.
- [x] 3.2 Update `PttForegroundService` active-channel selection, offset navigation, and persistence writes to use channel instance IDs.
- [x] 3.3 Update Debug dispatch so each Debug channel instance uses its own stored Debug mode instead of one global Debug channel mode.
- [x] 3.4 Ensure active PTT sessions retain the channel instance dispatch context selected at press time until release completes.
- [ ] 3.5 Add service/dispatch tests for routing duplicate Debug instances, not-ready instances, unknown types, missing active IDs, and list changes during active PTT.

## 4. Phone UI Configuration And Dashboard

- [x] 4.1 Update the dashboard channel panel to render configured channel instances in repository order with configured display names.
- [x] 4.2 Update channel card activation, phone PTT gesture, and config-button actions to pass channel instance IDs.
- [ ] 4.3 Add or adapt phone-side configuration flows for creating channel instances from built-in types, editing display name, editing position, and editing type-specific config.
- [x] 4.4 Make Debug configuration instance-specific, including mode selection, display name, and position edits.
- [ ] 4.5 Add UI/state tests or pure reducer tests for add-instance, rename, reorder, duplicate Debug configuration, and active-channel preservation.

## 5. RSM And Android Auto Surfaces

- [x] 5.1 Update RSM control-mode next/previous navigation to consume configured channel instance order.
- [x] 5.2 Update RSM TTS announcement keys/cache invalidation so spoken channel names follow instance display-name edits.
- [x] 5.3 Update Android Auto `ChannelBrowseEntry` generation and media IDs to use channel instance IDs and configured display names.
- [ ] 5.4 Update car browse and now-playing tests for duplicate channel types, configured ordering, renamed channels, and empty/not-ready lists.

## 6. Verification

- [ ] 6.1 Run `nix develop --no-write-lock-file -c gradle test` and fix regressions caused by this change.
- [ ] 6.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug` and fix build errors caused by this change.
- [ ] 6.3 On device `B02PTT-FF01`, verify default migration, adding two Debug instances with different names/modes, RSM spoken navigation, dashboard ordering, phone PTT routing, and Android Auto browse ordering.
- [ ] 6.4 Update project status/docs if implementation changes user-visible behavior or verification status.
