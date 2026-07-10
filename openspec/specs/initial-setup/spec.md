# Initial Setup

## Purpose

Define the first-launch setup flow that gates access to the main application until required permissions and runtime models are ready.

## Requirements

### Requirement: Initial setup screen gates main app

The system SHALL present a setup screen on first launch and on any subsequent launch where permissions are missing or model hashes fail.

#### Scenario: First launch shows setup
- **WHEN** the app is launched for the first time after install
- **THEN** the setup screen is shown instead of the main dashboard

#### Scenario: All steps complete enters dashboard
- **WHEN** all setup steps are completed (permissions granted AND models downloaded + verified)
- **THEN** the user taps "Enter Subspace" and the main dashboard is shown

#### Scenario: Permission revoked on later launch
- **WHEN** the app is launched and any required runtime permission has been revoked since last launch
- **THEN** the setup screen is shown with the permissions step marked as incomplete

#### Scenario: Model corrupted on later launch
- **WHEN** the app is launched and any model file's SHA-256 does not match the bundled hash manifest
- **THEN** the setup screen is shown with the model step marked as incomplete, triggering re-download

#### Scenario: All checks pass on launch
- **WHEN** the app is launched and all permissions are granted AND all model files pass SHA-256 verification
- **THEN** the main dashboard is shown directly, skipping the setup screen

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
- **THEN** the permissions step shows a green check mark and the model download step becomes actionable

#### Scenario: Some permissions denied
- **WHEN** the user denies one or more requested permissions
- **THEN** the permissions step shows which permissions are still missing and the "Grant permissions" button remains available

### Requirement: "Enter Subspace" button gated on completion

The setup screen SHALL have an "Enter Subspace" button that is disabled until both the permissions step AND the model step are complete.

#### Scenario: Button disabled when incomplete
- **WHEN** the setup screen is shown and either permissions or models step is incomplete
- **THEN** the "Enter Subspace" button is disabled with a visual indicator

#### Scenario: Button enabled when complete
- **WHEN** both permissions and models steps are marked completed
- **THEN** the "Enter Subspace" button is enabled

#### Scenario: Button navigates to dashboard
- **WHEN** the user taps "Enter Subspace"
- **THEN** the main dashboard is shown and the setup screen is not shown again until a step becomes incomplete on a future launch

### Requirement: ConnectionScreen no longer shows permissions

The RSM device setup page (ConnectionScreen) SHALL NOT include any permission-requesting UI elements.

#### Scenario: ConnectionScreen without permissions
- **WHEN** the user navigates to the RSM setup page
- **THEN** the page does not show a "Grant permissions" button, a permissions status row, or permissions guidance text

#### Scenario: ConnectionScreen still shows other readiness checks
- **WHEN** the user navigates to the RSM setup page
- **THEN** Bluetooth enablement, device presence, SPP state, and headset audio state are still displayed and actionable
