## 1. Persistent Car Identity and Resolution

- [x] 1.1 Add immutable configured-car, candidate, status, and typed resolution models that keep framework `BluetoothDevice` objects out of UI state.
- [x] 1.2 Add an application-private `SharedPreferences` car configuration store that atomically persists one canonical Bluetooth address plus its display-only label and exposes an empty default for existing installs.
- [x] 1.3 Replace `selectUnambiguousCarHfpDevice` with a pure configured-car resolver that returns the matching live connected HEADSET-profile device or a specific unconfigured, absent, disconnected, RSM-conflict, or inspection failure.

## 2. Foreground-Service Configuration Boundary

- [x] 2.1 Add a service-owned car HFP configuration controller that projects eligible connected candidates, configured status, and permission/profile-unavailable states from the existing HEADSET proxy and target-RSM providers.
- [x] 2.2 Implement selection mutation with a fresh profile snapshot, exact target-RSM exclusion, `STATE_CONNECTED` revalidation, atomic replacement, and preservation of the prior record on failure.
- [x] 2.3 Publish UI-safe car configuration state through the existing foreground-service state boundary and refresh it on profile connection changes, readiness refresh, explicit retry, and successful selection.
- [x] 2.4 Expose service operations for configuration refresh and candidate selection without exposing or logging raw Bluetooth addresses.

## 3. On-the-road Runtime Cutover

- [x] 3.1 Inject the car configuration store/resolver into `CarTelecomStarter` and resolve one live configured device at the beginning of each On-the-road setup attempt.
- [x] 3.2 Fail before HFP priming, expected-device reservation, and `placeCall` for missing, absent, disconnected, conflicting, or unverifiable configuration, with distinct non-identifying diagnostics and no unique-candidate fallback.
- [x] 3.3 Preserve the resolved device as the in-flight operation snapshot so later configuration replacement affects only later operations while existing prime, cleanup, Telecom reservation, and exact-route validation remain unchanged.
- [x] 3.4 Remove the obsolete unique non-RSM candidate selector and its ambiguity-only diagnostics after all production and test callers use configured resolution.

## 4. Car Configuration User Interface

- [x] 4.1 Extend dashboard mode-tile actions so long-pressing CAR dispatches `OpenCarSetup` for both available and unavailable states, while Work retains RSM setup and Phone retains no setup long-press.
- [x] 4.2 Add the host UI navigation intent and `MainActivity` dashboard route for car configuration without changing the selected input mode or dispatching the CAR tile's ordinary tap action.
- [x] 4.3 Build the dedicated Compose car configuration view with configured status, connected candidate rows, selected state, duplicate/missing-name handling, empty and inspection-unavailable guidance, retry, Bluetooth settings access, and back navigation.
- [x] 4.4 Wire candidate selection to service-side revalidation, keep the view open after mutation, and surface recoverable selection failure without losing the previously configured car.

## 5. Verification and Field Acceptance

- [x] 5.1 Add focused JVM tests for configuration-store restart persistence and replacement, including preservation across transient unavailable states and failed selection.
- [x] 5.2 Replace selector tests with exhaustive configured resolver/controller tests covering multiple connected devices, duplicate names, stale entries, missing configuration, absent/disconnected car, target-RSM conflict, permission/profile loss, and no fallback.
- [x] 5.3 Add dashboard action and navigation tests proving CAR long-press works in available and unavailable states, changes no mode, and does not also dispatch the tap action.
- [x] 5.4 Extend `CarTelecomStarter` tests to prove only the configured live device is primed among multiple endpoints, failure occurs before side effects, configuration replacement does not redirect an in-flight operation, and existing exact-device cleanup/handoff behavior remains intact.
- [x] 5.5 Run the focused modified JVM test targets and build/install the debug application in the repository devshell.
- [x] 5.6 On the physical Android device, connect the car and competing HFP endpoints, select the car through CAR long-press, restart the app/service, and verify Android Auto PTT uses the saved exact car while missing/disconnected configuration fails without routing to another device.
- [x] 5.7 Inspect in-app persisted logs from successful and rejected attempts to verify semantic resolution outcomes are present and no full or partial Bluetooth addresses are recorded.
