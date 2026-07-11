## MODIFIED Requirements

### Requirement: Channel readiness state
The system SHALL obtain readiness from the runtime entry associated with each channel instance. Readiness SHALL combine valid enabled provider configuration with the provider/runtime-declared availability of semantic capabilities required by that instance. Readiness and preparation eligibility SHALL be instance-specific even when multiple instances reference the same provider. Core routing SHALL NOT inspect provider identity, a built-in channel kind, or platform transport state to derive readiness.

#### Scenario: Ready instance
- **WHEN** an enabled channel instance has provider-validated configuration and its runtime reports every required semantic capability available
- **THEN** its runtime readiness SHALL be true

#### Scenario: Instance dependency unavailable
- **WHEN** a channel instance lacks valid configuration or its runtime reports a required semantic capability unavailable
- **THEN** its runtime readiness SHALL be false
- **AND** readiness of other instances SHALL be evaluated independently

#### Scenario: Inactive instance retains dependency readiness
- **WHEN** an enabled inactive instance has valid configuration and its runtime reports all required semantic capabilities available
- **THEN** its runtime readiness SHALL remain true
- **AND** active selection or shared-controller activation state SHALL NOT be treated as dependency availability

#### Scenario: Provider is unavailable
- **WHEN** an instance's referenced provider is missing, incompatible, or failed to initialize
- **THEN** its runtime entry SHALL report unavailable rather than ready
- **AND** core routing SHALL preserve and identify the instance without attempting provider-specific readiness logic

### Requirement: PTT routing respects readiness
The system SHALL evaluate the active catalogue instance's runtime readiness and preparation availability before dispatching a PTT capture, regardless of which input mode or actuator initiated PTT. Route resolution SHALL remain based on active `InputMode`, not `PttSource`. Ready or recoverable input SHALL be prepared through the ID-keyed runtime registry. A not-ready runtime whose provider/runtime reports recoverable preparation available SHALL enter pending input preparation; every other not-ready or unavailable runtime SHALL follow the immediate problem-feedback path. Core routing SHALL NOT use Keyboard, provider, implementation, hardware, or connection-state checks to choose recovery.

#### Scenario: PTT on a ready active instance in Work mode
- **WHEN** PTT is pressed while `Work` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Work audio route
- **AND** request a committed input target from the runtime associated with the active instance ID

#### Scenario: PTT on a ready active instance in On-the-road mode
- **WHEN** PTT is pressed while `OnTheRoad` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Telecom audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: PTT on a ready active instance in On-a-pinch mode
- **WHEN** PTT is pressed while `OnAPinch` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the local audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: Not-ready runtime enters recoverable preparation
- **WHEN** PTT is pressed while the selected runtime is not ready
- **AND** that runtime reports recoverable input preparation available
- **THEN** the system SHALL reserve one pending audio input session
- **AND** request recoverable input preparation from that runtime
- **AND** it SHALL NOT select the immediate not-ready problem-feedback path solely because a required capability is currently unavailable

#### Scenario: Not-ready runtime does not offer recovery
- **WHEN** PTT is pressed while the selected runtime is not ready or unavailable
- **AND** that runtime does not report recoverable input preparation available
- **THEN** the system SHALL select the immediate problem-feedback path
- **AND** it SHALL NOT request channel input preparation

#### Scenario: Phone PTT selects an instance before readiness admission
- **WHEN** the user starts phone PTT from any persisted functional channel card
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** set that card's stable instance ID as active regardless of preparation state
- **AND** route PTT admission through the matching runtime entry
- **AND** a non-recoverable unavailable runtime SHALL produce host-owned problem feedback without capture

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** RSM PTT is pressed while Work mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** route preparation to the active runtime instance

#### Scenario: Android Auto PTT auto-transitions to On-the-road mode
- **WHEN** Android Auto PTT is initiated while On-the-road mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** route preparation to the active runtime instance

#### Scenario: Runtime refuses preparation
- **WHEN** the active runtime becomes unavailable, recoverable preparation fails, or the runtime refuses input after route gating but before capture starts
- **THEN** the system SHALL NOT start capture or play the ready beep
- **AND** host-owned error feedback and route cleanup SHALL follow the existing audio session policy

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the host-resolved audio route if PTT is pressed while the target runtime is not ready and does not offer recoverable input preparation. If provider/runtime-driven recoverable preparation is attempted, the system SHALL defer the error decision until preparation fails or times out. The audio route SHALL be resolved from the active `InputMode`, and the host SHALL remain responsible for beep playback and route cleanup.

#### Scenario: PTT on a non-recoverable not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active runtime is not ready and does not offer recovery
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: PTT on a non-recoverable not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active runtime is not ready and does not offer recovery
- **THEN** the system SHALL play a two-tone error beep on the On-the-road mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: PTT on a non-recoverable not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active runtime is not ready and does not offer recovery
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: Phone PTT on a non-recoverable not-ready channel card
- **WHEN** a functional channel card is long-pressed and that runtime is not ready and does not offer recovery
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** set that channel's stable instance ID as active
- **AND** play a two-tone error beep on the On-a-pinch mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: Recoverable preparation succeeds
- **WHEN** PTT is pressed for a not-ready runtime that offers recoverable preparation
- **AND** preparation makes the required semantic capabilities available before timeout
- **THEN** the system SHALL NOT play the two-tone error beep
- **AND** it SHALL continue through the normal ready beep and host-owned capture path

#### Scenario: Recoverable preparation fails
- **WHEN** PTT is pressed for a not-ready runtime that offers recoverable preparation
- **AND** preparation fails, is cancelled, or times out
- **THEN** the system SHALL play the two-tone error beep on the resolved input-mode route when possible
- **AND** it SHALL drop the PTT capture without playing the ready beep
