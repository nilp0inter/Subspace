## Context

`MainActivity` currently initializes its local route to `Setup`, renders `InitialSetupScreen`, then asynchronously checks runtime permissions and hashes every model file. The setup surface therefore appears on every cold activity creation even when no action is required. In parallel, `MainActivity.onStart` binds `PttForegroundService`, whose STT and TTS initializers independently call `ModelDownloader.ensure`; the setup action can call the same downloader against the same directories. A matching version marker lets `ensure` bypass the full hash failure that caused setup to request repair.

The dashboard can appear after permission and disk checks while native Parakeet and Supertonic loading, controller construction, journal initialization, and `SystemAnnouncer` vocabulary rendering remain in flight. The announcer has no completion or failure signal and silently substitutes a beep for an absent phrase. External RSM, keyboard, and car connections are separate, potentially unbounded readiness domains.

The change crosses activity routing, service initialization, model storage, native engine readiness, controller wiring, announcement caching, and Compose UI. It must preserve Android 12+/API 31 constraints, existing Night Ops and Daylight palettes, Chakra Petch/Inter typography, and the hardware-first dashboard semantics in `VISUAL_IDENTITY.md`.

## Goals / Non-Goals

**Goals:**

- Give passive checking, downloading, and core initialization a dedicated loading/recovery surface.
- Render setup only when a concrete permission or model-acquisition action is required.
- Automatically leave setup after the final required action starts or succeeds; never require an additional acknowledgement to continue.
- Establish one authoritative bootstrap state and one model acquisition owner.
- Enter the dashboard only when verified assets, both native speech engines, required controllers, and every required spoken navigation phrase are ready.
- Report only observed stages and measurable progress.
- Provide finite, retryable failure handling instead of an infinite spinner or silent degradation.
- Keep optional and external readiness visible after dashboard entry rather than making it a global blocker.
- Produce an entertaining but restrained loading treatment derived from the Analog-to-Routed Wave identity without adding a rendering dependency.

**Non-Goals:**

- Automatically pair, connect, or wait for the RSM during global bootstrap.
- Wait for Keyboard BLE, Android Auto, Telecom, serial reconnect, HFP, or journal recovery before showing the dashboard.
- Redesign dashboard channel cards, input-mode tiles, setup cards, or the Android system permission dialog.
- Change runtime permission policy or make notification permission optional.
- Change the downloaded model set, model format, native inference implementation, or announcement vocabulary wording.
- Persist a separate onboarding-completed preference; live prerequisites remain authoritative.
- Keep the loading animation visible for a minimum duration, simulate progress, or show fictional terminal logs.
- Add Lottie, video, image-sequence, or other animation dependencies.
- Guarantee continued bootstrap work after Android kills the application process; resumable model files remain the process-death recovery mechanism.

## Decisions

### 1. Derive the root surface from an authoritative bootstrap state

Introduce a sealed bootstrap state exposed as a lifecycle-aware `StateFlow`:

```text
ConnectingService
CheckingPrerequisites(stage)
NeedsSetup(missingPermissions, invalidModelSets, error?)
AcquiringModels(progress)
PreparingCore(stage, completedUnits, totalUnits)
Ready
Failed(stage, diagnostic, retryable)
```

`MainActivity` projects this state into one of three root surfaces:

```text
Checking / Acquiring / Preparing  -> Loading
NeedsSetup                       -> InitialSetupScreen
Failed                           -> Loading recovery state
Ready                            -> existing dashboard/navigation routes
```

The activity no longer initializes “unknown” as setup and no longer decides model validity independently. While the service is not bound, it displays the passive `ConnectingService` stage rather than a dashboard backed by default `AppState()`.

Once `Ready` is reached, the existing dashboard and drill-down route behavior remains local to the activity. If a later explicit retry or process recreation re-enters bootstrap, bootstrap state takes precedence over dashboard routing.

Alternative considered: persist an onboarding flag and render the dashboard immediately for returning users. Rejected because acknowledgement does not prove current permission, asset, native model, controller, or announcement readiness.

### 2. Let the service coordinate core bootstrap; keep model acquisition process-single-flight

`PttForegroundService` owns the aggregate bootstrap coordinator because it owns native engines, controllers, announcement playback, and the live `AppState`. The binder exposes bootstrap state and explicit commands to refresh prerequisites, begin model acquisition, and retry a failed core stage. Permission dialogs remain activity-owned; their result triggers a coordinator refresh.

