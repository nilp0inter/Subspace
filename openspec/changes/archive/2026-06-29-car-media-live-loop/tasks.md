## 1. Channel listing model

- [x] 1.1 Introduce `ChannelBrowseEntry` data class in the model package with fields: `id`, `name`, `statusKind` (Active/Ready/Standby), `pendingCount`
- [x] 1.2 Add an explicit `orderIndex` to `Channel` (and `ChannelRepository` ordering) so phone and car render the same order
- [x] 1.3 Expose a `Flow<List<ChannelBrowseEntry>>` projection on `PttForegroundService` derived from the existing `AppState` + channel repository
- [x] 1.4 Add `InputMode.selectedBy: User | System` flag on `AppState` (default `User`); wire it through `InputModeController` so existing manual selection sets `User` and the new auto-switch sets `System`

## 2. Now-playing metadata builder

- [x] 2.1 Add four state-tinted drawables under `app/src/main/res/drawable/` (`car_art_not_ready.xml`, `car_art_ready.xml`, `car_art_recording.xml`, `car_art_finalizing.xml`) using `VISUAL_IDENTITY.md` palette colors
- [x] 2.2 Implement a pure `CarNowPlayingMetadata` builder that maps `(activeChannel, CarMediaPttState, perChannelPendingCount) -> MediaMetadata`
- [x] 2.3 Implement the subtitle rule: state pill + (only when `pending > 0`) compact pending prefix; truncate pending portion first under 40 chars
- [x] 2.4 Unit-test the metadata builder across all four `CarMediaPttState` values, with pending=0 and pending=N, with active channel and with not-ready channel

## 3. Skip decision function

- [x] 3.1 Define `CarSkipAction` enum: `NoOp`, `NextChannel`, `PrevChannel`, `SkipMessage`, `ReplayMessage`
- [x] 3.2 Implement pure `CarSkipDecision.fromState(state: CarMediaPttState): Pair<CarSkipAction, CarSkipAction>` (Next, Prev) with the table from design D4
- [x] 3.3 Add `CarPttCommandBus.setActiveChannelOffset(offset: Int)` and `CarPttCommandBus.skipCurrentMessage()` and `CarPttCommandBus.replayLastHeard()` calls; wire through to `PttForegroundService`
- [x] 3.4 Implement `PttForegroundService.setActiveChannelOffset(offset)` that selects a channel by stable orderIndex, saturating at the bounds (no wraparound)
- [x] 3.5 Implement skip/replay stub paths on `PttForegroundService` that no-op safely until inbound backlog tracking exists; document the future wiring path
- [x] 3.6 Unit-test the skip decision function across all `CarMediaPttState` values and the empty-channel-list edge case

## 4. CarMediaSessionService refactor

- [x] 4.1 Bind `CarMediaSessionService` to `PttForegroundService` (or extend the existing binding) to collect the `Flow<List<ChannelBrowseEntry>>`
- [x] 4.2 Replace the single hardcoded `ROOT_ID = "subspace-car-ptt"` item with one `MediaItem` per `ChannelBrowseEntry`; use channel id as mediaId and channel name + status subtitle
- [x] 4.3 On `MediaItem` selection via the head unit (`MediaSession.Callback.onPlay` for a browse item), call `setActiveChannel(id)` instead of unconditionally `startTelecomCapture()`
- [x] 4.4 Keep the legacy `onPlay` (when no media id is supplied) mapped to `startTelecomCapture()` for the active channel, preserving existing behavior
- [x] 4.5 Implement `onSkipToNext` and `onSkipToPrevious` in `MediaSession.Callback` consulting `CarSkipDecision.fromState` and dispatching to the right `CarPttCommandBus` call
- [x] 4.6 Drive `notifyChildrenChanged` whenever the channel list flow emits a new list
- [x] 4.7 Replace the metadata builder call in `updateSessionState` with the new `CarNowPlayingMetadata` builder
- [x] 4.8 Update existing tests that pin `subspace-car-ptt` as the root id

## 5. InputMode auto-switch

- [x] 5.1 Extend `InputModeController` to observe `AndroidAutoPresenceBus.connectivity` changes (existing bus)
- [x] 5.2 On transition to connected: if `InputModeAvailability.onTheRoad == true` AND (current mode is `OnAPinch` OR `selectedBy == System`), call `setInputMode(OnTheRoad, selectedBy=System)`
- [x] 5.3 On transition to disconnected: if `InputModeAvailability.work == true` AND (current mode is `OnTheRoad` AND `selectedBy == System`), call `setInputMode(Work, selectedBy=System)`
- [x] 5.4 Expose `setInputMode(mode, selectedBy)` API; existing user-initiated path passes `selectedBy=User`
- [x] 5.5 Unit-test auto-switch behavior including the manual-selection-preserved invariant and availability-gated no-op

## 6. Tests and validation

- [x] 6.1 Add unit tests for `ChannelBrowseEntry` projection: configured/unconfigured channels, ready states, pending counts
- [x] 6.2 Add unit tests for `CarNowPlayingMetadata` builder (covers all subtitle truncation rules)
- [x] 6.3 Add unit tests for `CarSkipDecision.fromState` (covers all five actions and the empty-channel-list edge)
- [x] 6.4 Add unit tests for the `InputMode` auto-switch including the `selectedBy` invariant
- [x] 6.5 Verify `nix develop --no-write-lock-file -c gradle test assembleDebug` passes
- [x] 6.6 Verify `nix flake check --no-write-lock-file` passes
- [x] 6.7 Run the existing `AGENTS.md` manual acceptance checklist plus the new Android Auto steps: connect AA head unit, observe channel browse list, select row, verify active channel changes, Next-while-idle switches, Next-while-finalizing skips
- [x] 6.8 Update `STATUS.md` "Verified On Device" section to include the new Android Auto channel browse + skip + now-playing card behaviors
- [x] 6.9 Update `STATUS.md` "Implemented" section to include the new Android Auto live loop surfaces