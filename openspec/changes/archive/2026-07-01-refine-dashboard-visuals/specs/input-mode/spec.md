## MODIFIED Requirements

### Requirement: User mode selection
The system SHALL allow the user to select any available mode from the main
dashboard. If the Work/RSM mode is unavailable, activating the Work/RSM tile SHALL
open the RSM setup flow instead of transitioning modes.

#### Scenario: User selects an available mode
- **WHEN** the user taps an available mode in the mode selector
- **THEN** the system SHALL transition to that mode
- **AND** apply the mode's audio route policy and actuator gating

#### Scenario: User taps unavailable Work mode
- **WHEN** the user taps the Work/RSM mode tile while Work mode is unavailable
- **THEN** the system SHALL not transition to Work mode
- **AND** the system SHALL open the RSM setup flow

#### Scenario: User taps unavailable non-Work mode
- **WHEN** the user taps an unavailable non-Work mode
- **THEN** the system SHALL not transition and SHALL not register a mode-selection tap on the greyed-out control

#### Scenario: Rules re-assert after user selection
- **WHEN** the user selects a mode and a subsequent device event triggers a transition rule
- **THEN** the rule SHALL take effect regardless of the user's prior selection

### Requirement: Visible mode selector on main dashboard
The system SHALL display a fixed-height mode selector on the main dashboard
showing all three modes with icon-first availability indicators. The selector
SHALL show Work/RSM with a headset icon, OnTheRoad with a steering-wheel or car
control icon, and OnAPinch with a phone icon. Each mode SHALL remain visible even
when unavailable.

#### Scenario: All modes visible
- **WHEN** the main dashboard is displayed
- **THEN** the selector SHALL show `Work`, `OnTheRoad`, and `OnAPinch` as fixed-height tile controls
- **AND** each tile SHALL include a large mode icon and concise mode label

#### Scenario: Unavailable mode greyed out
- **WHEN** a mode is unavailable
- **THEN** its selector tile SHALL remain visible at the same size as available tiles
- **AND** its unavailable state SHALL be shown through tile color, icon intensity, status text, or an indicator

#### Scenario: Active mode highlighted
- **WHEN** a mode is active
- **THEN** its selector tile SHALL be visually highlighted to indicate the current mode

## ADDED Requirements

### Requirement: Work RSM tile opens setup by long press
The Work/RSM mode tile SHALL provide setup access through long-press regardless
of current Work mode availability.

#### Scenario: User long-presses available Work tile
- **WHEN** the dashboard is visible, Work mode is available, and the user long-presses the Work/RSM tile
- **THEN** the system SHALL open the RSM setup or monitor flow
- **AND** the system SHALL NOT change input mode as a result of the long-press

#### Scenario: User long-presses unavailable Work tile
- **WHEN** the dashboard is visible, Work mode is unavailable, and the user long-presses the Work/RSM tile
- **THEN** the system SHALL open the RSM setup flow
- **AND** the system SHALL NOT transition to Work mode

### Requirement: Mode icons follow visual identity
The input mode selector SHALL use app-provided line-art icons that match the
Subspace visual identity: consistent stroke weight, rounded caps or corners, and
state-driven accent treatment. The selector SHALL NOT use emoji as mode icons.

#### Scenario: Mode selector renders icons
- **WHEN** the main dashboard mode selector is displayed
- **THEN** the Work/RSM tile uses a headset-style icon
- **AND** the OnTheRoad tile uses a steering-wheel or car-control-style icon
- **AND** the OnAPinch tile uses a phone-style icon
- **AND** none of the mode icons are emoji glyphs
