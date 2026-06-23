# Status

Last updated: 2026-06-23

## Current State

The Android app is implemented as a native Kotlin/Compose app for the
`B02PTT-FF01` Bluetooth PTT device. The Bluetooth proof-of-concept works, the
main dashboard is now the default view, and the app is moving from hardware
validation toward the channel-router model defined in `PRODUCT_VISION.md`.

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

## Verified On Device

Reported working on physical Android hardware with `B02PTT-FF01`:

- App install and launch.
- Connection flow to monitor screen.
- Main dashboard launch, disconnected indicator routing, connected indicator routing, and back-to-dashboard behavior.
- PTT echo behavior.
- Background echo continuation after switching apps was implemented via foreground service and should be retested after reinstalling the latest debug build.
- Volume Up and Volume Down mapping was corrected after hardware testing.

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
- Inbound message streams, pending unheard message state, or last-heard message state.
- Autoplay, replay, skip, backlog consumption, or priority-channel capture.
- Hardware-driven channel menu, channel confirmation, or channel history mode.
- STT, TTS, command execution, webhooks, cloud services, assistant channels, integrations, or advanced pipelines.

## Next Useful Work

- Re-run the full manual acceptance checklist in `AGENTS.md` on the latest installed build.
- Replace static dashboard channels with the first real channel behavior.
- Add lightweight logging around SPP events and SCO route changes if manual testing reveals intermittent route loss.
- Add an in-app raw event/debug panel only if hardware debugging continues to require it.
