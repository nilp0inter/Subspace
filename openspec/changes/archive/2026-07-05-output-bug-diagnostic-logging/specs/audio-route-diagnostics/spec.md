## ADDED Requirements

### Requirement: Route diagnostics are collected under one logcat tag
The system SHALL emit debug route diagnostics under the `SubspaceRoute` logcat tag so a mixed car/RSM/mobile route trace can be captured without unrelated application logs.

#### Scenario: Capture only route diagnostics
- **WHEN** a developer runs logcat filtered to `SubspaceRoute`
- **THEN** route diagnostics for PTT dispatch, audio route resolution, SCO acquisition, Telecom call-audio readiness, and route release SHALL be visible under that tag
- **AND** unrelated model initialization, channel processing, and UI logs SHALL NOT be required to interpret the route trace

### Requirement: Diagnostics describe Android audio device state without sensitive payloads
The system SHALL format Android audio route state with device ids, device types, product names when present, RSM-target match flags, Android audio mode, and route-selection branch names, while excluding audio content and Bluetooth MAC addresses.

#### Scenario: Audio device state is logged
- **WHEN** route diagnostics include an Android `AudioDeviceInfo` value or device list
- **THEN** the diagnostic line SHALL include the device id, type, product name if available, whether it is a Bluetooth SCO endpoint, and whether it matches the target RSM predicate
- **AND** the diagnostic line SHALL tolerate null or missing product names

#### Scenario: Sensitive payloads are excluded
- **WHEN** route diagnostics are emitted during capture or playback
- **THEN** the diagnostic lines SHALL NOT include PCM samples, encoded audio, recordings, transcripts, Bluetooth MAC addresses, or secret values

### Requirement: PTT dispatch diagnostics correlate source, mode, and route endpoint
The system SHALL log route-boundary snapshots that correlate the PTT source, active input mode, mode availability, resolved route endpoint, Android audio mode, current communication device, and available communication devices.

#### Scenario: RSM PTT dispatch is traced
- **WHEN** the RSM PTT button starts dispatch
- **THEN** diagnostics SHALL log the source as `Rsm`, the mode before auto-transition, the auto-transition result, the mode after auto-transition, and the resolved route endpoint

#### Scenario: Phone PTT dispatch is traced
- **WHEN** a phone channel long-press starts dispatch
- **THEN** diagnostics SHALL log the source as `Phone`, the mode before auto-transition, the auto-transition result, the mode after auto-transition, the resolved route endpoint, and the current Android communication device before local route use

#### Scenario: Car PTT dispatch is traced
- **WHEN** Android Auto car PTT starts or Telecom capture starts dispatch
- **THEN** diagnostics SHALL log the source as `CarTelecom`, the mode before and after transition, and the resolved car route endpoint

### Requirement: SCO route diagnostics reveal RSM endpoint selection decisions
The system SHALL log enough `ScoAudioController` state to distinguish a positive RSM endpoint match from anonymous SCO fallback selection, Android route rejection, Android route replacement, timeout, and warm retention.

#### Scenario: SCO device scan is traced
- **WHEN** Work-mode readiness or route acquisition scans available communication devices
- **THEN** diagnostics SHALL log whether RSM HFP is connected, the current communication device, the available communication device list, and the selected branch: target product-name match, anonymous HFP fallback, or no matching device

#### Scenario: SCO acquisition is traced
- **WHEN** Work-mode route acquisition requests a communication device
- **THEN** diagnostics SHALL log the requested device, the `setCommunicationDevice()` result, the current communication device after the request, the selected device id, and the final acquisition result

#### Scenario: SCO warm retention is traced
- **WHEN** Work-mode route release starts warm retention or clears the retained route after the warm timeout
- **THEN** diagnostics SHALL log the selected device id, current communication device, active client count, and whether the communication route was cleared

### Requirement: Telecom route diagnostics reveal car call-audio readiness and release state
The system SHALL log Telecom car call-audio route accept/reject decisions and capture-route release snapshots so car-mode route behavior can be compared against Work and OnAPinch behavior.

#### Scenario: Telecom call-audio state changes
- **WHEN** a Subspace Telecom connection receives a call-audio state change
- **THEN** diagnostics SHALL log the call-audio route mask, active Bluetooth display name when available, whether Bluetooth call audio is present, and whether the route is acceptable for car capture

#### Scenario: Telecom capture route is released
- **WHEN** the Telecom capture route is released before media response playback or on route cleanup
- **THEN** diagnostics SHALL log Android audio mode and current communication device before and after clearing the communication route

### Requirement: Diagnostics do not change routing behavior
The diagnostic change SHALL NOT alter route selection, route acquisition, route release, audio focus, capture source selection, output device selection, mode transitions, or UI state.

#### Scenario: Diagnostic patch is behavior-neutral
- **WHEN** route diagnostics are enabled in a debug build
- **THEN** Work, OnTheRoad, and OnAPinch SHALL execute the same routing code paths and make the same route decisions they made before the diagnostic change
- **AND** any observed behavior difference SHALL be treated as a diagnostic patch bug
