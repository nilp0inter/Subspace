## Why

The app currently renders its actionable setup screen during every cold-start eligibility check, making returning users believe interaction is required while permissions and model files are merely being inspected. Startup work is also split between the activity and foreground service, allowing duplicate model acquisition and exposing the dashboard before speech engines, controllers, and spoken channel announcements are ready.

## What Changes

- Introduce a passive bootstrap loading surface that owns the transition from launch to either actionable setup, recoverable bootstrap failure, or the dashboard.
- Make the loading surface report real initialization stages and use a restrained animated analog-to-routed signal derived from the Subspace visual identity; do not invent progress or delay completion for animation.
- Define core readiness as verified model assets, ready native STT and TTS engines, constructed required controllers, and a completely rendered system-announcement vocabulary.
- Keep RSM, keyboard, Android Auto, Telecom, reconnect, and journal-recovery readiness outside the global startup gate because they depend on optional or open-ended external conditions.
- Show setup only when a known user action is required: grant missing runtime permissions or download/repair model assets.
- Automatically return from setup to loading after the final required action succeeds; remove the manual “Enter Subspace” acknowledgement.
- Centralize model verification, repair, acquisition, and progress under one coordinator so the activity and service cannot download or modify the same model set concurrently.
- Expose terminal bootstrap failures as a recovery state with an actionable retry and concrete diagnostic rather than an infinite spinner, silent degradation, or setup screen.

## Capabilities

### New Capabilities
- `app-bootstrap`: Passive launch checking, core initialization readiness, real stage progress, visual loading behavior, recovery, and automatic dashboard cutover.

### Modified Capabilities
- `initial-setup`: Restrict setup to missing user-resolvable prerequisites and return automatically to passive loading when setup completes.
- `runtime-model-download`: Give model acquisition and repair a single owner, serialize concurrent requests, and require post-repair verification before reporting completion.
- `main-device-dashboard`: Enter the dashboard only after core bootstrap readiness while keeping optional peripheral and transport readiness as dashboard-level state rather than startup blockers.

## Impact

- Affects `MainActivity` route ownership, setup state, and service binding/initialization sequencing.
- Adds a lifecycle-aware bootstrap coordinator/state model and a visual-identity-aligned loading/recovery composable.
- Changes `PttForegroundService` STT/TTS/controller initialization to consume coordinated verified assets and publish finite initialization outcomes.
- Changes `ModelDownloader`, `ModelVerifier`, and their callers to prevent duplicate acquisition and close the marker-matches-but-hash-fails repair path.
- Changes `SystemAnnouncer` precomputation to expose phrase-level progress and aggregate success/failure.
- Removes the setup screen’s manual dashboard-entry callback and button.
- Requires focused JVM/Compose or instrumentation coverage for bootstrap transitions, model-acquisition serialization and repair, announcement aggregation, and automatic setup cutover.
- No new external runtime dependency is required; the animation can use existing Compose Canvas, theme colors, Chakra Petch, and Inter assets.
