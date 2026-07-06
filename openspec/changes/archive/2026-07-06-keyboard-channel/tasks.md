## 1. Vendor sleepwalker-core and wire Gradle

- [x] 1.1 Copy `android/sleepwalker-core/` from the sleepwalker repo into `sleepwalker-core/` at the Subspace repo root
- [x] 1.2 Add a `sleepwalker-core/UPSTREAM` file recording the pinned upstream commit
- [x] 1.3 Add `include(":sleepwalker-core")` to `settings.gradle.kts`
- [x] 1.4 Add `implementation(project(":sleepwalker-core"))` to `app/build.gradle.kts` dependencies
- [x] 1.5 `git add` the new module files so Nix flake evaluation can see them
- [x] 1.6 Verify `nix develop --no-write-lock-file -c gradle :app:dependencies` resolves the `:sleepwalker-core` project

## 2. KeyboardChannel model and repository

- [x] 2.1 Add `KeyboardChannel` data class to `Channel.kt` with `id`, `name`, `hostProfile: HostProfile`, `orderIndex = 2`, and `isReady` computed from bridge connection state (via a `bridgeConnectedProvider: () -> Boolean` constructor parameter)
- [x] 2.2 Add `KEYBOARD_ORDER_INDEX = 2` constant and a `KeyboardChannel -> KEYBOARD_ORDER_INDEX` branch in `Channel.orderIndex`
- [x] 2.3 Add `loadKeyboard()` and `saveKeyboard(channel)` to `ChannelRepository` with a `KEY_KEYBOARD_HOST_PROFILE` pref key; `loadKeyboard()` reads the persisted profile name (default `LINUX_US`) and takes a `bridgeConnectedProvider` for `isReady`
- [x] 2.4 Include `loadKeyboard()` in `loadChannels()` between debug and the sorted-by-orderIndex call
- [x] 2.5 Add a `KeyboardChannel` branch to `ChannelBrowseEntry`/`projectChannelBrowseEntries` so the Android Auto browse tree includes it
- [x] 2.6 Add JVM unit tests for `KeyboardChannel.isReady` (profile set + bridge connected → true; bridge disconnected → false) and `ChannelRepository.loadKeyboard`/`saveKeyboard` round-trip

## 3. Sleepwalker BLE connection manager

- [x] 3.1 Create `SleepwalkerBleConnection` in `app/src/main/java/dev/nilp0inter/subspace/bluetooth/` holding `BluetoothGatt`, RX/TX characteristics, negotiated MTU, and a `StateFlow<KeyboardConnectionState>`
- [x] 3.2 Define `KeyboardConnectionState` sealed enum: `Disconnected`, `Scanning`, `Connecting`, `Connected`
- [x] 3.3 Implement `connect(adapter, context)`: scan by device name `"sleepwalker"`, discover `BleUuids.SERVICE`, enable TX notifications, request MTU 247
- [x] 3.4 Implement TX notification handler: parse via `SessionStatusParser.parse(data)`, log status under `SubspaceRoute` tag with `seqId`/`statusName` (no payload content); do not block the send path
- [x] 3.5 Implement `suspend fun sendOp(op: LowLevelOp)`: frame via `op.toFrameBytes()`, chunk via `BleWriter.chunkFrame(frame, mtu)`, write each chunk to RX with `WRITE_TYPE_NO_RESPONSE`; no-op (or throw) if not `Connected`
- [x] 3.6 Implement `disconnect()`: close gatt, clear characteristics, transition to `Disconnected`
- [x] 3.7 Add a `FakeSleepwalkerBleConnection` for tests that records sent ops and exposes a configurable `connectionState`

## 4. KeyboardPttController

