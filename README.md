# Subspace

Native Android MVP for validating the `B02PTT-FF01` Bluetooth PTT device.

Current implementation status is tracked in `STATUS.md`.

## Scope

The app implements the scaffold in `android-ptt-scaffold-spec.md`:

- two screens: connection and monitor
- Bluetooth Classic SPP/RFCOMM serial connection
- raw button token parsing and hardware mode tracking
- Bluetooth SCO communication-audio route validation
- in-memory PTT echo test with ready beep, recording, playback, and SCO keep-warm
- foreground service ownership while serial monitoring is active, so PTT echo can continue after switching apps
- bundled Chakra Petch and Inter fonts plus the visual identity from `VISUAL_IDENTITY.md`

Out of scope remains unchanged: no STT, no TTS, no command execution, no cloud service, no persistent recordings, no A2DP, no hidden Android Bluetooth APIs, and no phone mic/speaker fallback.

## Build

Use the repository Nix devshell. Do not install Android tooling globally.

```sh
nix develop --no-write-lock-file -c gradle test assembleDebug
```

The debug APK is produced under `app/build/outputs/apk/debug/`.

## Verification

```sh
nix flake check --no-write-lock-file
nix develop --no-write-lock-file -c gradle test
nix develop --no-write-lock-file -c gradle assembleDebug
```
