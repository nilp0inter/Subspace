# Changelog

All notable changes to Subspace are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and release versions follow semantic versioning while the app is pre-1.0.

## [Unreleased]

### Added

- Added persistent, content-free diagnostics for PTT cancellation provenance, terminal ownership, cleanup failures, and RSM reconnect disposition.

### Fixed

- Isolated PTT cancellation by source so RSM serial teardown and stale Telecom callbacks cannot terminate unrelated Phone, RSM, or car input sessions.

## [0.6.0] - 2026-07-11

### Added

- Added a persistent, configurable channel catalogue: built-in Journal, Keyboard, and Debug channels now have dedicated runtime lifecycles and can be created, configured, and browsed consistently.
- Added automatic Sleepwalker BLE reconnection before a Keyboard PTT session starts.

### Changed

- Centralized channel startup, PTT dispatch, and configuration persistence behind the channel runtime registry.

### Fixed

- Kept active RSM serial monitoring alive in the foreground service after the main activity moves to the background.

## [0.5.0] - 2026-07-10

### Added

- Added a launch bootstrap screen that reports initialization progress, exposes recoverable startup failures, and offers retry before the dashboard opens.
- Added mandatory first-run setup for permissions and on-device speech-model acquisition, including per-file download progress and resumption after interrupted downloads. The app returns to setup when required permissions are revoked or model assets fail verification.

### Changed

- Reduced the installed APK by removing bundled Parakeet and Supertonic models; verified speech models now download to app storage during setup.
- Moved model downloads from Hugging Face to the project's Cloudflare R2 mirror.
- Coordinated model verification and acquisition, native STT/TTS initialization, controller creation, and announcement preparation under one readiness flow; setup no longer requires a separate dashboard-entry action.
- Cached the system-announcement vocabulary after bootstrap to avoid repeated synthesis work.
- Hardened PTT session ownership so normal release, cancellation, setup failure, and stale callbacks cannot race or clean the same route twice.
- Improved recorder and car-route readiness with liveness validation, one same-route retry, exact-device HFP-to-Telecom handoff, and stable Bluetooth-route acceptance before car capture begins.

### Fixed

- Fixed normal car-hang finalization so accepted Journal captures receive terminal PCM, metadata, OGG/transcription processing, Markdown regeneration, and route cleanup.
- Fixed ready beeps preceding unusable zero-only capture and prevented pre-beep PCM from entering channel-visible recordings.
- Fixed Android Auto PTT state/control ordering through Recording, Finalizing, terminal completion, and the 30-second idle-retention window.
- Fixed phone-initiated recorded responses becoming inaudible on connected car media routes by waiting for stable normal-media routing and transient audio focus.
- Fixed On The Road PTT from placing a second Telecom call while a session is active or proceeding when the selected channel is not ready.
- Fixed STT-controller initialization racing route setup and ensured a timed-out Telecom route is released.
- Fixed an app-startup crash caused by collecting the keyboard bridge state before PTT dispatch initialization.
- Avoided unnecessary audio-route selection when there is no recorded response to play.
## [0.4.0] - 2026-07-07

### Added

- Sleepwalker-core and OmniKeymap consumed as Nix flake inputs, enabling automatic dependency updates through `nix flake update`.

### Changed

- Adapted app code to upstream sleepwalker-core breaking API changes: `HostProfile`, `KeymapEntry`, `TextPlanner`, and related types.

### Fixed

- Fixed release APK build failure caused by missing Gradle dependency declaration between `copyKeymapResources` and `mapReleaseSourceSetPaths` in `:sleepwalker-core`.

### Removed

- Vendored `sleepwalker-core/` directory (~2100 lines), replaced by flake input consumption.

## [0.3.0] - 2026-07-06

### Added

