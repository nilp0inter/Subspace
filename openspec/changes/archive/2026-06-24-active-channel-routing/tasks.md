## 1. Domain Model Updates

- [x] 1.1 Remove `enabled` from `Channel` interface in `Models.kt`.
- [x] 1.2 Add derived `isReady: Boolean` property to `Channel` interface.
- [x] 1.3 Implement `isReady` on `CaptainsLogChannel` (checks if baseDirectory is not null and at least one save toggle is true).
- [x] 1.4 Implement `isReady` on `DebugChannel` (hardcoded to true).
- [x] 1.5 Add `activeChannelId: String` to `AppState`, defaulting to `CaptainsLogChannel.ID`.
- [x] 1.6 Update default initializers to remove `enabled` usages.

## 2. Audio Subsystem Updates

- [x] 2.1 Add `suspend fun playErrorBeep(coldStart: Boolean = false)` to `PcmOutput` interface in `AudioPorts.kt`.
- [x] 2.2 Implement `playErrorBeep` in `AndroidPcmOutput` (`AndroidAudio.kt`) using `generateSinePcm16` for a two-tone sequence (e.g., 400Hz then 300Hz, 150ms each).

## 3. PTT Routing Logic Updates

- [x] 3.1 Update `dispatchPttPressed()` in `PttForegroundService.kt` to resolve the current channel from `appState.activeChannelId`.
- [x] 3.2 Add conditional check in `dispatchPttPressed()`: if the active channel `!isReady`, acquire SCO, call `playErrorBeep(sco.coldStart)`, release SCO, and do not route to any controller.
- [x] 3.3 Update routing in `dispatchPttPressed()` to call the respective controller only if the active channel is ready.

## 4. UI Layer Migration

- [x] 4.1 Create `CaptainsLogConfigScreen.kt` and move existing Captain's Log configuration UI (toggles, directory selector, permission buttons) from `MainDashboardScreen.kt`.
- [x] 4.2 Create `DebugChannelConfigScreen.kt` and move Debug Channel configuration UI (mode selector).
- [x] 4.3 Add a new action `setActiveChannel(id: String)` to `PttUiActions` and implement it in `MainActivity` (or ViewModel).
- [x] 4.4 Update `PttUiActions` to support navigating to the configuration screens (e.g. `navigateToCaptainsLogConfig()`).
- [x] 4.5 Refactor `MainDashboardScreen` to remove inline configuration controls from channel cards.
- [x] 4.6 Update channel cards in `MainDashboardScreen` to display an active/standby state and a ready/not-ready status pill based on the new model.
- [x] 4.7 Wire card tap to dispatch `setActiveChannel` and add a \"Config\" icon button to each functional card that triggers the navigation actions.