- [x] 4.1 Create `KeyboardStatus` sealed enum in `Models.kt`: `Idle`, `WaitingForAudio`, `Recording`, `MaxDurationReached`, `Transcribing`, `Typing`, `Done(text)`, `EmptyAudio`, `Cancelled`, `Error(reason)`
- [x] 4.2 Create `KeyboardPttController` in `app/src/main/java/dev/nilp0inter/subspace/channel/` mirroring `SttController`'s capture half (constructor: `scope`, `sco`, `captureService`, `source`, `output`, `transcriptionService: PcmTranscriber`, `connection: SleepwalkerBleConnection`, `hid: LowLevelHid`, `hostProfileProvider: () -> HostProfile`)
- [x] 4.3 Implement `onPttPressed(route)`: set `pttDown=true`, launch `startSession(route)` (capture + `WaitingForAudio` → `Recording`, `observeCompletion` for max-duration retention)
- [x] 4.4 Implement `onPttReleased(route)`: set `pttDown=false`, launch `finishSessionIfNeeded(route)` — stop capture, handle empty audio (`EmptyAudio`), else `Transcribing`
- [x] 4.5 Implement transcription: call `transcriptionService.transcribe(samples, sampleRate)`; on `EmptyInput`/`ModelNotReady`/`Failed` → `Error` + release route; on success → proceed to typing
- [x] 4.6 Implement typing: `TextPlanner(hid).plan(text, hostProfile)`; on `TextRenderingFailure` → `Error` (no HID sent) + release route; on success → `Typing`, `TapScriptCompiler.compile(ops, hid)`
- [x] 4.7 Implement send loop: `connection.sendOp(hid.arm())`, then each compiled op via `sendOp`, then `connection.sendOp(hid.disarm())`; on success → `Done(text)`; on exception mid-typing → `connection.sendOp(hid.kill())` if connected, then `Error`
- [x] 4.8 Ensure `output.releaseRoute()` is called in a `finally` after typing completes/fails, matching `SttController's discipline
- [x] 4.9 Implement `cancelAndRelease()`: cancel setup/completion/transcribe/typing jobs, cancel capture session, `kill()` if armed and connected, release route, set `Idle`
- [x] 4.10 Implement `setEnabled(value)` mirroring `SttController`: when disabled and idle, reset to `Idle`

## 5. PttForegroundService dispatch integration

- [x] 5.1 Add `lateinit var sleepwalkerConnection: SleepwalkerBleConnection` and `var keyboardController: KeyboardPttController?` fields to `PttForegroundService`
- [x] 5.2 Instantiate `SleepwalkerBleConnection` in `onCreate`; collect its `connectionState` into `MonitorState.keyboardConnectionState` via `updateMonitor`
- [x] 5.3 In the STT-ready block (after `sttReady.complete(transcriber)`), construct `KeyboardPttController` with the shared `sco`, `captureService`, `source`, `pcmOutput`, `TranscriptionService(transcriber)`, the connection, `LowLevelHidImpl()`, and `{ _appState.value.keyboard.hostProfile }`
- [x] 5.4 Add `KeyboardChannel.ID` arm to `dispatchPttPressed` calling `keyboardController?.onPttPressed(route)`
- [x] 5.5 Add `KeyboardChannel.ID` arm to `dispatchPttReleased` calling `keyboardController?.onPttReleased(route)`
- [x] 5.6 Add `KeyboardChannel.ID` arm to `cancelActiveSession` calling `keyboardController?.cancelAndRelease()`
- [x] 5.7 Update `updateActiveControllers()`: enable the keyboard controller only when `activeChannelId == KeyboardChannel.ID`; call `cancelAndRelease()` and reset status when not active
- [x] 5.8 Add `keyboardController?.cancelAndRelease()` and `sleepwalkerConnection.disconnect()` to `onDestroy` and the SPP-disconnect teardown path
- [x] 5.9 Wire `keyboard = channelRepository.loadKeyboard({ sleepwalkerConnection.connectionState.value == KeyboardConnectionState.Connected })` into the initial `_appState` load and a `setKeyboardHostProfile(profile)` action that persists and refreshes readiness
- [x] 5.10 Add `chan.${KeyboardChannel.ID}.name` / `.selected` entries to the supertonic TTS announcement string map

## 6. MonitorState and UI

- [x] 6.1 Add `keyboard: KeyboardChannel`, `keyboardStatus: KeyboardStatus`, `keyboardConnectionState: KeyboardConnectionState` fields to `MonitorState`/`AppState` in `Models.kt`
- [x] 6.2 Add `KeyboardStatus.displayText()` extension mirroring the `SttStatus` pattern
- [x] 6.3 Collect `keyboardController.status` into `MonitorState.keyboardStatus` via `updateMonitor` in the service
- [x] 6.4 Add a `KeyboardChannelConfigScreen` Composable: host-profile selector (sourced from `HostProfile.values()`), bridge connection state indicator, connect/disconnect button, and a status panel showing `keyboardStatus.displayText()` and last typed text
- [x] 6.5 Add `MainRoute.KeyboardConfig` to `MainActivity` enum and a dashboard card for the keyboard channel (name, readiness, active state) navigating to the config screen
- [x] 6.6 Add `setKeyboardHostProfile` to the `PttUiActions` interface and wire it through `MainActivity` to the service action

## 7. Manifest and permissions

- [x] 7.1 Verify `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are already declared in `AndroidManifest.xml` (they should be, for the RSM); add them only if missing
- [x] 7.2 Confirm no new runtime permission flow is needed (reuse the existing RSM permission grant); if the sleepwalker scan runs before the grant, gate it behind the existing permission check

## 8. Tests

- [x] 8.1 JVM test: `KeyboardPttController` successful cycle — fake capture source, `FakeSttTranscriber` returning `"hello world"`, `FakeSleepwalkerBleConnection` recording ops; assert status reaches `Done("hello world")`, `arm`/typed/`disarm` ops sent in order, route released
- [x] 8.2 JVM test: empty audio → `EmptyAudio`, no transcription call, no HID ops, route released
- [x] 8.3 JVM test: STT model not ready (`TranscriptionException.ModelNotReady`) → `Error`, no HID ops, route released
- [x] 8.4 JVM test: transcription failure (`TranscriptionException.Failed`) → `Error`, no HID ops, route released
- [x] 8.5 JVM test: unrepresentable glyph — `TextPlanner` returns `TextRenderingFailure.UnrepresentableGlyph` → `Error`, no `arm`/HID ops sent, route released
- [x] 8.6 JVM test: error during typing (fake connection throws on send mid-stream) → `kill` op sent, status `Error`, route released
- [x] 8.7 JVM test: `cancelAndRelease` cancels in-flight typing, sends `kill` if armed, resets to `Idle`
- [x] 8.8 JVM test: `KeyboardChannel.isReady` transitions with bridge connection state
- [x] 8.9 JVM test: `ChannelRepository` keyboard load/save round-trip and inclusion in `loadChannels()`

## 9. Build and verification

- [x] 9.1 `nix develop --no-write-lock-file -c gradle :app:assembleDebug` succeeds
- [x] 9.2 `nix develop --no-write-lock-file -c gradle test` passes (new keyboard tests green)
- [x] 9.3 `nix develop --no-write-lock-file -c gradle :app:installDebug` succeeds on `B02PTT-FF01`
- [x] 9.4 Manual device flow: pair sleepwalker bridge, activate keyboard channel, press/release RSM PTT, observe typed text on the paired host
