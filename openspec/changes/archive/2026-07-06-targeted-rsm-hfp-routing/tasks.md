## 1. Remove Spike and Establish Route Ownership Model

- [x] 1.1 Remove the temporary `runTargetedHfpSpike` RSM PTT interception and restore normal RSM dispatch flow
- [x] 1.2 Introduce a production Work route owner abstraction that can track target RSM HFP ownership separately from `AudioDeviceInfo` transport identity
- [x] 1.3 Add route ownership state for target RSM device, selected SCO transport, active client count, warm-retention job, and failure state

## 2. Targeted RSM HFP Acquisition

- [x] 2.1 Resolve the bonded target `B02PTT-FF01` `BluetoothDevice` from the existing scanner/connection state
- [x] 2.2 Verify target RSM Headset/HFP profile connection before Work route acquisition
- [x] 2.3 Call `BluetoothHeadset.startVoiceRecognition(targetRsm)` during cold Work acquisition
- [x] 2.4 Wait for `BluetoothHeadset.isAudioConnected(targetRsm)` or target audio-state confirmation with timeout
- [x] 2.5 Select the active generic `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` as Work transport only after target RSM HFP ownership is proven
- [x] 2.6 Fail closed and clean up target voice recognition when start, ownership proof, or transport selection fails

## 3. Work Release and Warm Retention

- [x] 3.1 Release Work route ownership with `BluetoothHeadset.stopVoiceRecognition(targetRsm)` when warm retention expires or is cancelled
- [x] 3.2 Preserve warm retention only for target RSM-owned Work route reuse
- [x] 3.3 Hard-release target RSM HFP audio before acquiring OnTheRoad or OnAPinch routes
- [x] 3.4 Keep route reference counting balanced across normal completion, cancellation, acquisition failure, and post-capture failure

## 4. Availability and Dispatch Semantics

- [x] 4.1 Update Work availability to use permissions, Bluetooth enabled, target RSM bonded, SPP connected, and target RSM HFP connected
- [x] 4.2 Allow Work and OnTheRoad to be available simultaneously when RSM and Android Auto are both connected
- [x] 4.3 Keep RSM PTT auto-transition to Work when Work is logically available
- [x] 4.4 Ensure Work acquisition failure does not dispatch capture and does not resolve through the car route
- [x] 4.5 Ensure transition/acquisition failure feedback is skipped unless the actuator's home route can be safely acquired

## 5. Diagnostics

- [x] 5.1 Add `SubspaceRoute` logs for target RSM HFP connection state, start result, audio-connected polling, selected SCO transport, and stop result
- [x] 5.2 Log semantic owner and transport identity together so anonymous `AudioDeviceInfo` slots are understandable
- [x] 5.3 Keep diagnostics privacy-safe by excluding Bluetooth MAC addresses and PCM/audio payloads

## 6. Tests

- [x] 6.1 Add unit tests for Work availability with anonymous SCO transport and target RSM HFP connected
- [x] 6.2 Add unit tests for Work availability with target RSM HFP disconnected
- [x] 6.3 Add unit tests for RSM route acquisition accepting anonymous SCO only after target ownership proof
- [x] 6.4 Add unit tests for acquisition failures returning fail-closed with no car fallback
- [x] 6.5 Add unit tests for Work warm retention reuse and hard release before non-Work route acquisition
- [x] 6.6 Add unit tests for RSM transition/acquisition failure skipping cross-route error beep

## 7. Device Verification

- [x] 7.1 Build and install the debug APK on the connected test phone
- [x] 7.2 With car and RSM connected, verify the UI shows both Work and OnTheRoad available
- [x] 7.3 Press RSM PTT and verify logs show `startVoiceRecognition(B02PTT-FF01)` success and `isAudioConnected(B02PTT-FF01)=true`
- [x] 7.4 Verify Work ready beep, capture, and playback route through the RSM and not the car
- [x] 7.5 Release RSM PTT, then press car PTT and verify OnTheRoad still routes through the car
- [x] 7.6 Simulate or force target RSM HFP acquisition failure and verify no capture or error beep routes through the car
- [x] 7.7 Run the relevant Gradle test task and record the command/output in the implementation summary
