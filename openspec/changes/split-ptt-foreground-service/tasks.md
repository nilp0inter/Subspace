## 1. Baseline Ownership and Verification

- [x] 1.1 Record every externally used `PttForegroundService` property and method and its current caller before changing the service surface
- [x] 1.2 Record current ownership for each mutable service field, resource, and `Job`, including its construction and teardown points
- [x] 1.3 Record the current `onCreate`, `onStartCommand`, serial-disconnect, PTT-completion, and `onDestroy` side-effect order used as the migration oracle
- [x] 1.4 Identify the focused existing tests that cover channel mutation, reconnect policy, readiness refresh, input mode, bootstrap, navigation announcements, PTT completion, and Android service lifetime
- [x] 1.5 Run the identified focused baseline tests and debug Kotlin compilation before the first responsibility cutover

## 2. Channel Management Cutover

- [x] 2.1 Add focused tests for provider-backed channel creation with explicit and default payloads
- [x] 2.2 Add focused tests for missing-provider creation and configuration-update failures without repository mutation
- [x] 2.3 Add focused tests for successful and failed channel selection, including playback callback multiplicity and ordering
- [x] 2.4 Introduce `ServiceChannelManager` with explicit repository, provider-registry, playback-selection, ID-generation, and logging dependencies
- [x] 2.5 Move channel creation and configuration-update behavior into `ServiceChannelManager` without changing result types or schema handling
- [x] 2.6 Move channel-selection behavior and ordered playback notifications into `ServiceChannelManager`
- [x] 2.7 Retain the existing `PttForegroundService` channel API as stable delegation and keep PTT dispatch and `AppState`-based offset selection in the service
- [x] 2.8 Remove superseded channel-management logic and dependencies from the service after the single-path cutover
- [x] 2.9 Run the added channel-manager tests and affected existing channel tests

## 3. Authoritative Service State Projection

- [x] 3.1 Add ordered-state tests for connection updates that also affect readiness, input-mode availability, and car-media projection
- [x] 3.2 Add state-projection tests for catalogue order, active channel, runtime status, pending counts, and provider descriptors
- [x] 3.3 Add tests for monitor, input-mode, and car-HFP UI mutations through the service-facing contract
- [x] 3.4 Introduce `ServiceStateProjector` as the sole owner of `MutableStateFlow<AppState>` and its read-only projection
- [x] 3.5 Move connection and monitor copy-update operations to explicit `ServiceStateProjector` methods
- [x] 3.6 Move input-mode publication and compound readiness projection to `ServiceStateProjector` without changing emission order
- [x] 3.7 Move catalogue/runtime aggregation and active-channel state publication to `ServiceStateProjector`
- [x] 3.8 Move car-HFP and bootstrap-derived `AppState` updates to `ServiceStateProjector`
- [x] 3.9 Preserve `appState` and `channelBrowseEntries` as stable service properties backed by the projector
- [x] 3.10 Replace every direct service `_appState` read or write with the projector's read-only snapshot or typed operation
- [x] 3.11 Remove the service-local mutable flow only after no second `AppState` mutation owner remains
- [x] 3.12 Run the added projector tests and affected dashboard, input-mode, car-media, and channel-browse tests

## 4. RSM Serial and Reconnect Ownership

- [x] 4.1 Add coordinator-level recording fakes for scanner, SPP connection, elapsed time, delay scheduling, and connection-event publication
- [x] 4.2 Add tests for manual connect admission, duplicate connect suppression, explicit disconnect, and target-device resolution
- [x] 4.3 Add tests for automatic reconnect eligibility, scheduled deadlines, blocked prerequisites, attempt completion, and cancellation
- [x] 4.4 Add tests for SPP state-event ordering, session termination before and during RSM PTT, and reconnect disposition reporting
- [x] 4.5 Add tests for late SPP events and cancelled reconnect jobs not reviving retired connection state
- [x] 4.6 Introduce typed serial/reconnect events and `RsmSerialConnectionCoordinator` with narrow platform and service-edge callbacks
- [x] 4.7 Move `targetDevice`, `sppClient`, `serialJob`, `sppStateJob`, `reconnectJob`, and `ReconnectPolicy` into the coordinator as one ownership cut
- [x] 4.8 Move manual connection, explicit disconnect, serial-session start, SPP collection, and session-end orchestration into the coordinator
- [x] 4.9 Move reconnect prerequisite evaluation, decision handling, scheduling, and automatic-attempt execution into the coordinator
- [x] 4.10 Keep Android Bluetooth HEADSET-profile callbacks and permission requests at the service edge, forwarding observations to the coordinator
- [x] 4.11 Route coordinator events through `PttDispatcher`, `ServiceStateProjector`, and foreground-lifetime integration in the existing order
- [x] 4.12 Remove superseded serial/reconnect fields, jobs, and methods from the service after all callers use the coordinator
- [x] 4.13 Run the added coordinator tests and affected reconnect, readiness, PTT-cancellation, and service-lifetime tests

## 5. Foreground and Readiness-Loop Ownership

