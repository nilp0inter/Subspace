## ADDED Requirements

### Requirement: On-the-road car tile opens car configuration by long press
The On-the-road/CAR mode tile SHALL provide access to the dedicated car-device configuration view through long-press regardless of current On-the-road mode availability. This setup gesture SHALL remain separate from the tile's ordinary mode-selection click.

#### Scenario: User long-presses available CAR tile
- **WHEN** the dashboard is visible, On-the-road mode is available, and the user long-presses the CAR tile
- **THEN** the system SHALL open the car-device configuration view
- **AND** SHALL NOT change the selected input mode as a result of the long-press

#### Scenario: User long-presses unavailable CAR tile
- **WHEN** the dashboard is visible, On-the-road mode is unavailable, and the user long-presses the CAR tile
- **THEN** the system SHALL open the car-device configuration view
- **AND** SHALL NOT transition to On-the-road mode

#### Scenario: Car setup long-press does not dispatch tile tap
- **WHEN** a CAR-tile long-press is recognized
- **THEN** the system SHALL dispatch only the car-configuration navigation action
- **AND** SHALL NOT also dispatch the ordinary CAR-tile selection action
