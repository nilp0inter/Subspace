## ADDED Requirements

### Requirement: Non-Work problem feedback honors route-release gates
When a PTT request selects a non-Work route but cannot start capture because the selected channel is unavailable or refuses input, the system SHALL await the resolved route's route gate before playing problem feedback. It SHALL NOT play non-Work feedback while a prior Work/RSM route is still observed as active.

#### Scenario: Warm Work route precedes unavailable phone channel
- **WHEN** a warm Work/RSM route exists
- **AND** phone PTT selects On-a-pinch
- **AND** the selected channel cannot accept input
- **THEN** the system SHALL await observed Work-route release before attempting local problem feedback
- **AND** SHALL not retain the RSM communication device solely because capture was refused

#### Scenario: Work-route release gate fails before feedback
- **WHEN** a non-Work problem-feedback attempt cannot observe Work-route release before its configured timeout
- **THEN** the system SHALL not play feedback through the stale route
- **AND** SHALL perform the resolved route cleanup contract without leaving a new route lease behind
