## 1. Media Session Entry Point

- [x] 1.1 Choose the Android media API for this repository and add the minimal dependency or framework integration required to expose a media session to Android Auto.
- [x] 1.2 Add the Android manifest service, intent filters, metadata, and resources required for Android Auto/media discovery.
- [x] 1.3 Implement a minimal media session/service that publishes stable Subspace metadata and playback state for `Ready`, `Recording`, `Finalizing`, and `Not ready`.
- [x] 1.4 Route media play, pause, stop, and play/pause callbacks from the media session into a single car PTT input adapter.

## 2. Virtual PTT Adapter

- [x] 2.1 Add a car/media-originated PTT source to the PTT source model.
- [x] 2.2 Implement a latched virtual PTT adapter with released/pressed state and toggle handling.
- [x] 2.3 Emit synthetic car-originated PTT press/release events into the existing foreground-service PTT dispatch path.
- [x] 2.4 Ensure stop-like or ambiguous media commands release an active virtual PTT session.
- [x] 2.5 Ensure failed start attempts leave virtual PTT released.

## 3. Routing And Safety

- [x] 3.1 Extend PTT dispatch readiness behavior so car-originated PTT uses the same active-channel readiness checks as RSM PTT.
- [x] 3.2 Ensure not-ready car-originated PTT plays the existing two-tone error beep when possible and does not open a capture session.
- [x] 3.3 Force-release virtual PTT on service shutdown, media-session deactivation, Android Auto/media disconnect where detectable, capture error, and max-duration stop.
- [x] 3.4 Keep active session ownership source-aware so a car-originated release cannot incorrectly close another source's active PTT session unless fail-safe shutdown requires it.

## 4. Feedback

- [x] 4.1 Update media session metadata/playback state when virtual PTT transitions between released, pressed, and finalizing states.
- [x] 4.2 Reuse existing ready/error beep behavior for car-originated PTT start failures and successful capture starts.
- [x] 4.3 Add completion or stop feedback if an existing audio path supports it without delaying release safety.

## 5. Tests And Verification

- [x] 5.1 Add unit tests for virtual PTT toggle behavior: released to pressed, pressed to released, stop-like release, and failed-start remains released.
- [x] 5.2 Add unit tests for car-originated PTT routing on ready and not-ready active channels.
- [x] 5.3 Add unit tests or service-level tests for fail-safe release on shutdown/error/max-duration hooks that are practical to test without Android Auto hardware.
- [x] 5.4 Run `nix develop --no-write-lock-file -c gradle test`.
- [x] 5.5 Run `nix develop --no-write-lock-file -c gradle assembleDebug`.
- [x] 5.6 Manually verify on Android Auto or Desktop Head Unit that steering-wheel/media play-pause toggles virtual PTT only while Subspace owns the active media session.
