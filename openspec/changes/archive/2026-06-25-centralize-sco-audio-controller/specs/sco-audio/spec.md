## MODIFIED Requirements

### Requirement: SCO warmup retention

The system SHALL use centralized reference counting to keep the SCO route active for 30 seconds after the last active client releases the route, so that subsequent sessions or rapid navigational announcements get instant SCO acquisition and reliable beep playback.

#### Scenario: Active client drops to zero — SCO retained warm
- **WHEN** all components that have acquired the SCO route release it (active client count drops to 0)
- **THEN** the system SHALL keep the SCO communication device set for 30 seconds
- **AND** the system SHALL report SCO state as `Active` during the warmup window

#### Scenario: Warmup expires
- **WHEN** 30 seconds pass with the active client count remaining at 0
- **THEN** the system SHALL clear the communication device
- **AND** the system SHALL set audio manager mode back to `MODE_NORMAL`
- **AND** the system SHALL report SCO state transition: `Active → Closing → Inactive`

#### Scenario: Route acquired during warmup
- **WHEN** a warmup delay is active and a component requests to acquire the SCO route
- **THEN** the system SHALL cancel the warmup delay
- **AND** the system SHALL increment the active client count
- **AND** the system SHALL return immediately without re-acquisition since the route is already active
