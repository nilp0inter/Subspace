# Initial Setup

## Purpose

Define the first-launch setup flow that gates access to the main application until required permissions and runtime models are ready.

## Requirements

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

### Requirement: Initial setup screen gates main app
The system SHALL present a setup screen after passive bootstrap checking on first launch or any subsequent launch where a required permission is missing or a model set requires explicit download or repair. The setup screen SHALL gate the dashboard while a user-resolvable prerequisite remains incomplete, and setup completion SHALL return automatically to passive loading for core preparation.

#### Scenario: First launch begins with loading
- **WHEN** the app is launched for the first time after install
- **THEN** the passive loading surface is shown while setup prerequisites are checked
- **AND** setup is shown only after missing permission or model actions are known

#### Scenario: All setup steps complete resumes loading
- **WHEN** all setup steps are completed with permissions granted and model assets fully verified
- **THEN** the system automatically returns to loading for core initialization
- **AND** no "Enter Subspace" action is required

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

### Requirement: Setup screen shows step status

The setup screen SHALL display each step with a clear status indicator (pending, in progress, completed, failed).

#### Scenario: Step shows pending initially
- **WHEN** the setup screen appears and a step has not been started
- **THEN** the step shows a pending/neutral icon and its action button

#### Scenario: Completed step shows check mark
- **WHEN** a step has been successfully completed
- **THEN** the step shows a green check mark and its action button is hidden or disabled

#### Scenario: In-progress step shows progress
- **WHEN** a step is actively being executed (e.g., downloading models)
- **THEN** the step shows a progress indicator (spinner for permissions, progress bar with percentage for downloads)

#### Scenario: Failed step shows error
- **WHEN** a step has failed (e.g., download error, permission denied)
- **THEN** the step shows an error icon and a retry button

### Requirement: Permissions step requests all runtime permissions

The system SHALL request all required runtime permissions in a single batch when the user taps the permissions action button.

#### Scenario: Grant all permissions
- **WHEN** the user taps "Grant permissions" on the setup screen
- **THEN** the system launches Android's runtime permission dialog for BLUETOOTH_CONNECT, BLUETOOTH_SCAN, RECORD_AUDIO, and POST_NOTIFICATIONS simultaneously

#### Scenario: All permissions granted
- **WHEN** the user grants all requested permissions
- **THEN** the permissions step shows a green check mark and the storage-access step becomes actionable

#### Scenario: Some permissions denied
- **WHEN** the user denies one or more requested permissions
- **THEN** the permissions step shows which permissions are still missing and the "Grant permissions" button remains available

### Requirement: Storage access uses Android's dedicated settings flow

The system SHALL require all-files access before model acquisition because Journal persists to
user-selected real filesystem paths for external synchronization.

#### Scenario: Request all-files access
- **WHEN** runtime permissions are granted and the user taps "Allow storage access"
- **THEN** the system opens Android's app-specific all-files access settings page

#### Scenario: Return with storage access granted
- **WHEN** the user grants all-files access and returns to Subspace
- **THEN** bootstrap rechecks its prerequisites automatically
- **AND** the storage-access step shows a green check mark
- **AND** the model download step becomes actionable

#### Scenario: Storage access remains denied
- **WHEN** all-files access is missing
- **THEN** bootstrap remains on the setup screen
- **AND** model acquisition and core initialization do not start

### Requirement: ConnectionScreen no longer shows permissions

The RSM device setup page (ConnectionScreen) SHALL NOT include any permission-requesting UI elements.

#### Scenario: ConnectionScreen without permissions
- **WHEN** the user navigates to the RSM setup page
- **THEN** the page does not show a "Grant permissions" button, a permissions status row, or permissions guidance text

#### Scenario: ConnectionScreen still shows other readiness checks
- **WHEN** the user navigates to the RSM setup page
- **THEN** Bluetooth enablement, device presence, SPP state, and headset audio state are still displayed and actionable