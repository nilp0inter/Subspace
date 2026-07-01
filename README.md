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

## Continuous Integration

GitHub Actions CI runs on every pull request, push to `main`, and manual
dispatch. The workflow uses the same Nix devshell as local development — no
standalone Android, Java, or Gradle setup actions are used.

CI runs these commands in order:

1. `nix flake check --no-write-lock-file`
2. `nix develop --no-write-lock-file -c gradle --version`
3. Provision the debug keystore from the `ANDROID_DEBUG_KEYSTORE_BASE64` repository secret to `.android/debug.keystore`
4. `nix develop --no-write-lock-file -c gradle test`
5. `nix develop --no-write-lock-file -c gradle assembleDebug`

The debug APK is published as a workflow artifact named `subspace-debug-apk`,
sourced from `app/build/outputs/apk/debug/*.apk` with 14-day retention.

The debug keystore is required because `app/build.gradle.kts` pins its path
to `rootProject.file(".android/debug.keystore")`. The path is gitignored
(see `.gitignore`), so CI provisions the file from a base64-encoded secret
on every run. See `RELEASE_SIGNING.md` for keystore setup.

## Releases

Releases are tag-triggered. Pushing an annotated, GPG-signed tag matching
`v*` (for example `v0.2.0`) triggers the `Release` workflow, which:

1. Verifies the tag's GPG signature against the public key committed at
   `.github/release-signing-pubkey.asc`. Unsigned or badly-signed tags
   fail the workflow before any build runs.
2. Provisions the debug and release keystores from GitHub Actions
   secrets.
3. Builds a release-signed APK via `gradle assembleRelease` using an
   environment-driven signing config (no debug fallback).
4. Verifies the APK signature with `apksigner`.
5. Publishes the APK to GitHub Releases via the `gh` CLI, with
   auto-generated release notes.

The release build type disables R8, ProGuard, and resource shrinking
for the first releases; minification will be added in a separate change
with hardware-verified keep rules.

Operator-facing release procedure (keystore setup, backups, rotation,
release-cutting checklist) is documented in `RELEASE_SIGNING.md`.

## Dependency Maintenance

[Renovate](https://docs.renovatebot.com/) keeps GitHub Actions pins, Nix flake
inputs, Gradle/Maven dependencies, and Cargo dependencies up to date. The
configuration lives in `renovate.json` at the repository root.
