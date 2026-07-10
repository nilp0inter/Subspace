## Why

Subspace still has route transitions where time passing can allow the next audio path to proceed even when Android has not proven the previous route released or the next route is ready. This breaks On-the-road capture after a warm Work/RSM SCO session: Telecom can report a car call route while the actual communication/capture path is still stale, blocked, or unverified.

## What Changes

- Replace route-transition success inferred from elapsed time with gates satisfied by observed Android OS state.
- Keep timeouts only as bounds that fail closed; a timeout SHALL NOT mean a route is ready, released, or safe to capture from.
- Add internal audio input subsystem gates for:
  - releasing a warm Work/RSM SCO route before switching to Phone or On-the-road;
  - preparing and validating an On-the-road Telecom/car capture route;
  - validating capture startup before delivering channel input start events;
  - releasing Telecom/car routes when setup fails before capture begins.
- Preserve warm Work/RSM SCO reuse when the next requested input is another Work/RSM PTT.
- Preserve the channel boundary: channels continue to receive only channel input events, live PCM streams, terminal PCM, cancellation, and failure.
- Do not expose `AudioManager`, `BluetoothHeadset`, Telecom route state, `ScoRoute`, `PcmOutput`, `CaptureSource`, `ResolvedAudioRoute`, or input-mode strategy details to channels.
- Do not redesign STT, TTS, journal processing, keyboard HID output, Bluetooth pairing, UI layout, release signing, Gradle, or SDK configuration.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `audio-input-session-lifecycle`: Route acquisition, route release, setup failure, and channel input start SHALL be gated by observed OS/device state inside the audio input subsystem.
- `sco-audio`: Work/RSM SCO warm retention SHALL be reusable for Work/RSM, but route switches away from Work SHALL require observed RSM release rather than elapsed time.
- `telecom-voip-car-ptt`: On-the-road capture SHALL require observed car/Telecom route readiness and SHALL release the pending route if setup fails before capture starts.
- `capture-service`: Capture startup SHALL surface enough internal result information for the audio input subsystem to fail closed before channel input is reported when the recorder cannot be proven usable.
- `channel-framework`: Channels SHALL remain isolated from route-state gates and continue to consume only channel-level input events and audio data.

## Impact

- Affected production code: `PttAudioSessionManager`, `ScoAudioController`, `CarTelecomStarter`, `TelecomCarPttLifecycle`, `SubspaceConnection`, `PttAudioRouteResolver`, route abstractions in `AudioPorts`, and Android capture source wiring.
- Affected Android APIs: `BluetoothHeadset.isAudioConnected`, `BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED`, `AudioManager.communicationDevice`, `AudioManager.OnCommunicationDeviceChangedListener`, Telecom `CallAudioState` / call endpoint callbacks, `AudioManager.getActiveRecordingConfigurations`, and `AudioRecordingConfiguration`.
- Affected tests: audio input session lifecycle tests, SCO route transition tests, Telecom car PTT setup/failure tests, capture startup failure tests, and channel-boundary tests.
- No new external dependencies.
- No change to persisted channel configuration, Android SDK targets, hidden API policy, Gradle/Nix tooling, or release signing.
