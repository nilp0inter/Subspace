## MODIFIED Requirements

### Requirement: Dashboard is the default app view
The system SHALL show the main dashboard as the default interactive app view after core bootstrap readiness completes. Before that readiness result, the system SHALL show passive loading, actionable setup, or bootstrap recovery according to authoritative bootstrap state. RSM and other optional external readiness SHALL NOT prevent dashboard entry after core readiness.

#### Scenario: App starts while core bootstrap is incomplete
- **WHEN** the app launches and core bootstrap has not completed successfully
- **THEN** the system shows loading, setup, or recovery for the current bootstrap state
- **AND** the system does not render the dashboard from default placeholder state

#### Scenario: App starts while device is not ready
- **WHEN** core bootstrap reaches ready and the RSM device readiness gate is false
- **THEN** the system shows the main dashboard
- **AND** the system does not automatically show the legacy connection screen
- **AND** the Work/RSM tile reflects the unavailable or setup state

#### Scenario: App starts while device is ready
- **WHEN** core bootstrap reaches ready and the RSM device readiness gate is true
- **THEN** the system shows the main dashboard
- **AND** the system does not automatically show the legacy monitor screen

#### Scenario: Optional peripheral remains unavailable
- **WHEN** core bootstrap reaches ready while Keyboard BLE, Android Auto, or another optional external capability is unavailable
- **THEN** the system shows the main dashboard
- **AND** the optional capability continues to communicate its own readiness inside the dashboard or drill-down surface

#### Scenario: Bootstrap completes after setup
- **WHEN** a setup action returns to loading and all core readiness conditions subsequently complete
- **THEN** the system shows the main dashboard automatically
- **AND** no separate dashboard-entry acknowledgement is requested
