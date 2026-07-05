## 1. Diagnostic Formatting

- [x] 1.1 Add a shared `SubspaceRoute` log tag for route diagnostics
- [x] 1.2 Add null-safe `AudioDeviceInfo` diagnostic formatting with id, type, product name, SCO flag, and target-RSM flag
- [x] 1.3 Add available-communication-device list formatting that excludes Bluetooth MAC addresses and audio payloads

## 2. SCO Route Diagnostics

- [x] 2.1 Log Work-route SCO scans with RSM HFP state, current communication device, and available communication devices
- [x] 2.2 Log SCO selection branch as target product-name match, anonymous HFP fallback, or no matching device
- [x] 2.3 Log SCO acquisition begin, candidate device, `setCommunicationDevice()` result, current device after request, selected id, and final outcome
- [x] 2.4 Log Work-route release and warm-retention lifecycle without changing warm-retention behavior

## 3. PTT and Mode Boundary Diagnostics

- [x] 3.1 Log PTT dispatch start with source, mode before transition, availability, Android audio mode, current device, and available devices
- [x] 3.2 Log source auto-transition result and mode after transition
- [x] 3.3 Log resolved route endpoint, SCO route class, output class, and capture source id for Work, OnTheRoad, and OnAPinch
- [x] 3.4 Log OnAPinch/local route audio-state snapshot before local capture or playback can begin

## 4. Telecom Diagnostics

- [x] 4.1 Log Telecom call-audio state changes with route mask, active Bluetooth display name, Bluetooth-route presence, and accept/reject result
- [x] 4.2 Log Telecom capture-route release snapshots before and after clearing communication device and restoring normal mode

## 5. Verification

- [x] 5.1 Run `nix develop --no-write-lock-file -c gradle test` and confirm JVM tests still pass
- [x] 5.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug` and confirm the debug APK builds
- [x] 5.3 Capture `SubspaceRoute` logcat for a car-only PTT cycle and confirm Telecom route lines are present
- [x] 5.4 Capture `SubspaceRoute` logcat for RSM PTT with car and RSM connected and confirm SCO scan, selection branch, acquisition, and warm-retention lines are present
- [x] 5.5 Capture `SubspaceRoute` logcat for phone/local PTT after the mixed car/RSM path and confirm the local route snapshot shows whether stale communication routing is present
