## ADDED Requirements

### Requirement: Phone PTT is not terminated by unrelated device lifecycles
A held or slide-locked Phone PTT session SHALL remain active until a Phone-owned release or cancellation, maximum-duration cutoff, whole-service teardown, capture failure, or another existing global validity failure terminates it. RSM serial reconnect failure, RSM serial stream loss, explicit RSM serial disconnect, and stale Telecom lifecycle callbacks SHALL NOT terminate Phone PTT.

#### Scenario: Held Phone PTT survives RSM reconnect failure
- **WHEN** an unlocked Phone PTT session remains held
- **AND** an automatic RSM serial reconnect attempt fails
- **THEN** the Phone PTT session SHALL remain active
- **AND** capture SHALL continue without a synthetic Phone release

#### Scenario: Locked Phone PTT survives RSM reconnect failure
- **WHEN** a Phone PTT session is slide-locked
- **AND** an automatic RSM serial reconnect attempt fails or its serial stream ends
- **THEN** the Phone PTT session SHALL remain locked and active
- **AND** the visible stop action SHALL remain its ordinary explicit terminal control

#### Scenario: Phone PTT survives stale Telecom timeout
- **WHEN** Phone PTT is pending or active
- **AND** a timeout or terminal callback arrives from an earlier Telecom operation
- **THEN** the Phone session SHALL remain unchanged

#### Scenario: Existing Phone terminal controls remain effective
- **WHEN** Phone PTT receives finger release while unlocked, explicit stop while locked, focus loss, gesture cancellation, maximum-duration cutoff, capture failure, or whole-service teardown
- **THEN** the session SHALL terminate according to its existing Phone or global lifecycle contract
