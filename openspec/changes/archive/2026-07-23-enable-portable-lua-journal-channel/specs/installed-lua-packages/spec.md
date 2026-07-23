## ADDED Requirements

### Requirement: Revised v1 clean cutover uses exact immutable package revisions
After the host adopts the revised unreleased manifest v1, every production/external development package used by acceptance SHALL be republished as an immutable release asset containing explicit `resources`, including an empty mount list where applicable. Historical pre-cutover assets SHALL remain historical and incompatible; the host SHALL not rewrite their bytes, infer a declaration, or activate them through a legacy parser. Installation/update SHALL continue validating exact downloaded asset bytes before atomic store publication.

#### Scenario: Historical package lacks resources
- **WHEN** an installed or downloaded historical development asset uses the superseded manifest shape
- **THEN** validation SHALL reject it as incompatible with revised v1
- **AND** the store SHALL retain any previously active valid revised revision unchanged

#### Scenario: Revised package is published
- **WHEN** an update asset declares explicit compatible resources and passes exact validation
- **THEN** the transaction SHALL atomically publish it under the same repository-derived provider identity
- **AND** runtime reconciliation SHALL replace affected generations without changing instance identity

### Requirement: Official package acceptance pins exact revised releases
End-to-end dependency acceptance for this change SHALL resolve and install exact immutable assets for:
- Debug: `nilp0inter/debug-channel`, owner database ID `1224006`, `v1.2.0`, asset `subspace-channel.zip`.
- Diagnostics: `nilp0inter/diagnostics-channel`, owner database ID `1224006`, `v1.3.0`, asset `subspace-channel.zip`.
- Journal: `nilp0inter/journal-channel`, owner database ID `1224006`, `v1.0.0`, asset `subspace-channel.zip`.

Each release SHALL be stable/non-draft/non-prerelease, contain exactly one canonical asset of that name, and contain a positive immutable repository database ID matching manifest `repositoryId`. Release immutability in this contract is the project policy that published tags and assets are never replaced; GitHub's optional immutable-releases repository feature is not required. Fixtures, hashes, release metadata, and installation tests SHALL be updated together only from inspected exact assets. Runtime tests SHALL not make live network calls.

#### Scenario: Revised official packages are resolved
- **WHEN** acceptance inspects the three pinned releases
- **THEN** each manifest identity, release version, asset name, digest, and source-only archive SHALL match recorded fixtures
- **AND** each package SHALL install through the normal validator/store transaction

#### Scenario: Exact release is missing or mutable metadata differs
- **WHEN** a pinned release/asset is absent, duplicated, draft/prerelease, owned by another account, or differs from recorded identity/digest
- **THEN** acceptance SHALL fail rather than substituting latest release or another asset

### Requirement: Package updates preserve compatible mount bindings by declaration identity
When an installed provider changes revision, the store/reconciliation path SHALL preserve a channel instance's mount binding only if the new declaration has the same mount ID, kind, and requested access and remains authorized. Removed or incompatible declarations SHALL not leak or silently retarget grants. Failed package update or rollback SHALL leave the prior active revision and bindings intact; successful replacement SHALL create fresh generation-owned mount handles.

#### Scenario: Debug update retains no mounts
- **WHEN** Debug updates between revised revisions that both declare an explicit empty mount list
- **THEN** no mount binding or picker state SHALL be created

#### Scenario: Journal update keeps compatible output declaration
- **WHEN** Journal updates to a revision declaring the same required read-write directory-tree `output` mount
- **THEN** the instance MAY retain its generic persisted binding after revalidation
- **AND** successor Lua SHALL receive a newly created opaque handle, not predecessor userdata

#### Scenario: Journal update changes mount kind or access
- **WHEN** a revision changes the declaration incompatibly
- **THEN** the host SHALL preserve the opaque platform grant for explicit repair/reselection policy but SHALL not bind it automatically
- **AND** the instance SHALL become typed-unavailable until resolved
