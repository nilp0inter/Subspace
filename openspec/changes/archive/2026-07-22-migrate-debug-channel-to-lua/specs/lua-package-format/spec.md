## MODIFIED Requirements

### Requirement: Package manifest declares one bounded provider revision
`manifest.json` SHALL be a UTF-8 JSON object with `manifestVersion` exactly `1`, a positive decimal-string `repositoryId`, a nonblank bounded `packageVersion`, a canonical `entryModule`, bounded nonblank `presentation.label` and `presentation.summary`, exact `runtime.luaVersion` and `runtime.apiVersion` strings, a `configuration` object, and a `capabilities` array. Every field SHALL have the declared JSON type. V1 validation SHALL reject missing fields, duplicate JSON keys, unknown fields, blank values, invalid repository IDs, invalid entry-module names, invalid or duplicate capability identifiers, and values exceeding host-configured finite bounds. A future manifest shape SHALL use another manifest version; the v1 host SHALL NOT infer forward compatibility from ignored metadata.

#### Scenario: Manifest declares all required v1 fields
- **WHEN** a manifest contains exactly the required v1 fields (including configuration and capabilities) with valid types and bounded values
- **THEN** the host SHALL retain the declared package version, entry module, presentation, runtime requirements, configuration schema, and capability eligibility in the validated package revision

#### Scenario: Manifest contains an unknown field
- **WHEN** a v1 manifest contains a field not defined by package-format v1
- **THEN** the host SHALL reject the complete package with a typed manifest result
- **AND** it SHALL NOT silently ignore the field or reinterpret it as configuration, capability, provenance, or compatibility metadata

#### Scenario: Manifest version is unsupported
- **WHEN** `manifestVersion` is absent or not exactly `1`
- **THEN** the host SHALL return a typed unsupported-format result
- **AND** it SHALL NOT attempt to parse the manifest as v1 or execute its source

#### Scenario: Manifest declares empty configuration and capabilities
- **WHEN** a manifest declares empty configuration data/UI fields and an empty capabilities list (e.g., Diagnostics)
- **THEN** the host SHALL accept the manifest configuration and capabilities
- **AND** it SHALL record the revision with no capabilities and an empty configuration schema

#### Scenario: Manifest declares invalid capability identifier
- **WHEN** a manifest contains a capability identifier not present in the allowlist of stable semantic capability names (`audio.transcription`, `audio.synthesis`, `audio.playback`)
- **THEN** the host SHALL reject the complete package with a typed capability-validation error
- **AND** it SHALL NOT register the provider or authorize execution

#### Scenario: Manifest contains duplicate capability identifiers
- **WHEN** a manifest contains duplicate capability identifiers in the `capabilities` array
- **THEN** the host SHALL reject the complete package with a typed capability-validation error

### Requirement: Package validation is bounded, static, and fail closed
Before an artifact can enter the immutable content store or installed-provider index, the host SHALL enforce finite host-configured bounds on exact compressed artifact bytes, archive-entry count, manifest bytes, path bytes, per-module uncompressed source bytes, total uncompressed source bytes, and compression expansion. It SHALL decode manifest and source as strict UTF-8, reject Lua binary-chunk signatures, statically validate configuration and capability schemas and defaults, and pass the derived entry module, source map, and exact Lua/API requirements through the existing `ImmutableProgramImage` validation. Package validation SHALL NOT execute Lua, create a Lua state or actor, call a plugin callback, acquire a channel capability, mutate the provider registry, mutate the channel catalogue, or perform network access.

#### Scenario: Package exceeds an archive bound
- **WHEN** compressed bytes, entry count, one expanded source, total expanded source, path length, or expansion ratio exceeds its configured bound
- **THEN** validation SHALL terminate with a typed bounds result
- **AND** it SHALL NOT retain a partial source map, active content, or growing overflow buffer

#### Scenario: Source is invalid UTF-8 or bytecode
- **WHEN** a Lua entry contains invalid UTF-8 or begins with a Lua binary-chunk signature
- **THEN** the host SHALL reject the complete artifact before state creation
- **AND** it SHALL NOT coerce text, load bytecode, or continue with other modules

#### Scenario: Validation receives adversarial but syntactically valid Lua
- **WHEN** package source contains code that would loop, allocate, log, spawn, sleep, or request another host effect if executed
- **THEN** static package validation SHALL complete without executing that source
- **AND** no Lua state, actor, timer, log, callback, or host effect SHALL be observed

#### Scenario: Static configuration validation executes no Lua
- **WHEN** the host validator checks manifest configuration schemas, UI settings, and defaults during package installation
- **THEN** validation SHALL complete using only static host logic
- **AND** no Lua engine execution, module loading, or script state allocation SHALL occur

#### Scenario: Invalid default configuration value fails validation
- **WHEN** a manifest configuration schema declares a default value that does not match the field type or violates data bounds
- **THEN** validation SHALL fail-closed and reject the package revision