A process-scoped model asset repository wraps `ModelVerifier` and `ModelDownloader` with a single-flight coroutine and shared progress state. Every activity/service path must use this repository; direct `ModelDownloader.ensure` calls are removed. Concurrent requests join the in-flight operation and receive its result instead of starting a second writer. The repository retains only `applicationContext` and process-scoped state. Partial files and HTTP Range behavior remain the durable recovery mechanism after process death.

Alternative considered: put all coordination in an activity `ViewModel`. Rejected because car/Telecom entry can create the service without an activity, and service-owned controllers must not depend on an activity lifecycle.

Alternative considered: let the service call the existing downloader directly and guard only the setup button. Rejected because it leaves multiple ownership paths and does not close future races.

### 3. Treat model acquisition as an explicit setup action but render its autonomous work on loading

Prerequisite checking never downloads automatically. Missing or invalid model sets produce `NeedsSetup`, where the user explicitly starts download/repair. Once accepted, the root returns to loading and shows real file/set progress while the repository performs acquisition. A download failure returns to `NeedsSetup` with the concrete error and retry action. Successful acquisition performs a fresh full verification before core preparation begins.

Permission completion follows the same automatic rule: after the permission result, the coordinator rechecks prerequisites. It either stays in setup for another known missing action or returns to loading. The manual “Enter Subspace” button and callback are removed.

Alternative considered: leave download progress inside setup. Rejected because after the user initiates download no further interaction is required; continuing to show an actionable setup form recreates the original semantic mix.

### 4. Make full verification the only model-complete result

A version marker is a cheap invalidation hint, not proof of validity. Model readiness requires the manifest version, required file presence/nonzero length, and every SHA-256 hash to match. A marker-matching but hash-invalid set is repaired, not accepted by a marker-only fast path. A completion marker is written only after all set files verify, and the aggregate repository result is emitted only after a final full verification of all required sets.

Verification and acquisition stay on `Dispatchers.IO`. STT and TTS sets may be verified independently, but writes to a given set are serialized and aggregate completion remains atomic from the bootstrap consumer’s perspective.

### 5. Define the finite core readiness barrier explicitly

After prerequisites pass, the service prepares independent STT and TTS branches concurrently:

```text
Verified Parakeet -> construct JNI transcriber -> await STT Ready
                  -> STT/Keyboard/Journal controller installation

Verified Supertonic -> construct JNI synthesizer -> await TTS Ready
                     -> TTS controller installation
                     -> render announcement vocabulary

STT Ready + TTS Ready -> STT-to-TTS controller installation
all required controllers + all announcements -> Bootstrap.Ready
```

The required controller set is the STT controller, TTS controller, STT-to-TTS controller, Journal PTT controller, and Keyboard PTT controller. Keyboard connection itself is not required. Synchronous service infrastructure such as capture, routing, dispatcher, echo, repository loading, and phone-account registration must be constructed before preparation reports progress.

Native constructors starting an asynchronous load do not count as ready. The coordinator observes `SttModelStatus` and `TtsModelStatus` until each reaches `Ready` or `Failed`. Each finite stage has a test-injectable timeout; timeout becomes `Failed` with the stage and diagnostic. Retry reconstructs only after prior jobs/controllers for the failed attempt are cancelled or discarded, preventing duplicate pollers and stale controller publication.

Alternative considered: show the dashboard once disk assets verify and expose model loading per channel. Rejected because the selected product contract defines the dashboard as core-ready, including spoken channel navigation.

### 6. Give announcement rendering an aggregate contract

`SystemAnnouncer.precompute` returns or publishes structured progress and a terminal result:

```text
WaitingForTts
Rendering(completed, total, currentKey)
Ready(renderedKeys)
Failed(completed, total, failedKey, reason)
```

The total is derived from the actual vocabulary map, not hard-coded in the UI. A phrase counts as rendered only when synthesis succeeds and produces non-empty SCO-ready PCM in the cache. Bootstrap reaches `Ready` only when every required key is present. The existing ready-beep fallback remains a defensive runtime behavior for later cache loss or unexpected calls; it does not turn partial precomputation into bootstrap success.

Retrying announcement preparation reuses an already-ready TTS engine and replaces only successfully generated entries without publishing aggregate success early.

### 7. Use a real-stage Analog-to-Routed Wave loading surface

