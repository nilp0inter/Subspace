# AGENTS.md

## Development Environment

Use the repository flake for all development tooling.

```sh
nix develop
```

For one-off commands, prefer:

```sh
nix develop -c <command>
```

Do not install Android, Gradle, Kotlin, Java, or build tooling globally. Do not use `brew`, `apt`, SDKMAN, or manually downloaded SDKs for this repository.

## Flake Facilities

The default devshell provides:

- JDK 17
- Gradle
- Kotlin compiler
- Android SDK platforms API 31 and API 35
- Android build tools 35.0.0
- Android platform tools

The shell exports:

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `JAVA_HOME`
- `GRADLE_OPTS` with the Nix-provided `aapt2` override

Gradle state is scoped to the repository by `GRADLE_USER_HOME=$PWD/.gradle`.

## Nix And Git Source Visibility

This repository is a Git worktree. Nix evaluates the flake from the Git source, so files required by flake evaluation must be tracked or staged before running flake commands.

If a new file is needed by `flake.nix`, stage it before evaluation:

```sh
git add <file>
nix flake check --no-write-lock-file
```

Do not stage unrelated user changes. Only stage files required for Nix to evaluate or files the user explicitly asks to stage.

## Standard Commands

Validate the flake:

```sh
nix flake check --no-write-lock-file
```

Verify the devshell toolchain:

```sh
nix develop --no-write-lock-file -c gradle --version
```

Run future Gradle project commands through the devshell:

```sh
nix develop -c gradle build
nix develop -c gradle test
```

If the project later adds a Gradle wrapper, prefer the wrapper inside the devshell:

```sh
nix develop -c ./gradlew build
nix develop -c ./gradlew test
```

## Android Device Testing

Use a physical Android 12+ device with USB debugging enabled. The target hardware is `B02PTT-FF01`.

Check that ADB can see the device:

```sh
nix develop --no-write-lock-file -c adb devices
```

The expected state is `<serial>    device`. If the state is `unauthorized`, accept the USB debugging prompt on the phone.

Install the debug build:

```sh
nix develop --no-write-lock-file -c gradle installDebug
```

Launch the app:

```sh
nix develop --no-write-lock-file -c adb shell am start -n dev.nilp0inter.subspace/.MainActivity
```

In-app test flow:

- Tap `Grant permissions`.
- Enable Bluetooth if needed.
- Put `B02PTT-FF01` in pairing mode.
- Tap `Scan for device`.
- Tap `Pair device` if found unpaired.
- Tap `Connect serial`.
- If `Headset audio capability` stays unavailable, open Android Bluetooth settings, connect the headset/calls profile, then retry readiness checks.

Manual acceptance checks:

- Press/release PTT; the monitor must show `pressed` and `released`.
- Press Group; hardware mode must change to `Control`.
- Press PTT while in Control; hardware mode must return to `Active`.
- In Control mode, press Volume Up and Volume Down; each row must show `clicked` and return to `idle` after 300 ms.
- Enable echo, hold PTT, speak, release; beep, recording, and playback must route through the headset, not the phone.
- Switch to another app while serial is connected; echo must continue working and Android must show the `Subspace connected` foreground-service notification.
- Tap `Disconnect serial`; the foreground-service notification must be removed.

## Android Project Constraints

Use the SDK supplied by the flake. Do not create or rely on `local.properties` for SDK discovery unless explicitly required by Android tooling; it is ignored by Git.

Keep the Android target aligned with the scaffold specification:

- Minimum SDK: API 31
- Target SDK: current stable Android SDK available in the flake, currently API 35
- Language: Kotlin
- Hidden Android APIs: not allowed

## Generated Files

Do not commit generated local state:

- `.gradle/`
- `.kotlin/`
- `build/`
- `local.properties`

These paths are ignored by `.gitignore`.
