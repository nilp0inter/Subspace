## ADDED Requirements

### Requirement: Model acquisition has one process owner
The system SHALL route all model inspection, acquisition, repair, and progress observation through one process-scoped model asset repository. The repository SHALL permit at most one acquisition writer for a model set and SHALL make concurrent callers join the same in-flight result and progress stream.

#### Scenario: Activity and service request the same model set
- **WHEN** the activity-facing bootstrap flow and service initialization request the same model set concurrently
- **THEN** exactly one acquisition operation writes that model set
- **AND** both callers observe the same terminal result

#### Scenario: Request arrives during active acquisition
- **WHEN** a second request for a model set arrives while acquisition is active
- **THEN** the second request joins the active operation
- **AND** it does not truncate, append to, hash, or mark the target independently

#### Scenario: Progress has multiple observers
- **WHEN** multiple bootstrap consumers observe an active acquisition
- **THEN** each consumer receives progress from the single active operation

### Requirement: Acquisition completion requires final full verification
The system SHALL report model acquisition complete only after every required model set passes a fresh full manifest, version, file-presence, nonzero-length, and SHA-256 verification. A successful download call or matching version marker alone SHALL NOT produce a complete result.

#### Scenario: Marker matches but file hash is invalid
- **WHEN** a model set's version marker matches the bundled manifest but any required file hash does not match
- **THEN** the set is considered invalid
- **AND** acquisition repairs or replaces the invalid content
- **AND** completion is withheld until full verification passes

#### Scenario: Download returns without valid assets
- **WHEN** the transfer operation returns but final full verification fails
- **THEN** acquisition reports failure with the verification diagnostic
- **AND** bootstrap does not proceed to native initialization

#### Scenario: All model sets verify after repair
- **WHEN** repaired or downloaded model files and version markers all pass a fresh full verification
- **THEN** the repository reports aggregate model readiness exactly once for that operation

## MODIFIED Requirements

### Requirement: Re-download on version change
The system SHALL acquire or repair a model set when its directory or version marker is absent, when the bundled manifest version differs from the on-disk marker, or when any required file fails presence, nonzero-length, or SHA-256 verification. A completion marker SHALL be written only after every required file in the set has been acquired and verified.

#### Scenario: Version mismatch triggers re-download
- **WHEN** the app checks a model set and its `.subspace_assets_version` file contains a different version than the bundled manifest
- **THEN** the model set is considered invalid
- **AND** the single acquisition owner re-downloads or repairs the required files

#### Scenario: Version match verifies hashes
- **WHEN** the `.subspace_assets_version` matches the bundled manifest version
- **THEN** each required model file's presence, nonzero length, and SHA-256 are verified
- **AND** any mismatch marks the model set invalid and requires repair

#### Scenario: Missing marker requires acquisition
- **WHEN** a model directory or completion marker is missing
- **THEN** the model set is considered invalid even if partial files are present
- **AND** resumable acquisition may reuse valid partial bytes according to the existing Range behavior

#### Scenario: Completion marker is committed last
- **WHEN** every required file in a model set has been downloaded and verified
- **THEN** the repository writes the manifest version to the completion marker
- **AND** no matching completion marker is published before all files verify
