## ADDED Requirements

### Requirement: Launch starts with passive bootstrap loading
The system SHALL render a passive loading surface from cold activity creation until prerequisite checking determines the next valid root state. The system SHALL NOT represent unknown permission, model, service, or core-initialization state as an actionable setup state.

#### Scenario: Returning user cold launch
- **WHEN** the app is cold-launched with all setup prerequisites already satisfied
- **THEN** the loading surface is shown while prerequisites and core readiness are checked
- **AND** the setup screen is never shown during that successful launch

#### Scenario: Service binding is pending
- **WHEN** the activity is visible but the bootstrap-owning service is not yet bound
- **THEN** the loading surface shows a passive service-starting stage
- **AND** the dashboard is not rendered from a default placeholder application state

#### Scenario: Missing prerequisite discovered
- **WHEN** prerequisite checking identifies a missing permission or invalid model set with a known user remedy
- **THEN** the system replaces loading with the actionable setup surface

### Requirement: Bootstrap state is authoritative
The system SHALL expose one lifecycle-aware bootstrap state as the source of truth for loading, setup, recovery, and dashboard eligibility. Activity recreation or rebinding SHALL reconstruct the root surface from that state rather than defaulting to setup or dashboard.

#### Scenario: Activity is recreated during checking
- **WHEN** the activity is recreated while prerequisite checking is in progress
- **THEN** the recreated activity shows loading for the current observed stage
- **AND** it does not show setup unless the coordinator has identified a missing user action

#### Scenario: Activity is recreated during core preparation
- **WHEN** the activity is recreated while model acquisition, native initialization, controller construction, or announcement rendering is in progress
- **THEN** the recreated activity shows the current loading stage and available real progress
- **AND** it does not start a duplicate bootstrap operation

### Requirement: Core readiness gates dashboard entry
The system SHALL enter the dashboard only after all required core readiness conditions have completed successfully: runtime permissions are granted, all required model assets pass full verification, native STT and TTS engines report `Ready`, required model-backed controllers are constructed, and every required system-announcement phrase is cached as non-empty SCO-ready audio.

#### Scenario: Disk assets valid but native engine loading
- **WHEN** model files pass integrity verification but either native speech engine has not reported `Ready`
- **THEN** the loading surface remains visible
- **AND** the dashboard is not shown

#### Scenario: Engines ready but controllers pending
- **WHEN** STT and TTS engines are ready but any required controller has not been successfully constructed
- **THEN** the loading surface remains visible
- **AND** the dashboard is not shown

#### Scenario: Announcements partially rendered
- **WHEN** fewer than all required system-announcement phrases are cached as non-empty SCO-ready audio
- **THEN** bootstrap is not considered ready
- **AND** the loading surface reports announcement-rendering progress

#### Scenario: Core readiness complete
- **WHEN** every core readiness condition completes successfully
- **THEN** the system automatically replaces loading with the dashboard
- **AND** it does not wait for an acknowledgement or for a loading animation to finish

### Requirement: Optional external readiness does not block bootstrap
The system SHALL NOT require RSM bonding, SPP connection, HFP availability, Keyboard BLE connection, Android Auto presence, Telecom routing, serial reconnection, or journal recovery to complete global bootstrap.

#### Scenario: RSM is unavailable at core readiness
- **WHEN** core readiness completes while the RSM is powered off, unpaired, disconnected, or missing an audio profile
- **THEN** the dashboard is shown
- **AND** the RSM tile reflects its existing unavailable or setup state

#### Scenario: Optional integrations are absent
- **WHEN** core readiness completes without Keyboard BLE or Android Auto connectivity
- **THEN** the dashboard is shown
- **AND** each optional capability continues to report its own readiness independently

#### Scenario: Journal recovery continues
- **WHEN** core readiness completes while journal recovery work remains in progress
- **THEN** the dashboard is shown
- **AND** journal recovery continues outside the global bootstrap gate

### Requirement: Loading reports observed work
The loading surface SHALL identify the current observed bootstrap stage and SHALL display measurable progress only when the underlying operation reports a real count or byte total. The system SHALL NOT manufacture a combined percentage, random log output, or fictional initialization activity.

#### Scenario: Model file download reports bytes
- **WHEN** a model file download reports bytes read and a positive total byte count
- **THEN** loading displays the current file or model set and corresponding measurable progress

#### Scenario: Announcement rendering reports phrase count
- **WHEN** system announcements are being rendered
- **THEN** loading displays the number of successfully rendered phrases and the vocabulary-derived total

#### Scenario: Stage has no measurable total
- **WHEN** a bootstrap stage reports no meaningful total
- **THEN** loading identifies the stage without presenting a fabricated percentage

### Requirement: Loading follows the Subspace visual identity
The loading surface SHALL use the existing Night Ops and Daylight Material color schemes, Chakra Petch and Inter typography, and a restrained animated Analog-to-Routed Wave motif representing voice becoming routed digital signal. Ordinary loading SHALL use the theme primary accent and SHALL reserve warning/error treatment for actual failures.

#### Scenario: Night Ops loading
- **WHEN** the app uses the dark theme
- **THEN** loading uses the Void/Hull surfaces, Subspace Cyan for active scanning/readiness, and existing night text colors
- **AND** it does not use Alert Amber as ordinary progress color

#### Scenario: Daylight loading
- **WHEN** the app uses the light theme
- **THEN** loading uses Hull White/Deck Plating surfaces, Command Gold for primary progress, and existing daylight text colors

#### Scenario: Reduced or disabled animation
- **WHEN** platform animation is reduced or disabled
- **THEN** loading remains understandable through a static routed-wave treatment, stage labels, and state indicators
- **AND** no bootstrap behavior depends on animation completion

#### Scenario: Loading animation content
- **WHEN** the loading surface is visible
- **THEN** it renders at most one restrained routed-signal animation plus event-driven status and stage indicators
- **AND** it does not render starfields, spacecraft, CRT flicker, fictional telemetry, or scrolling fake logs

### Requirement: Bootstrap failures terminate in recovery
The system SHALL convert prerequisite inspection errors, native engine failures, controller construction failures, announcement rendering failures, and finite-stage timeouts into an explicit recovery state containing the failed stage, a concrete diagnostic, and a retry action when retry is safe. It SHALL NOT leave the user on an infinite loading indicator or misclassify an autonomous failure as initial setup.

#### Scenario: Native model reports failure
- **WHEN** either native speech engine reports `Failed`
- **THEN** loading changes to recovery for that engine
- **AND** the dashboard is not shown
- **AND** a diagnostic and retry action are presented

#### Scenario: Finite stage times out
- **WHEN** a finite core-initialization stage exceeds its configured timeout
- **THEN** loading changes to recovery with the timed-out stage identified
- **AND** retry does not leave jobs or controllers from the previous attempt active

#### Scenario: Announcement phrase fails
- **WHEN** any required announcement phrase fails to produce non-empty SCO-ready audio
- **THEN** bootstrap changes to recovery with the failed phrase or stage identified
- **AND** a runtime beep fallback does not cause bootstrap to report success

#### Scenario: Retry succeeds
- **WHEN** the user retries a recoverable bootstrap failure and all core conditions subsequently complete
- **THEN** the system automatically enters the dashboard
