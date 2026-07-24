## MODIFIED Requirements

### Requirement: Package manifest declares one bounded provider revision
`manifest.json` SHALL be a UTF-8 JSON object with exactly `manifestVersion`, `repositoryId`, `packageVersion`, `entryModule`, `presentation`, `runtime`, `configuration`, `resources`, `profileTypes`, `choiceResolvers`, `workQueues`, and `capabilities`. `manifestVersion` SHALL be integer `1`; `repositoryId` a positive decimal string; `packageVersion` nonblank and bounded; `entryModule` canonical; presentation labels bounded; and runtime Lua/API versions exact. Configuration, resources, profile types, choice resolvers, work queues, and capabilities SHALL use their referenced exact contracts. Every field SHALL have the declared JSON type. V1 validation SHALL reject missing fields, duplicate keys, unknown fields, blank/invalid identity, invalid module references, duplicates across each declaration namespace, undeclared resolver/work capabilities, and values exceeding finite bounds. Because v1 is unreleased and evolves by clean cutover, the host SHALL reject superseded shapes omitting any newly required array/object; it SHALL not infer empty declarations, dispatch a legacy parser, negotiate features, or change `subspace-lua-v1`.

#### Scenario: Manifest declares all required v1 fields
- **WHEN** a manifest contains exactly the revised fields with valid configuration, resources, profile types, choice resolvers, work queues, and capabilities
- **THEN** the host SHALL retain every validated declaration in one immutable package revision
- **AND** no declaration SHALL execute Lua or acquire authority during validation

#### Scenario: Manifest contains an unknown field
- **WHEN** a v1 manifest contains a field outside the exact revised key set
- **THEN** the host SHALL reject the complete package
- **AND** it SHALL not preserve or reinterpret the field

#### Scenario: Manifest version is unsupported
- **WHEN** `manifestVersion` is absent or not exactly `1`
- **THEN** the host SHALL return a typed unsupported-format result
- **AND** it SHALL not execute or reinterpret the package

#### Scenario: Manifest declares empty optional facilities
- **WHEN** a package needs no configuration, resources, profiles, resolvers, queues, or capabilities and declares each required object/array explicitly empty
- **THEN** validation SHALL accept those exact empty declarations
- **AND** materialization SHALL grant none of those facilities

#### Scenario: Historical package omits a new declaration
- **WHEN** an otherwise valid development artifact omits `profileTypes`, `choiceResolvers`, or `workQueues`
- **THEN** the revised host SHALL reject it before installation, publication, or execution
- **AND** official packages SHALL require republished exact artifacts rather than a compatibility default

#### Scenario: Manifest declares invalid capability identifier
- **WHEN** a manifest contains an identifier outside the stable allowlist, whose values are exactly `audio.transcription`, `audio.synthesis`, `audio.playback`, `audio.files`, `storage.files`, `keyboard.output`, `network.http`, `profiles.read`, `secrets.read`, and `work.queue`
- **THEN** the host SHALL reject the complete package with a typed capability-validation error
- **AND** it SHALL not register the provider or authorize execution

#### Scenario: Manifest contains duplicate capability identifiers
- **WHEN** a capability identifier occurs more than once
- **THEN** validation SHALL reject the complete package

## ADDED Requirements

### Requirement: New manifest declarations are statically cross-validated
The package validator SHALL validate profile-type schemas, choice-resolver IDs/modules/capability subsets, work-queue IDs, configuration source references, required module presence, and capability relationships as one bounded graph. Every package dynamic source SHALL reference a resolver or profile type declared by the same repository revision; every resolver capability SHALL be a permitted subset of package capabilities; `workQueues` SHALL be nonempty only with `work.queue`; and secret profile fields SHALL require a package capable of reading secrets before runtime use. Cross-package declaration references and unresolved aliases SHALL be rejected. Static cross-validation SHALL not create a Lua state, profile, secret, queue, HTTP request, or editor object.

#### Scenario: Resolver reference is missing
- **WHEN** configuration names a package resolver absent from `choiceResolvers`
- **THEN** static validation SHALL reject the complete package
- **AND** it SHALL not defer the error to editor runtime

#### Scenario: Queue lacks work capability
- **WHEN** `workQueues` contains an ID but `capabilities` omits `work.queue`
- **THEN** validation SHALL reject the complete package before storage

### Requirement: Existing official packages cut over to explicit empty declarations
Every official package published against the revised v1 contract SHALL include explicit `profileTypes`, `choiceResolvers`, and `workQueues`, even when empty. Stored predecessor artifacts that omit them SHALL remain exact immutable bytes and become unavailable under the revised validator until an explicit compatible update is installed. The host SHALL not rewrite their manifests or source maps in place.

#### Scenario: Existing official package is updated
- **WHEN** Debug, Diagnostics, Journal, or Keyboard publishes a compatible revision with explicit empty new arrays
- **THEN** ordinary installation/update SHALL validate and activate it under the same package path
- **AND** it SHALL acquire no new authority from empty declarations
