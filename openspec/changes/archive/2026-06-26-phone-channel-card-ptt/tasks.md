## 1. Audio Route Abstractions

- [x] 1.1 Add a phone-local audio route/output path that does not require Bluetooth SCO and uses Android default local routing.
- [x] 1.2 Keep the existing SCO output path strict so RSM audio still requires an actual Bluetooth SCO communication device.
- [x] 1.3 Add a route resolver that selects the SCO route only when the actual RSM audio route is usable, otherwise selects the phone-local route.
- [x] 1.4 Add unit tests or fakes covering route selection for RSM-available and RSM-unavailable states.

## 2. Source-Independent PTT Dispatch

- [x] 2.1 Add service-level phone PTT press/release entry points that accept the target channel id.
- [x] 2.2 Refactor PTT dispatch so RSM and phone sources share readiness checks, ready/error beep behavior, max-duration handling, and channel dispatch.
- [x] 2.3 Ensure phone-originated PTT sets the long-pressed channel active before readiness evaluation and capture dispatch.
- [x] 2.4 Preserve existing RSM physical PTT behavior and RSM control-mode channel navigation behavior.

## 3. Dashboard Long-Press Input

- [x] 3.1 Extend functional channel cards with long-press handling while preserving normal tap-to-activate behavior.
- [x] 3.2 Ensure config buttons remain independent and do not start PTT or change active channel state.
- [x] 3.3 End phone-originated PTT on pointer release or gesture cancellation.
- [x] 3.4 Apply the same long-press behavior to every functional channel card currently shown on the dashboard.

## 4. Channel Behavior Coverage

- [x] 4.1 Verify ready phone-originated PTT dispatches captures to the selected channel controller.
- [x] 4.2 Verify not-ready phone-originated PTT plays the two-tone error beep on the resolved route and does not dispatch capture.
- [x] 4.3 Verify phone long-press uses RSM audio when the actual RSM audio route is usable.
- [x] 4.4 Verify phone long-press uses phone microphone and Android default local playback when the actual RSM audio route is unavailable.

## 5. Validation

- [x] 5.1 Run the relevant unit tests for audio controllers, channel routing, and dashboard/service behavior.
- [x] 5.2 Run the project build or standard Gradle verification command through the Nix devshell.
- [x] 5.3 Perform manual device checks for RSM-connected phone long-press, RSM-disconnected phone fallback, not-ready error beep, and release-during-beep cancellation.
