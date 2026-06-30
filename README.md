# Subspace

Subspace is a hardware-first, voice-first Android channel router for the
`B02PTT-FF01` Bluetooth PTT device.

The product goal is a programmable radio for voice tools: the user selects a
channel, holds PTT, speaks, releases, and lets that channel decide whether to
record, transcribe, forward, automate, respond, or do nothing immediately. The
phone screen is a monitor and configuration surface; the core live loop should
work from hardware controls whenever possible.

`PRODUCT_VISION.md` is the canonical product north star.

## Current Implementation

The current app is an implementation step toward that model. It validates the
target hardware and provides the first operator dashboard:

- Main dashboard with device connection status, functional channel cards, and phone-side PTT slide-lock.
- Legacy connection and monitor screens retained as hardware validation tools.
- Bluetooth Classic SPP/RFCOMM serial connection.
- Raw button token parsing and hardware mode tracking.
- Bluetooth SCO communication-audio route validation.
- In-memory PTT echo test with ready beep, recording, playback, and SCO keep-warm.
- Foreground service ownership while serial monitoring is active, so echo can continue after switching apps.
- Bundled Chakra Petch and Inter fonts plus the visual identity from `VISUAL_IDENTITY.md`.

Real channel routing, persistent channel history, channel configuration, channel
backlog, replay/skip behavior, priority-channel capture, and hardware-driven
channel/history navigation are not implemented yet.

## Repository Docs

- `PRODUCT_VISION.md`: long-term product model and interaction behavior.
- `VISUAL_IDENTITY.md`: visual language, typography, palette, and UI principles.
- `AGENTS.md`: development environment, tooling, and manual device-test instructions.

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