- [x] 5.1 Add tests for idempotent foreground start/stop, unchanged notification identity, and single readiness-loop ownership
- [x] 5.2 Add tests for monitoring retention, deferred serial-disconnect shutdown during PTT terminal work, and shutdown after retention clears
- [x] 5.3 Add tests for repeated start commands, explicit disconnect, and service stop without a duplicate notification transition
- [x] 5.4 Introduce `ForegroundServiceCoordinator` with injected Android-edge start, stop, stop-self, notification, refresh, and delay callbacks
- [x] 5.5 Move the logical foreground flag and readiness-refresh-loop job into the coordinator
- [x] 5.6 Route monitoring, serial, PTT-terminal, and service-start events to the coordinator while preserving existing predicates and interval
- [x] 5.7 Keep notification-channel creation and actual Android `Service` API calls at the service edge or behind explicit injected callbacks
- [x] 5.8 Remove superseded foreground/readiness-loop state and methods from the service after cutover
- [x] 5.9 Run the added coordinator tests and affected refresh-loop, reconnect-lifetime, and Android service-lifecycle tests

## 6. Core Initialization and Native Resource Ownership

- [x] 6.1 Add tests for successful and failed STT, TTS, journal, text-output, and navigation-TTS construction through the `CoreInit` contract
- [x] 6.2 Add tests for model-status poller ownership, bootstrap retry discard, repeated discard, and partial-initialization shutdown
- [x] 6.3 Add tests for navigation-TTS prepare replacement and exactly-once shutdown on retry and service destruction
- [x] 6.4 Introduce `ServiceCoreInitializer` with explicit model, audio, capability, filesystem, JNI-construction, scope, and state-publication dependencies
- [x] 6.5 Move STT transcriber construction, model-directory state, transcription service, and STT status polling into the initializer
- [x] 6.6 Move TTS synthesizer/controller construction, model-directory state, and TTS status polling into the initializer
- [x] 6.7 Move journal-controller construction and text-output availability projection into the initializer without changing capability results
- [x] 6.8 Move navigation-TTS engine preparation, state-loss reporting, replacement, and ownership into the initializer
- [x] 6.9 Move controller discard and idempotent core-resource shutdown into the initializer while preserving cancellation and release order
- [x] 6.10 Wire `BootstrapCoordinator` to `ServiceCoreInitializer` and remove `CoreInit` implementation from `PttForegroundService`
- [x] 6.11 Update capability-host and runtime-composition dependencies to read core capabilities through narrow initializer accessors
- [x] 6.12 Remove superseded core resource fields, poller jobs, constructors, and discard methods from the service
- [x] 6.13 Run the added initializer tests and affected bootstrap, model-asset, TTS-controller, journal, and capability-host tests

## 7. RSM Navigation Announcement Ownership

- [x] 7.1 Add tests for menu text, channel name, selected-channel text, unknown keys, and removed-channel keys
- [x] 7.2 Add tests for announcement synthesis result forwarding, latest-wins delegation, error-beep playback, and unavailable-engine behavior
- [x] 7.3 Introduce `RsmAnnouncementCoordinator` with catalogue, core navigation-engine, host-audio, scope, and bootstrap-result dependencies
- [x] 7.4 Move RSM text announcement and error-beep orchestration into the coordinator without adding another request queue
- [x] 7.5 Route button and channel-cycle announcement requests from the service to the coordinator
- [x] 7.6 Remove superseded announcement orchestration from the service while retaining pure vocabulary resolution in its focused helper
- [x] 7.7 Run the added announcement tests and affected navigation-TTS, vocabulary, host-audio, and button-state tests

## 8. Service Shell Consolidation

- [x] 8.1 Ensure `PttForegroundService` now contains only Android lifecycle, dependency composition, stable binder/UI delegation, and narrow Android/Telecom/button callback forwarding
- [x] 8.2 Keep `onCreate` as an explicit composition root while grouping construction through the focused collaborators instead of introducing a replacement service graph
- [x] 8.3 Keep `onDestroy` as the explicit top-level shutdown sequence and preserve its existing timeout, non-cancellable, and resource-release ordering
- [x] 8.4 Verify every migrated mutable field, resource, and `Job` has one owner and no collaborator receives the service or mutable `AppState`
- [x] 8.5 Verify no production operation executes both a legacy and extracted side-effect path
- [x] 8.6 Remove obsolete service interfaces, delegation scaffolding not required by the stable surface, dead fields, imports, and superseded helpers
- [x] 8.7 Re-run `cloc` and record the final service/core collaborator sizes as decomposition evidence without enforcing an arbitrary maximum

## 9. Final Verification and Cleanup

- [x] 9.1 Run all focused unit tests added or modified for channel management, state projection, serial/reconnect, foreground lifetime, core initialization, and announcements
- [x] 9.2 Run affected existing PTT audio-session, dispatcher, runtime integration, input-mode, reconnect, bootstrap, navigation-TTS, host-audio, and channel tests
- [x] 9.3 Run debug Kotlin compilation and fix all production and test diagnostics without changing behavior
- [x] 9.4 Build the debug APK successfully
- [x] 9.5 Run the focused Android service-lifecycle instrumentation tests on the connected target device
- [x] 9.6 Install and launch the final debug build on the physical Android device
- [x] 9.7 Verify `B02PTT-FF01` PTT press/release, Group mode change, Control-mode PTT return, and volume click expiry
- [ ] 9.8 Verify RSM-routed echo recording/playback, Android Auto Telecom capture/playback, and coexistence with the connected RSM
- [x] 9.9 Verify background operation retains the `Subspace connected` foreground notification and explicit serial disconnect removes it
- [x] 9.10 Confirm persisted channel/profile data, permissions, SDK configuration, release signing behavior, and user-visible UI remain unchanged
- [x] 9.11 Remove temporary recording hooks or migration-only test scaffolding that is not part of the durable regression suite
- [x] 9.12 Run the final focused automated checks after cleanup and confirm all specification scenarios have verification evidence