The loading composable uses existing Compose Canvas primitives and the Material theme. A continuous analog wave on the left becomes structured routed segments on the right; one restrained signal pulse travels across it, and completed real stages illuminate stable segments. The dominant color is `MaterialTheme.colorScheme.primary`: Subspace Cyan in Night Ops and Command Gold in Daylight. Alert Amber is reserved for warnings/failures/transmission semantics, not ordinary progress.

The surface includes:

- `SUBSPACE` and a concise field-terminal subtitle using Chakra Petch and Inter;
- the analog-to-routed line as the main focal element;
- the current observed stage and measurable count or byte progress when available;
- a compact stable stage rail using completed, active, pending, and failed treatments;
- a recovery variant with diagnostic and retry action.

It does not show raw fictional logs, random values, starfields, spacecraft, CRT flicker, or a fake combined percentage. It respects status-bar insets, both color schemes, font scaling, and the platform animator-duration setting. Reduced/no-animation operation leaves a legible static routed line and updating status text. Completion is never delayed for the exit animation; any crossfade is driven by state and remains brief.

Alternative considered: a circular progress indicator over the existing setup screen. Rejected because it preserves the semantic conflation and does not express the voice-routing identity.

### 8. Keep external capability readiness outside bootstrap

`ConnectionState.readyForMonitor` remains the authoritative RSM readiness predicate. Keyboard connection, Android Auto presence, Telecom route, reconnect loops, and journal recovery continue through their existing state and admission paths after dashboard entry. Core readiness must not imply that any external device is online, and external absence must not send the user back to global loading or setup.

Headless service entry performs the same prerequisite/core checks. If UI-resolvable setup is missing, model-backed operations remain unavailable and fail closed; this change does not add a headless permission/download interaction.

## Risks / Trade-offs

- **Longer time before dashboard entry because native loading and announcement rendering are now real blockers** -> Run independent STT/TTS branches concurrently, report actual progress, avoid artificial animation delay, and profile on target hardware.
- **A single failed announcement blocks the selected core-ready contract although a beep fallback exists** -> Report the failed phrase and retry precisely; retain the beep only as runtime defence after successful bootstrap.
- **Native status can remain `Loading` indefinitely** -> Apply finite, test-injectable stage timeouts and expose retryable recovery rather than spinning forever.
- **Service recreation can restart native preparation** -> Cancel attempt-owned jobs on destruction, keep model acquisition single-flight at process scope, and rebuild aggregate state deterministically after rebinding.
- **Process death loses in-memory progress** -> Resume model bytes from partial files, rerun verification, and reconstruct bootstrap state from durable Android permissions and model files.
- **Animation can contend with native initialization** -> Use one low-complexity Canvas animation, cache static geometry, avoid per-frame collections/strings, and keep progress text updates event-driven.
- **Moving download progress away from setup may surprise users after tapping Download** -> Transition immediately to a clearly labelled `Downloading speech packages` loading stage with real file/byte progress; return to setup only if another action or retry is required.
- **Existing initializer failures currently leave model status at `Idle`** -> Convert constructor, native load, controller construction, announcement, and timeout failures into explicit terminal bootstrap diagnostics.
- **The existing permission set includes notification permission as a global prerequisite** -> Preserve current policy in this change; revisiting permission necessity is explicitly out of scope.

## Migration Plan

1. Introduce the model asset repository and route all verification/acquisition callers through its single-flight API while preserving existing file layout, markers, hashes, and resumable downloads.
2. Add structured native/controller/announcement initialization results and aggregate them in the service bootstrap coordinator without changing the UI route yet.
3. Expose bootstrap state and binder commands; make activity permission and model actions feed the coordinator.
4. Add the loading/recovery composable and derive the root surface from bootstrap state.
5. Remove activity-local launch verification/download ownership, direct service downloader calls, obsolete setup completion state, and the “Enter Subspace” action.
6. Verify cold returning launch, first setup, permission denial/grant, missing/corrupt model repair, model download interruption/resume, native failure/timeout/retry, announcement partial failure/retry, configuration recreation, and optional peripheral absence.

No persistent data migration is required. Rollback restores the former activity/service ownership; downloaded models remain compatible because directory layout and manifest formats do not change.

## Open Questions

None. The core-ready boundary, automatic setup-to-loading transition, and visual direction were selected during exploration.
