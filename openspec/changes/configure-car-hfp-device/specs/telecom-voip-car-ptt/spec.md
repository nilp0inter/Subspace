## MODIFIED Requirements

### Requirement: Car HFP selection and priming hand exact ownership to Telecom
On-the-road setup SHALL resolve the car HFP endpoint from the one persistently configured car identity and the current HEADSET profile state, not from display name or candidate cardinality. Resolution SHALL return the matching live `BluetoothDevice` only when that exact configured identity reports `BluetoothProfile.STATE_CONNECTED` and is not the exact target RSM. Missing, absent, disconnected, conflicting, or unverifiable configuration SHALL fail closed before HFP priming, expected-device reservation, or Telecom placement; the system SHALL NOT choose another connected device or restore unique-candidate inference. Successful priming SHALL remain a bounded pulse on the resolved exact car device: observe its HFP audio connect, stop voice recognition on the same device, observe its HFP audio disconnect, and reserve that device as the expected Telecom route before placing the call. The priming route SHALL NOT remain concurrently owned while Telecom acquires call audio. A failed or timed-out prime SHALL NOT be ignored when deciding whether the car capture route is ready.

#### Scenario: Configured exact car is selected among multiple devices
- **WHEN** an exact car identity is configured
- **AND** the current connected HFP set contains that car and any number of other devices
- **AND** the configured car is not the exact target RSM
- **THEN** the system SHALL select the matching live configured car regardless of display names or list order
- **AND** SHALL NOT require it to be the only connected non-RSM candidate

#### Scenario: No car is configured
- **WHEN** On-the-road PTT setup begins without a configured car identity
- **THEN** the system SHALL fail setup before HFP priming, expected-device reservation, or Telecom placement
- **AND** SHALL NOT infer a car even if exactly one non-RSM HFP device is connected

#### Scenario: Configured car is absent or disconnected
- **WHEN** On-the-road PTT setup begins with a configured car identity
- **AND** no matching live HEADSET-profile device reports `STATE_CONNECTED`
- **THEN** the system SHALL fail setup before HFP priming or Telecom placement
- **AND** SHALL NOT substitute another connected HFP device

#### Scenario: Configured car conflicts with target RSM
- **WHEN** the configured car identity equals the exact target RSM identity
- **THEN** the system SHALL treat the configuration as invalid
- **AND** SHALL fail setup before HFP priming or Telecom placement

#### Scenario: Configuration changes after operation resolution
- **WHEN** an On-the-road operation has resolved and begun priming one exact configured car
- **AND** the persisted car configuration is replaced before that operation terminates
- **THEN** the in-flight operation SHALL retain ownership and cleanup responsibility for its originally resolved exact device
- **AND** the replacement SHALL apply only to later operations

#### Scenario: Car HFP prime becomes connected
- **WHEN** `startVoiceRecognition(car)` succeeds for the resolved configured car
- **AND** HFP audio becomes connected for that same car device
- **THEN** the system SHALL call `stopVoiceRecognition(car)`
- **AND** wait until HFP audio is disconnected for that device
- **AND** reserve that exact car as the expected Telecom Bluetooth route
- **AND** only then place the self-managed Telecom call

#### Scenario: Car HFP handoff does not disconnect
- **WHEN** voice recognition was stopped for the resolved configured car device
- **AND** its HFP audio does not disconnect before the configured timeout
- **THEN** the system SHALL fail the car PTT setup
- **AND** SHALL NOT place the Telecom call

#### Scenario: Car HFP priming is cancelled
- **WHEN** the priming operation is cancelled after voice recognition starts
- **THEN** the system SHALL stop voice recognition on the exact started device
- **AND** SHALL NOT retain a priming route or expected-device reservation for later Telecom cleanup

#### Scenario: Expected-device reservation cannot be created
- **WHEN** a previous Telecom connection or expected-device reservation still owns the coordinator
- **THEN** the system SHALL fail the new car PTT setup before `placeCall`
- **AND** SHALL preserve a single owner rather than overwrite the existing reservation