- Endpoint-bound PTT audio routing by input mode: Work uses the target RSM SCO endpoint, On The Road uses the Telecom car route, and On A Pinch uses the phone-local route.
- Telecom car PTT readiness hooks and response playback plumbing for car-routed sessions.
- Diagnostic-only `SubspaceRoute` logging for mixed car/RSM/mobile routing investigation, including route resolution, SCO selection, PTT session, and Telecom route state.
- Periodic readiness refresh while serial monitoring is active and the target device remains disconnected or unready.
- Keyboard channel backed by Sleepwalker BLE bridge integration, including keyboard-channel configuration UI, BLE connection adapter, PTT controller, and `sleepwalker-core` Android library module.

### Changed

- Refined the main dashboard with safe-area padding, fixed-height icon mode tiles, embedded RSM Bluetooth status, and an always-visible VU meter standby state.
- Restricted RSM SCO acquisition to the target `B02PTT-FF01` endpoint instead of accepting any Bluetooth SCO device.
- Reworked route resolution around explicit physical endpoints (`Rsm`, `Car`, `Local`) instead of mode-agnostic SCO/local decisions.
- Updated OpenSpec archives and synced routing, input-mode, SCO-audio, dashboard, VU-meter, reconnect, channel-framework, and keyboard-channel specs to match implemented behavior.

### Fixed

- Fixed mixed car and RSM audio routing so Work-mode PTT no longer captures/routes through the car endpoint and On The Road no longer selects the RSM as the car route.
- Fixed route-error feedback so unavailable endpoint-specific routes fail closed with an error cue instead of silently using the wrong endpoint.
- Fixed `TtsController` cancellation handling so coroutine cancellation is rethrown instead of converted to an error status.
- Fixed disconnected-device readiness staleness by refreshing readiness until the device becomes ready while monitoring continues.
- Fixed dashboard status-bar overlap and layout instability caused by the previous text-heavy mode selector and conditional VU-meter layout.

## [0.2.0] - 2026-07-01

### Added

- First signed GitHub Release pipeline with GPG-signed tag verification, release keystore provisioning through GitHub Actions secrets, release APK signing, `apksigner` verification, artifact upload, and GitHub Release publication.
- CI pipeline using the Nix devshell for flake validation, Gradle toolchain verification, JVM tests, debug APK assembly, and debug APK artifact upload.
- Debug keystore provisioning from repository secrets for CI signing validation.
- Main operator dashboard with device connection status, channel cards, phone-side PTT fallback, slide-to-lock PTT, VU-meter signal display, and Subspace visual identity.
- Bluetooth Classic SPP/RFCOMM monitoring for the `B02PTT-FF01` device, raw button token parsing, hardware mode tracking, and foreground-service ownership while serial monitoring is active.
- Echo, Journal, Captain's Log/Journal rename, Debug, Parakeet STT test, Supertonic TTS test, active channel routing, and Android Auto live-loop projection groundwork.
- Telecom self-managed VoIP car PTT foundation.
- Release signing operator documentation covering keystore storage, backup, rotation, loss handling, GPG signing setup, and release-cutting checklist.

### Changed

- Centralized SCO keep-warm and capture plumbing around shared services to support channel-specific capture and playback flows.
- Unified PTT audio capture into a shared `CaptureService` and propagated capture-level signal to UI tests.
- Renamed Captain's Log to Journal channel and added metadata-driven streaming WAV capture artifacts.
- Aligned README and product documentation with the hardware-first, voice-first channel-router vision.

### Fixed

- Fixed cold-start ready-beep reliability and short-tap SCO warmup behavior.
- Fixed JNI startup UI blocking by moving initialization off the main thread and closing the STT/TTS init race.
- Fixed journal STT binding after off-main initialization completes.
- Fixed SCO reference-count balance, sample-rate propagation, and Journal thread safety after the capture-service refactor.
- Fixed release workflow `apksigner` lookup by using the full Android build-tools path.
- Fixed release build-type configuration for Android Gradle Plugin pre-created build types.

[Unreleased]: https://github.com/nilp0inter/Subspace/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/nilp0inter/Subspace/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/nilp0inter/Subspace/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/nilp0inter/Subspace/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/nilp0inter/Subspace/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/nilp0inter/Subspace/releases/tag/v0.2.0
