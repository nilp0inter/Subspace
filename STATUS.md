# Status

Last updated: 2026-06-28

## Current State

The Android app is implemented as a native Kotlin/Compose app for the
`B02PTT-FF01` Bluetooth PTT device. The Bluetooth proof-of-concept works, the
main dashboard is now the default view, and the app is moving from hardware
validation toward the channel-router model defined in `PRODUCT_VISION.md`.

The Android Auto live-loop surface is now projected onto the Media template
(see `openspec/changes/car-media-live-loop`): each Subspace channel is a
browse item, the now-playing card carries the active channel + live PTT state
pill + compact pending summary, steering-wheel Next/Prev is contextual, and
`InputMode` auto-switches to OnTheRoad on Android Auto connection while
preserving explicit user selections.

Implemented:

- Gradle Android project using Kotlin, Compose, min SDK 31, target SDK 35, and Nix-provided Android tooling.
- Main dashboard as the default app view, with device connection status and static channel cards.
- Legacy connection screen for permissions, Bluetooth readiness, target discovery, pairing, SPP connection, and SCO availability.
- Legacy monitor screen for hardware mode, button states, echo controls, SCO state, and echo state.
- Dashboard connection indicator routes to the legacy connection view when not ready and the legacy monitor view when ready.
- Bluetooth Classic SPP/RFCOMM serial connection using UUID `00001101-0000-1000-8000-00805f9b34fb`.
- Serial parser for split, concatenated, NUL-suffixed, and noisy tokens.
- Hardware mode tracking for Active and Control modes.
- Corrected observed volume mapping: `C:VM*` is Volume Up and `C:VP*` is Volume Down.
- Bluetooth SCO route selection with public `AudioManager` communication-device APIs.
- In-memory echo test with 880 Hz ready beep, headset recording, headset playback, 60 second max recording, and 30 second SCO keep-warm.
- Foreground service ownership while serial monitoring is active, including connected-device and microphone foreground-service types.
- Visual identity baked in via bundled Chakra Petch and Inter font assets, Night Ops dark palette, Daylight Starfleet light palette, and field-terminal UI styling.
- Android Auto Media browse tree with one `MediaItem` per Subspace channel, ordered by `Channel.orderIndex` (JournalChannel index 0, DebugChannel index 1) so phone and car share the same ordering. Per-channel browse subtitle encodes ACTIVE / READY / STANDBY state and the compact `<N> pending` summary when the count is non-zero. The legacy single-item `subspace-car-ptt` browse contract is removed.
- `CarMediaSessionService` binds to `PttForegroundService`, collects the `Flow<List<ChannelBrowseEntry>>` projection, and drives `notifyChildrenChanged` whenever the channel list changes.
- Now-playing card metadata built from a pure `CarNowPlayingMetadata` builder: title = active channel name, artist = Subspace, subtitle = state pill + compact pending summary (truncates the pending portion first under 40 chars), bitmap = one of four state-tinted drawables (`car_art_not_ready`, `car_art_ready`, `car_art_recording`, `car_art_finalizing`) using the `VISUAL_IDENTITY.md` palette colors.
- Browse-item selection (`onPlayFromMediaId`) sets the active channel via `CarPttCommandBus.setActiveChannel(id)`. The legacy `onPlay` (no media id) path still starts a Telecom capture on the active channel.
- Contextual steering-wheel Next/Previous via a pure `CarSkipDecision.fromState` function: Next/Prev switches the active channel (saturating at the bounds, no wraparound) while Ready; skips/replays the current inbound message while Finalizing; no-op while Recording or NotReady. `CarSkipAction` enum is unit-testable without a running `MediaSession`.
- `PttForegroundService.setActiveChannelOffset(offset)` selects a channel by stable `Channel.orderIndex`, saturating at the bounds. Skip-message and replay-message command paths exist on `CarPttCommandBus`/`PttForegroundService` as no-op stubs with documented future wiring pending the inbound-backlog and last-heard-message-state capabilities.
- `InputMode` auto-switches to OnTheRoad on Android Auto connect and reverts to Work on disconnect, gated by the `InputMode.selectedBy: User | System` invariant on `AppState`/`InputModeController`. The existing user-initiated path records `User`; the new auto-switch records `System`. Manual user selections are preserved across AA transitions per the `car-input-mode-auto-switch` spec.
- Selection-by-offset, channel ordering, browse projection, now-playing metadata, skip decision, and InputMode auto-switch are all covered by JVM unit tests (no Android frame required).

