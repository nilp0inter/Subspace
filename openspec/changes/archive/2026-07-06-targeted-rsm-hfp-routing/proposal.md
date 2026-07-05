## Why

When the car and B02PTT-FF01 RSM are connected at the same time, Android exposes the SCO transport as an anonymous `AudioDeviceInfo` whose `productName` is the phone model (`CPH2653`) rather than the remote endpoint. The previous anonymous SCO fallback could therefore bind Work/RSM audio to the car; the fail-closed hotfix prevents the wrong route but makes Work unavailable on the target phone while OnTheRoad is available.

A live spike showed `BluetoothHeadset.startVoiceRecognition(B02PTT-FF01)` succeeds while the car remains connected and `BluetoothHeadset.isAudioConnected(B02PTT-FF01)` becomes true even though the active `AudioDeviceInfo` remains anonymous. This gives the missing endpoint ownership proof needed to support Work and OnTheRoad availability at the same time without guessing.

## What Changes

- Make Work mode availability depend on logical RSM readiness: target RSM bonded/connected over SPP plus target RSM connected in the Bluetooth Headset/HFP profile.
- Replace anonymous SCO selection for Work with targeted HFP acquisition:
  - call `BluetoothHeadset.startVoiceRecognition(targetRsm)` for `B02PTT-FF01`,
  - wait for `BluetoothHeadset.isAudioConnected(targetRsm)`,
  - accept the generic `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` only after RSM HFP ownership is proven.
- Release Work audio with `BluetoothHeadset.stopVoiceRecognition(targetRsm)` and clear/forget the selected SCO transport when the route is no longer owned by the RSM.
- Preserve fail-closed behavior: if targeted RSM HFP acquisition cannot be proven, Work PTT must not fall back to the car, must not capture, and must not play error feedback through the car route.
- Keep OnTheRoad availability independent of Work availability; the car route remains owned by the Telecom self-call lifecycle.
- Add route ownership diagnostics for targeted RSM HFP acquisition, polling, release, and failure.
- Remove temporary spike-only behavior that intercepts RSM PTT before normal dispatch.
- **BREAKING**: Work SCO route identity no longer comes from `AudioDeviceInfo.productName`; RSM ownership is proven by `BluetoothHeadset` target-device audio state.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `sco-audio`: Work SCO acquisition changes from anonymous/product-name `AudioDeviceInfo` selection to target-device HFP acquisition with `BluetoothHeadset` ownership proof.
- `input-mode`: Work availability changes from generic `connection.readyForMonitor`/SCO-device visibility to logical RSM SPP + target HFP readiness so Work and OnTheRoad can be available simultaneously.
- `channel-routing`: RSM PTT route resolution must acquire the Work route only after target RSM HFP ownership is proven and must fail closed without cross-routing feedback when acquisition fails.

## Impact

- Affected code:
  - `app/src/main/java/dev/nilp0inter/subspace/audio/ScoAudioController.kt`
  - `app/src/main/java/dev/nilp0inter/subspace/service/PttForegroundService.kt`
  - `app/src/main/java/dev/nilp0inter/subspace/audio/AndroidAudio.kt`
  - `app/src/main/java/dev/nilp0inter/subspace/audio/AudioPorts.kt`
  - `app/src/main/java/dev/nilp0inter/subspace/audio/AndroidAudioDevices.kt`
  - `app/src/main/java/dev/nilp0inter/subspace/service/InputModeController.kt`
  - relevant route resolver and unit tests
- Android APIs:
  - `BluetoothHeadset.startVoiceRecognition(BluetoothDevice)`
  - `BluetoothHeadset.stopVoiceRecognition(BluetoothDevice)`
  - `BluetoothHeadset.isAudioConnected(BluetoothDevice)`
  - `BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED` or equivalent polling as implementation detail
- Permissions remain within existing Bluetooth/audio permission scope; no hidden Android APIs are allowed.
- Diagnostics must not log Bluetooth MAC addresses or PCM/audio payloads. Device names/product names and route IDs remain acceptable.
