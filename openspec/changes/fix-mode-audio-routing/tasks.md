## 1. Endpoint-Aware Test Coverage

- [ ] 1.1 Add JVM test fakes that can model distinct RSM SCO, car SCO, and local endpoints instead of a generic `hasAvailableScoDevice` boolean
- [ ] 1.2 Add tests proving `Work` availability is false when the RSM is bonded/SPP-connected but only a non-RSM SCO endpoint is available
- [ ] 1.3 Add tests proving `Work` route acquisition does not accept an active or warm car SCO endpoint
- [ ] 1.4 Add tests proving `Work` route resolution does not fall back to phone/local capture or output when the RSM endpoint is missing
- [ ] 1.5 Add tests proving SCO-backed PCM playback sets the selected RSM device as preferred output for ready beep, error beep, and normal playback
- [ ] 1.6 Add tests proving `OnTheRoad` capture readiness is satisfied only by the Telecom car call-audio lifecycle, not by active RSM SCO
- [ ] 1.7 Add tests proving `OnTheRoad` response playback uses media output after route switch and never targets RSM SCO
- [ ] 1.8 Add tests proving `OnAPinch` route resolution never acquires SCO and uses phone/local capture and media output

## 2. RSM Endpoint Route

- [ ] 2.1 Update the normal SCO route implementation so available-device lookup requires the B02PTT-FF01 endpoint and removes the arbitrary first-SCO fallback
- [ ] 2.2 Track the selected RSM `AudioDeviceInfo` for the current acquisition and make `isActive()` verify that the current communication device matches it
- [ ] 2.3 Update `hasAvailableScoDevice()` and readiness refresh so `HeadsetAudioState.Available` means RSM SCO is available, not any SCO endpoint
- [ ] 2.4 Ensure RSM route acquisition fails cleanly when the active communication device is a different SCO endpoint
- [ ] 2.5 Preserve RSM 30-second warm retention and reference counting for successful Work-mode sessions

## 3. Telecom Car Endpoint Route

- [ ] 3.1 Expose enough Telecom route readiness state for `PttForegroundService` to build an OnTheRoad route that verifies the active Subspace call-audio route
- [ ] 3.2 Add a Telecom car `ScoRoute` implementation that verifies Telecom readiness and never calls `setCommunicationDevice()` with the RSM endpoint
- [ ] 3.3 Wire `resolvePttAudioRoute(InputMode.OnTheRoad)` to use the Telecom car route with `TelecomCapturePcmOutput`
- [ ] 3.4 Preserve route timeout, abort, disconnect, and `TelecomCapturePcmOutput.releaseRoute()` behavior for every OnTheRoad exit path

## 4. Mode Route Resolution

- [ ] 4.1 Replace `Work` route resolution with an RSM-only route and remove the generic local fallback from Work-mode PTT dispatch
- [ ] 4.2 Keep `OnAPinch` route resolution as `NoopScoRoute` plus local phone capture and media output
- [ ] 4.3 Keep actuator handling mode-authoritative: RSM PTT to `Work`, car PTT to `OnTheRoad`, phone channel long-press to `OnAPinch`, then resolve by current mode
- [ ] 4.4 Verify existing input-mode transition tests still match the specs, including Android Auto connect switching to `OnTheRoad`
- [ ] 4.5 Update error-beep routing so not-ready feedback is delivered only through the active mode's valid output endpoint when that endpoint is available

## 5. SCO PCM Output Binding

- [ ] 5.1 Update the SCO-backed PCM output to receive or derive the selected communication endpoint for the resolved route
- [ ] 5.2 Apply `AudioTrack.setPreferredDevice(...)` for `playReadyBeep`, `playErrorBeep`, and `play(recording)`
- [ ] 5.3 Make SCO-backed output fail or report an error when the selected endpoint is no longer active instead of intentionally routing through another endpoint
- [ ] 5.4 Confirm echo playback, STT/TTS playback, STT+TTS playback, system announcements, and error beeps all use the endpoint-bound output path

## 6. Controller and Route Cleanup

- [ ] 6.1 Update channel controllers only as needed to consume endpoint-bound `ResolvedAudioRoute` without reintroducing direct endpoint selection
- [ ] 6.2 Remove or restrict any generic `resolveAudioRoute` helper paths that can select SCO solely because any SCO endpoint is active
- [ ] 6.3 Keep `PttSource` limited to press/release ownership and do not use it for route selection
- [ ] 6.4 Update tests that previously asserted generic SCO selection to assert endpoint-specific selection instead

## 7. Verification

- [ ] 7.1 Run `nix develop --no-write-lock-file -c gradle test` and confirm all JVM tests pass
- [ ] 7.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug` and confirm the APK builds
- [ ] 7.3 Manual device test with both car and RSM connected: RSM PTT in debug echo records and plays through the RSM only
- [ ] 7.4 Manual device test with both car and RSM connected: car PTT records through car call audio and any response plays through car media output after route switch
- [ ] 7.5 Manual device test with both car and RSM connected: phone channel long-press uses phone/local capture and output without acquiring SCO
- [ ] 7.6 Manual device test not-ready/error feedback for Work, OnTheRoad, and OnAPinch routes without cross-routing between car and RSM
