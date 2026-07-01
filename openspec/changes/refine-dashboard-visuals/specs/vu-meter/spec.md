## MODIFIED Requirements

### Requirement: Meter visible only while capturing

The VU meter SHALL always be visible in the dashboard layout. While
`isCapturing` is true, it SHALL render live level and peak feedback. While no
capture session is active, it SHALL render an idle standby state and SHALL retain
its normal layout space.

#### Scenario: Meter enters active capture state
- **WHEN** `isCapturing` transitions from false to true
- **THEN** the meter remains in place
- **AND** the meter transitions from standby rendering to live capture rendering

#### Scenario: Meter returns to standby state
- **WHEN** `isCapturing` transitions from true to false
- **THEN** the meter remains in place
- **AND** the meter transitions to idle standby rendering
- **AND** the meter continues to reserve its normal layout space

#### Scenario: Meter remains present while idle
- **WHEN** no capture session is active
- **THEN** the meter renders a dim idle track and standby label
- **AND** no dashboard content shifts because of meter absence
