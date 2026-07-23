## MODIFIED Requirements

### Requirement: Package manifest declares one bounded provider revision
`manifest.json` SHALL be a UTF-8 JSON object with `manifestVersion` exactly `1`, a positive decimal-string `repositoryId`, a nonblank bounded `packageVersion`, a canonical `entryModule`, bounded nonblank `presentation.label` and `presentation.summary`, exact `runtime.luaVersion` and `runtime.apiVersion` strings, a `configuration` object, a `resources` object, and a `capabilities` array. Every field SHALL have the declared JSON type. `resources` SHALL use the exact mount-declaration contract defined by `lua-resource-mounts`. V1 validation SHALL reject missing fields, duplicate JSON keys, unknown fields, blank values, invalid repository IDs, invalid entry-module names, invalid or duplicate capability identifiers, invalid resource declarations, and values exceeding finite host bounds. Because this v1 contract is unreleased and evolves by clean cutover, the host SHALL reject the superseded manifest-v1 shape that omits `resources`; it SHALL NOT infer an empty declaration, dispatch a legacy parser, negotiate features, or change the exact `subspace-lua-v1` API string.

#### Scenario: Manifest declares all required v1 fields
- **WHEN** a manifest contains exactly the revised required v1 fields, including configuration, resources, and capabilities, with valid types and bounded values
- **THEN** the host SHALL retain package version, entry module, presentation, runtime requirements, configuration schema, resource declarations, and capability eligibility in the validated package revision

#### Scenario: Manifest contains an unknown field
- **WHEN** a v1 manifest contains a field not defined by the revised package-format v1
- **THEN** the host SHALL reject the complete package with a typed manifest result
- **AND** it SHALL NOT silently ignore the field or reinterpret it as configuration, resource, capability, provenance, or compatibility metadata

#### Scenario: Manifest version is unsupported
- **WHEN** `manifestVersion` is absent or not exactly `1`
- **THEN** the host SHALL return a typed unsupported-format result
- **AND** it SHALL NOT attempt to parse the manifest as v1 or execute its source

#### Scenario: Manifest declares empty configuration, resources, and capabilities
- **WHEN** a manifest declares empty configuration data/UI fields, an empty resources mount list, and an empty capabilities list such as revised Diagnostics
- **THEN** the host SHALL accept all three explicit declarations
- **AND** it SHALL record the revision with no capabilities, mounts, or scalar fields

#### Scenario: Manifest omits resources
- **WHEN** an otherwise valid historical development artifact omits the required `resources` member
- **THEN** the revised host SHALL reject the complete package before installation, registration, or execution
- **AND** it SHALL NOT treat the omission as an empty mount declaration or change API versions

#### Scenario: Manifest declares invalid capability identifier
- **WHEN** a manifest contains an identifier outside the revised stable allowlist, whose permitted values are exactly `audio.transcription`, `audio.synthesis`, `audio.playback`, `audio.files`, and `storage.files`
- **THEN** the host SHALL reject the complete package with a typed capability-validation error
- **AND** it SHALL NOT register the provider or authorize execution

#### Scenario: Manifest contains duplicate capability identifiers
- **WHEN** a manifest contains duplicate capability identifiers in the `capabilities` array
- **THEN** the host SHALL reject the complete package with a typed capability-validation error
