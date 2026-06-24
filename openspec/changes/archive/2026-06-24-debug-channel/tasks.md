## 1. Data Model and Repository

- [x] 1.1 Create `DebugMode` enum (`ECHO`, `STT`, `TTS`, `STT_TTS`) in `model` package
- [x] 1.2 Create `DebugChannel` data class implementing `Channel` in `model` package
- [x] 1.3 Update `ChannelRepository` to persist and load `DebugChannel` state
- [x] 1.4 Update `AppState` to include `debugChannel` state alongside `captainsLog`

## 2. Monitor Screen Cleanup

- [x] 2.1 Remove Echo, STT, TTS, and STT↔TTS test toggles and related UI components from `MonitorScreen.kt`
- [x] 2.2 Remove obsolete test toggle functions from `PttUiActions` that were specific to the monitor screen

## 3. Dashboard and Configuration UI

- [x] 3.1 Create `DebugChannelConfigScreen` with mutually exclusive radio button options for selecting the `DebugMode`
- [x] 3.2 Add `setDebugChannelEnabled` and `setDebugChannelMode` to `PttUiActions`
- [x] 3.3 Replace the static "Diagnostics" mock channel on `MainDashboardScreen` with a live `DebugChannel` card
- [x] 3.4 Wire the `DebugChannel` card to display its current `mode` and `enabled` state, and navigate to the configuration screen

## 4. Service and Native Routing

- [x] 4.1 Delete `AudioRouter.kt` entirely
- [x] 4.2 Update `setCaptainsLogEnabled` in `PttForegroundService` to automatically set `debugChannel.enabled = false` (mutual exclusion)
- [x] 4.3 Implement `setDebugChannelEnabled` in `PttForegroundService` to automatically set `captainsLog.enabled = false`
- [x] 4.4 Implement `setDebugChannelMode` in `PttForegroundService` and persist changes
- [x] 4.5 Update `onPttPressed` and `onPttReleased` in `PttForegroundService` to route directly to either `captainsLogPttController` or the debug controllers based on which channel is active
- [x] 4.6 Dispatch to the correct diagnostic controller (Echo, STT, TTS, STT↔TTS) when `DebugChannel` is active, based on its selected `mode`