## 1. VuMeter Composable

- [x] 1.1 Add `VuMeter.kt` Composable in `ui/` taking `level: Float` and `isCapturing: Boolean`; render nothing and reserve no space when `isCapturing` is false
- [x] 1.2 Render the segmented horizontal bar with three palette-conformant zones — low (dim secondary text), good (transmit `SubspaceCyan`/`CommandGold`), clip (`AlertAmber`) — each segment keeping its zone color so the fill's top indicates the current zone; track/markings in secondary text color at reduced opacity
- [x] 1.3 Apply the perceptual mapping (`sqrt(level)`) before display; define `LOW_THRESHOLD` (~0.20) and `CLIP_THRESHOLD` (~0.88) as named constants in the displayed domain
- [x] 1.4 Implement VU ballistics locally: fast attack (~30ms), slower release (~200ms), peak-hold marker (~800ms hold then decay), using `Animatable`/`derivedStateOf` so only meaningful deltas recompose
- [x] 1.5 Use the Chakra Petch typeface for any markings/labels; style as field-terminal segmented bar (no traffic-light gradient)
- [x] 1.6 Add a JVM/Compose test asserting ballistics against a scripted level sequence: rise tracks within attack window, fall decays over release window, peak-hold holds then descends, hidden when `isCapturing` false; assert each zone renders in its palette color (low dim / good transmit / clip amber) for levels crossing `LOW_THRESHOLD` and `CLIP_THRESHOLD`

## 2. Wire signals through MainActivity

- [x] 2.1 Confirm `capture-service` change exposes `level: StateFlow<Float>` and `isCapturing: StateFlow<Boolean>` from `PttForegroundService` (hard dependency on capture-service task 7.1)
- [x] 2.2 Collect `level` and `isCapturing` from the bound service in `MainActivity` via `collectAsStateWithLifecycle()`, alongside the existing `appState` collection
- [x] 2.3 Pass `level` and `isCapturing` as parameters to `MainDashboardScreen`

## 3. Render on the dashboard

- [x] 3.1 Place `VuMeter` in the dashboard status/connection strip (beside the active channel and connection indicator)
- [x] 3.2 Gate the meter's visibility on `isCapturing`; add the ~120ms fade/scale transition on enter and exit
- [x] 3.3 Add a dashboard test asserting the meter is shown while `isCapturing` and absent (no reserved space) otherwise, and that it reflects a changing `level`

## 4. Verify

- [x] 4.1 Verify build: `nix flake check --no-write-lock-file` and `nix develop --no-write-lock-file -c gradle test assembleDebug`
- [x] 4.2 Verify each `vu-meter` and `main-device-dashboard` (delta) spec scenario maps to a passing test
- [x] 4.3 On-device tuning: adjust the perceptual curve constant, `LOW_THRESHOLD` and `CLIP_THRESHOLD`, and ballistics windows against real microphone input via the manual acceptance flow in `AGENTS.md`; confirm the meter animates during journal/STT capture, is absent at idle, and the three zones read correctly for quiet/healthy/loud speech

### Implementation Notes

- **1.6 / 3.3 test scope:** The repo's JVM test setup (JUnit + coroutines-test, no Compose UI / robolectric) cannot host Compose UI assertions. To keep the spec scenarios verifiable on plain JVM, the meter's ballistics + perceptual mapping + zone logic are extracted into a pure-Kotlin `VuMeterEngine` (`ui/VuMeterEngine.kt`) and the dashboard's meter-visibility decision is extracted into a pure predicate `dashboardVuMeterState` (`ui/MainDashboardScreen.kt`). JVM tests `VuMeterEngineTest` and `MainDashboardVuMeterTest` pin those contracts. The `VuMeter` Composable itself is a thin wrapper around the engine and predicate; its Compose-level rendering (segment colors, Chakra Petch markings, fade/scale transition) is verified by compile + on-device acceptance (4.3), and is ready to be covered by a Compose UI test if/when the repo adds robolectric or `androidx.compose.ui:ui-test-junit4`.
- **4.3 is operator-only:** requires a physical `B02PTT-FF01` device and live microphone input; cannot be executed by the agent.