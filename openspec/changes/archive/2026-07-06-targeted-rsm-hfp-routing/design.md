## Context

Subspace has three semantic input modes with different intended endpoints:

- `Work`: physical RSM (`B02PTT-FF01`) button and headset audio.
- `OnTheRoad`: car / Android Auto button and Telecom self-call audio.
- `OnAPinch`: phone long-press and local phone audio.

The current Work route uses Android `AudioManager`/`AudioDeviceInfo` to locate a Bluetooth SCO transport. Live diagnostics with car + Android Auto + RSM connected showed that the only SCO transport exposed on the target phone is anonymous from the product-name perspective:

```text
id=8369 type=BLUETOOTH_SCO product='CPH2653' targetRsm=false
```

The removed anonymous fallback selected that transport and routed RSM PTT audio through the car. The fail-closed hotfix removed the fallback, preventing wrong routing but making Work unavailable when `AudioDeviceInfo.productName` does not expose `B02PTT-FF01`.

A targeted HFP spike changed the evidence. With car + Android Auto + RSM connected, the app called:

```kotlin
BluetoothHeadset.startVoiceRecognition(targetRsm)
BluetoothHeadset.isAudioConnected(targetRsm)
BluetoothHeadset.stopVoiceRecognition(targetRsm)
```

The spike showed `startVoiceRecognition(B02PTT-FF01)` returned true and `isAudioConnected(B02PTT-FF01)` became true while the `AudioManager` transport still appeared as anonymous `BLUETOOTH_SCO product='CPH2653'`. Therefore `AudioDeviceInfo` is a transport identity, not a semantic endpoint identity.

## Goals / Non-Goals

**Goals:**

- Make `Work` and `OnTheRoad` available at the same time when RSM and car are both connected.
- Route Work/RSM PTT through the physical RSM, not the car.
- Preserve fail-closed behavior when the system cannot prove RSM ownership of the SCO transport.
- Use only public Android APIs; no hidden API or reflection-based Bluetooth route selection.
- Keep one active PTT capture at a time.
- Keep route ownership explicit so warm retention cannot leak across modes.
- Preserve existing Telecom self-call behavior for OnTheRoad.
- Add diagnostics that prove BluetoothDevice ownership and AudioManager transport state without logging MAC addresses or PCM/audio payloads.
- Remove temporary spike-only interception before delivering production behavior.

**Non-Goals:**

- Supporting simultaneous active Work and OnTheRoad captures.
- Supporting arbitrary Bluetooth headsets as Work endpoints.
- Guessing RSM identity from anonymous `AudioDeviceInfo` slots.
- Disconnecting or reconfiguring the car Bluetooth profile to force RSM routing.
- Using hidden Android APIs such as active-device setters not available to normal apps.
- Solving unrelated local/phone playback audibility issues unless they are caused by route ownership leakage introduced by this change.
- Changing channel semantics, STT/TTS behavior, journal behavior, or Android Auto browsing behavior.

## Decisions

### Decision 1: Treat endpoint identity and audio transport as separate layers

`AudioDeviceInfo` SHALL be treated as the audio transport layer. It may say only `BLUETOOTH_SCO product='CPH2653'` on the target phone. It is not sufficient to identify RSM ownership.

`BluetoothDevice` from the `BluetoothHeadset` profile SHALL be treated as the endpoint identity layer for Work. The target RSM is the bonded `BluetoothDevice` named `B02PTT-FF01`.

Alternative considered: continue using `AudioDeviceInfo.productName`. Rejected because the target phone does not expose the RSM name there.

Alternative considered: accept the first anonymous SCO endpoint when RSM HFP is connected. Rejected because live diagnostics proved it can bind Work to the car.

### Decision 2: Work availability is logical RSM readiness, not visible SCO transport identity

`Work` availability SHALL be based on the target RSM being ready as a device:

- Bluetooth permissions granted.
- Bluetooth enabled.
- Target RSM bonded.
- Target RSM SPP/serial connection open enough for button events and monitor state.
- Target RSM connected in the `BluetoothHeadset` profile.

The presence of an `AudioDeviceInfo` whose product name contains `B02PTT-FF01` SHALL NOT be required for Work availability.

This allows `Work` and `OnTheRoad` to be available simultaneously while keeping acquisition fail-closed.

### Decision 3: Work acquisition is targeted HFP acquisition

When Work audio is acquired, the route controller SHALL:

1. Resolve the target RSM `BluetoothDevice`.
2. Verify the target RSM HFP connection state is connected.
3. Call `BluetoothHeadset.startVoiceRecognition(targetRsm)`.
4. Poll `BluetoothHeadset.isAudioConnected(targetRsm)` or observe `ACTION_AUDIO_STATE_CHANGED` until ownership is proven or timeout expires.
5. After ownership is proven, accept the generic active `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` as the transport for Work capture/playback.
6. Set any `AudioTrack` preferred device to the selected SCO transport when available.

