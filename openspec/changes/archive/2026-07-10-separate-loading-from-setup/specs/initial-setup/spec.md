## ADDED Requirements

### Requirement: Setup presents only known user actions
The system SHALL show the setup surface only after bootstrap has identified a concrete user-resolvable prerequisite: missing runtime permissions or model assets that require explicit download or repair. The system SHALL NOT show setup during passive checking, native speech initialization, controller construction, announcement rendering, or autonomous recovery.

#### Scenario: Prerequisites are still unknown
- **WHEN** bootstrap has not completed permission and model inspection
- **THEN** the loading surface is shown
- **AND** the setup surface is not shown

#### Scenario: Permission action is known
- **WHEN** bootstrap identifies one or more missing required runtime permissions
- **THEN** setup shows the permission action and the exact missing state

#### Scenario: Model action is known
- **WHEN** bootstrap identifies an absent, version-mismatched, or hash-invalid model set
- **THEN** setup shows the model download or repair action

### Requirement: Setup actions continue automatically
The system SHALL re-evaluate prerequisites after every setup action and SHALL leave setup automatically when no further user action is currently required. The system SHALL NOT require a separate confirmation to continue bootstrap or enter the dashboard.

#### Scenario: Permission grant completes all setup actions
- **WHEN** the permission result grants all required permissions and model assets are already valid
- **THEN** the system automatically replaces setup with loading
- **AND** core preparation continues

#### Scenario: Permission grant reveals model action
- **WHEN** the permission result grants all required permissions but model assets require acquisition or repair
- **THEN** setup remains visible with the model action available

#### Scenario: User starts model acquisition
- **WHEN** the user starts the required model download or repair action
- **THEN** the system automatically replaces setup with loading
- **AND** loading shows real acquisition progress

#### Scenario: Model acquisition fails
- **WHEN** user-initiated model acquisition fails and requires an explicit retry
- **THEN** the system returns to setup with the concrete failure and retry action

#### Scenario: Model acquisition completes
- **WHEN** user-initiated model acquisition completes and all required model sets pass full verification
- **THEN** the system continues from loading into core preparation without another setup acknowledgement

## MODIFIED Requirements

### Requirement: Initial setup screen gates main app
The system SHALL present a setup screen after passive bootstrap checking on first launch or any subsequent launch where a required permission is missing or a model set requires explicit download or repair. The setup screen SHALL gate the dashboard while a user-resolvable prerequisite remains incomplete, and setup completion SHALL return automatically to passive loading for core preparation.

#### Scenario: First launch begins with loading
- **WHEN** the app is launched for the first time after install
- **THEN** the passive loading surface is shown while setup prerequisites are checked
- **AND** setup is shown only after missing permission or model actions are known

#### Scenario: All setup steps complete resumes loading
- **WHEN** all setup steps are completed with permissions granted and model assets fully verified
- **THEN** the system automatically returns to loading for core initialization
- **AND** no “Enter Subspace” action is required

#### Scenario: Permission revoked on later launch
- **WHEN** the app is launched and any required runtime permission has been revoked since the previous launch
- **THEN** loading transitions to setup with the permissions step marked incomplete

#### Scenario: Model corrupted on later launch
- **WHEN** the app is launched and any model file's SHA-256 does not match the bundled hash manifest
- **THEN** loading transitions to setup with the model step marked incomplete and an explicit repair action

#### Scenario: All checks pass on launch
- **WHEN** the app is launched and all permissions are granted and all model files pass SHA-256 verification
- **THEN** the setup screen is skipped
- **AND** loading continues through core initialization before the dashboard is shown

## REMOVED Requirements

### Requirement: "Enter Subspace" button gated on completion
**Reason**: Setup now exists only while a user-resolvable prerequisite is incomplete. A second acknowledgement after prerequisites complete misleadingly suggests another decision and prevents automatic continuation through core loading.

**Migration**: Remove the button and its callback. Drive setup exit from authoritative prerequisite/bootstrap state; after the final setup action, return automatically to loading and enter the dashboard only when core readiness completes.