## Verified Automatically

Passing commands:

```sh
nix flake check --no-write-lock-file
nix develop --no-write-lock-file -c gradle test assembleDebug
```

Covered by unit tests:

- Button parser token handling.
- Button state and hardware mode transitions.
- Connection readiness gate.
- Echo state machine cancellation, default timing, alternate timing, SCO keep-warm, and max-duration behavior.
- `ChannelBrowseEntry` projection: configured/unconfigured channels, active/ready/standby kinds, pending counts, stable `Channel.orderIndex`, `selectChannelByOffset` saturation without wraparound, and empty-channel-list edge case.
- `CarNowPlayingMetadata` builder: all four `CarMediaPttState` values with pending = 0 and pending = N, with active channel name and with not-ready fallback title, plus subtitle truncation-when-overflowing rule.
- `CarSkipDecision.fromState`: all five `CarSkipAction` values across the four `CarMediaPttState` values, plus the empty-channel-list edge case at offset-selection time.
- `InputModeController` auto-switch on AA connect/disconnect, `selectedBy` set to `System` on system transitions and `User` on explicit selections, the manual-selection-preserved invariant, and the availability-gated no-op.

## Verified On Device

Reported working on physical Android hardware with `B02PTT-FF01`:

- App install and launch.
- Connection flow to monitor screen.
- Main dashboard launch, disconnected indicator routing, connected indicator routing, and back-to-dashboard behavior.
- PTT echo behavior.
- Background echo continuation after switching apps was implemented via foreground service and should be retested after reinstalling the latest debug build.
- Volume Up and Volume Down mapping was corrected after hardware testing.

The new Android Auto live-loop surfaces verified working on device `CPH2653` (`a5c3b76a`):

- Android Auto Media browse list renders one row per Subspace channel in the stable `Channel.orderIndex` order (JournalChannel, then DebugChannel); subtitle carries ACTIVE / READY / STANDBY pill and forwards `notifyChildrenChanged` on channel-list updates.
- Selecting a channel row on the head unit switches the active channel on the phone dashboard; the now-playing card moves to that channel.
- Steering-wheel Next/Previous while Ready advances/retreats the active channel with saturation at the bounds (no wraparound); while Recording and while NotReady the controls no-op as specified.
- Steering-wheel Next/Previous while Finalizing dispatches to the `skipCurrentMessage` / `replayLastHeard` stubs (no-op pending inbound-backlog tracking); no ANR or capture-lifecycle disturbance observed.
- Now-playing title carries the active channel name; subtitle carries the live state pill (NOT READY / ACTIVE / RECORDING / FINALIZING); bitmap switches among `car_art_not_ready` / `car_art_ready` / `car_art_recording` (Subspace Cyan) / `car_art_finalizing` (Alert Amber).
- `InputMode` auto-switches to OnTheRoad on Android Auto connect and reverts to Work on disconnect when system-selected; manual user selections are preserved across AA connect/disconnect transitions.

## Build Output

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device:

```sh
nix develop --no-write-lock-file -c gradle installDebug
```

## Known Constraints

- Only `B02PTT-FF01` is supported.
- Android API 31+ only.
- No hidden Bluetooth APIs.
- No A2DP path.
- No phone speaker or phone microphone fallback.

## Product Gap To Vision

Not implemented yet:

- Real channel routing from captured PTT audio.
- Persistent channel list, channel configuration, or channel-specific behavior.
- Persistent message history.
- Inbound message streams, pending unheard message state, or last-heard message state. The car now-playing subtitle and skip/replay command paths render/accept the data structure but always no-op or render `0 pending` until this capability ships.
- Autoplay, replay, skip, backlog consumption, or priority-channel capture. Car steering-wheel Next/Prev while Finalizing dispatches to `skipCurrentMessage`/`replayLastHeard` stubs that no-op safely until inbound-backlog tracking lands.
- Hardware-driven channel menu, channel confirmation, or channel history mode.
- STT, TTS, command execution, webhooks, cloud services, assistant channels, integrations, or advanced pipelines.

## Next Useful Work

- Re-run the full manual acceptance checklist in `AGENTS.md` on the latest installed build, extended with the new Android Auto channel browse + skip + now-playing card steps above.
- Replace static dashboard channels with the first real channel behavior.
- Add lightweight logging around SPP events and SCO route changes if manual testing reveals intermittent route loss.
- Add an in-app raw event/debug panel only if hardware debugging continues to require it.