If any step fails, acquisition SHALL fail closed and no Work capture/playback SHALL begin.

Alternative considered: using `AudioManager.setCommunicationDevice` before proving BluetoothDevice ownership. Rejected as the primary ownership proof because it still selects an anonymous transport that may be the car.

### Decision 4: Work release stops target RSM voice recognition

When Work route ownership is released, the route controller SHALL call `BluetoothHeadset.stopVoiceRecognition(targetRsm)` for the target RSM and clear its selected SCO transport state after ownership ends.

Warm retention MAY remain for rapid RSM re-presses, but only while the retained route is still owned by the target RSM. Warm retention SHALL NOT be reused by a different input source.

### Decision 5: Route ownership must be source-bound

The source actuator determines the target endpoint:

- `Rsm` → Work/RSM endpoint.
- `CarTelecom` → OnTheRoad/car endpoint.
- `Phone` → OnAPinch/local endpoint.

If an actuator cannot transition to or acquire its home route, the system SHALL fail closed and SHALL NOT resolve feedback through the previous/current mode's endpoint. This prevents RSM failure feedback from playing through the car.

### Decision 6: Keep Telecom car ownership separate

OnTheRoad continues to use the existing Telecom self-call lifecycle. Car route acceptability remains based on Telecom call route state and rejection of the target RSM name where visible. This change does not route the car through `BluetoothHeadset.startVoiceRecognition`.

### Decision 7: Diagnostics prove both layers

Work diagnostics SHALL log:

- target RSM name, not MAC address,
- HFP connection state,
- `startVoiceRecognition` return value,
- `isAudioConnected(targetRsm)` polling/result,
- selected anonymous SCO transport via `routeDebugString`,
- failure reason,
- release/stop result.

Expected successful Work acquisition may look like:

```text
RSM_HFP_START target='B02PTT-FF01' returned=true
RSM_HFP_AUDIO_CONNECTED target='B02PTT-FF01' true
SCO_TRANSPORT_SELECT owner=Rsm device=id=8369 type=BLUETOOTH_SCO product='CPH2653' targetRsm=false
```

The `targetRsm=false` on `AudioDeviceInfo` is acceptable only when the HFP owner proof is true.

## Risks / Trade-offs

- [Risk] `startVoiceRecognition` behavior may vary by OEM or Bluetooth stack. → Mitigation: keep acquisition fail-closed, log return values and audio-connected polling, and preserve car route behavior when acquisition fails.
- [Risk] The RSM HFP audio path can open but PCM capture/playback may still route incorrectly. → Mitigation: acceptance requires audible ready beep/capture/playback through the RSM with car connected.
- [Risk] Warm retention of RSM HFP audio can interfere with car PTT. → Mitigation: bind warm retention to RSM ownership only and hard-release/stop RSM voice recognition before acquiring car/local routes.
- [Risk] `AudioDeviceInfo` remains anonymous, making debugging confusing. → Mitigation: log both semantic owner (`B02PTT-FF01` HFP audio state) and transport (`AudioDeviceInfo`) together.
- [Risk] Bluetooth profile callbacks can race with PTT press/release. → Mitigation: acquisition waits for target audio ownership with timeout; release is idempotent; reference counting remains balanced.
- [Risk] Error feedback may cross-route after a failed actuator transition. → Mitigation: transition/acquisition failures for a source without a valid home route skip route-resolved feedback.

## Migration Plan

1. Remove the temporary spike-only RSM PTT interception.
2. Add a production targeted RSM HFP route owner/controller around `BluetoothHeadset` and target `BluetoothDevice`.
3. Update Work availability to use logical RSM SPP + target HFP readiness rather than target product name in `AudioDeviceInfo`.
4. Update the Work SCO route acquisition/release path to use the targeted HFP owner proof.
5. Keep fail-closed behavior when target HFP acquisition fails.
6. Add unit coverage for route selection, availability, and failure feedback rules.
7. Install on the physical phone and validate car+RSM acceptance scenarios.

Rollback strategy: restore the fail-closed behavior that refuses anonymous SCO ownership. That preserves safety at the cost of Work being unavailable on the target phone while the car is connected.

## Open Questions

- Whether production acquisition should rely on polling `BluetoothHeadset.isAudioConnected(targetRsm)`, `ACTION_AUDIO_STATE_CHANGED`, or both. Polling was sufficient in the spike; broadcasts may improve diagnostics.
- Whether the 30-second warm retention should remain enabled when Android Auto is connected, or be shortened/disabled to reduce route-switch latency between Work and OnTheRoad.
- Whether local/OnAPinch output should be separately changed to force speaker/media output; current evidence suggests the local inaudible issue is not caused by stale active SCO at route resolution.
