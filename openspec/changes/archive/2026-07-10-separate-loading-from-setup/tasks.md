## 1. Model Asset Ownership

- [x] 1.1 Define model inspection, acquisition, progress, and terminal result types that distinguish valid, user-action-required, active, and failed states.
- [x] 1.2 Introduce a process-scoped model asset repository with one in-flight acquisition per model set and shared progress/result observation for concurrent callers.
- [x] 1.3 Change model acquisition so a matching version marker never bypasses file-presence, nonzero-length, and SHA-256 validation.
- [x] 1.4 Commit each model set's completion marker only after every required file verifies, then run aggregate full verification before publishing readiness.
- [x] 1.5 Preserve existing partial-file HTTP Range resume and retry behavior while repairing absent, version-mismatched, zero-length, or hash-invalid sets.

## 2. Observable Core Bootstrap

- [x] 2.1 Define the authoritative bootstrap state, stage, setup requirement, model progress, failure diagnostic, retry, and ready contracts as lifecycle-aware service state.
- [x] 2.2 Refactor `SystemAnnouncer` precomputation to publish vocabulary-derived phrase progress and terminal success only when every required key has non-empty SCO-ready PCM.
- [x] 2.3 Make announcement synthesis failures identify the failed phrase and preserve the existing runtime ready-beep fallback without treating fallback as bootstrap success.
- [x] 2.4 Add a service-owned bootstrap coordinator that checks permissions and verified model assets before starting native or controller initialization.
- [x] 2.5 Start independent STT and TTS native initialization branches concurrently and wait for actual `Ready`, explicit `Failed`, or test-injectable finite timeout outcomes.
- [x] 2.6 Publish the STT, TTS, STT-to-TTS, Journal PTT, and Keyboard PTT controllers only for the current successful bootstrap attempt and aggregate their construction into core readiness.
- [x] 2.7 Run announcement rendering after TTS readiness and publish bootstrap `Ready` only after all required controllers and announcement phrases are ready.
- [x] 2.8 Expose binder commands for prerequisite refresh, explicit model acquisition, and safe bootstrap retry; cancel or discard prior attempt jobs, pollers, and controllers before retry.
- [x] 2.9 Keep RSM, SPP, HFP, Keyboard BLE, Android Auto, Telecom, reconnect, and journal-recovery state outside the bootstrap completion predicate, including headless service entry.

## 3. Loading, Setup, and Routing UI

- [x] 3.1 Add a loading/recovery composable with the Analog-to-Routed Wave Canvas treatment, stable stage rail, current observed status, real byte/count progress, diagnostic, and retry action.
- [x] 3.2 Apply existing Night Ops and Daylight colors, Chakra Petch/Inter typography, rounded field-terminal surfaces, safe-area insets, font scaling, and static reduced/no-animation behavior without adding an animation dependency.
- [x] 3.3 Ensure the loading animation uses no artificial minimum duration, fictional logs, random values, fake aggregate percentage, starfield, spacecraft, CRT flicker, or ordinary amber progress.
- [x] 3.4 Derive the activity root surface from service bootstrap state so unbound, checking, acquiring, and preparing states render loading; known prerequisite gaps render setup; failures render recovery; and only `Ready` renders dashboard routes.
- [x] 3.5 Route runtime permission results back to prerequisite refresh and automatically leave setup when no further user action is required.
- [x] 3.6 Move active model download/repair progress to loading, return to setup only for a required retry, and continue automatically into core preparation after full verification.
- [x] 3.7 Remove the "Enter Subspace" button, callback, and manual acknowledgement while preserving the existing setup permission and model actions.
- [x] 3.8 Preserve dashboard and drill-down navigation after bootstrap and confirm unavailable optional peripherals remain dashboard tile/channel state instead of reopening global loading or setup.

## 4. Clean Cutover

- [x] 4.1 Remove activity-local cold-start verification, activity-owned download coroutine/progress, and unconditional initial `Setup` routing.
- [x] 4.2 Remove direct `ModelDownloader.ensure` calls from service initialization and all other callers so every model read/repair path uses the single authoritative repository.
- [x] 4.3 Remove obsolete setup completion fields, unused `AppState.setupState` mirroring, duplicate permission launch ownership, and stale setup-only comments without leaving compatibility aliases.
- [x] 4.4 Make constructor, native-load, controller, announcement, verification, and timeout failures populate explicit bootstrap diagnostics instead of remaining logged-only `Idle` or null states.

## 5. Focused Verification

- [x] 5.1 Add model repository tests for concurrent callers sharing one writer, marker-matching hash corruption repair, marker-last commit, final aggregate verification, failure propagation, and resumable partial files.
- [x] 5.2 Add bootstrap coordinator tests for initial loading, permission/model setup routing, automatic setup continuation, parallel native readiness, required-controller aggregation, external-readiness exclusion, timeout/failure recovery, safe retry, and recreation without duplicate work.
- [x] 5.3 Add `SystemAnnouncer` tests for real phrase counts, all-phrase success, empty output, partial synthesis failure, retry, and beep fallback remaining distinct from precompute success.
- [x] 5.4 Add UI/state-projection tests for loading versus setup versus recovery versus dashboard, real progress labels, no manual entry acknowledgement, and automatic dashboard cutover.
- [x] 5.5 Run the focused app unit-test targets covering the model repository, bootstrap coordinator, announcer, and root-state projection inside the repository Nix devshell.
- [x] 5.6 Compile the affected debug Android variant inside the repository Nix devshell and resolve all Kotlin/Compose diagnostics introduced by the cutover.
- [x] 5.7 On the target Android device, verify returning cold launch never flashes setup; first launch shows only known setup actions; download moves to loading; permission/model completion resumes loading; core completion opens dashboard; and RSM/Keyboard/Car absence does not block entry.
- [x] 5.8 On the target Android device, verify Night Ops and Daylight loading visuals, readable large-font layout, reduced/disabled animation behavior, native/announcement failure recovery, and no loading delay after readiness.
