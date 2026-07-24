## Purpose

Defines bounded exact-key configuration data schemas, UI rendering declarations, static validation, lossless persistence, atomic generation replacement, detached startup snapshots, and update compatibility for Lua channel packages.

## Requirements

### Requirement: Package manifests declare bounded exact-key configuration data schemas
`configuration` SHALL be an exact-key object containing only `schemaVersion`, `data`, and `ui`, with `schemaVersion` exactly integer `1`. `data` SHALL contain only `additionalProperties` and `fields`, with `additionalProperties` exactly `false` and `fields` a flat array of required scalar declarations. Every field SHALL contain unique canonical `id`, `type`, and exactly typed `default`. A `string` field MAY additionally contain only nonempty unique string `allowedValues`; a `boolean` field SHALL contain no type-specific keys; an `integer` field MAY additionally contain only inclusive signed-64-bit `minimum` and `maximum`. Defaults SHALL satisfy allowed values and ranges. Empty fields SHALL be valid and compile to `{}`. The validator SHALL reject unknown keys at every configuration, data, and field-declaration level, unsupported versions or types, duplicate IDs or values, invalid identifiers, nulls, nested values, invalid defaults or ranges, and type-specific keys on the wrong field type.

#### Scenario: Valid configuration data declaration
- **WHEN** a manifest contains the exact v1 data keys and valid string, boolean, or integer field declarations
- **THEN** static validation SHALL accept the declaration
- **AND** the materialized provider SHALL construct the complete specified default payload

#### Scenario: Empty configuration data declaration
- **WHEN** `data.fields` is an empty array and `additionalProperties` is `false`
- **THEN** validation SHALL succeed and the provider default SHALL be `{}`

#### Scenario: Duplicate, invalid, or mismatched field declaration
- **WHEN** fields duplicate an ID, use an ID outside `[a-z][a-z0-9_]*`, declare a mismatched default, invalid range, duplicate allowed value, or type-specific key on the wrong scalar type
- **THEN** validation SHALL reject the complete package before installation or provider publication

#### Scenario: Unknown nested data key
- **WHEN** `configuration`, `data`, or a data field declaration contains a key outside its exact v1 key set
- **THEN** validation SHALL reject the complete package
- **AND** it SHALL NOT ignore, preserve as an extension, or infer semantics for that key

### Requirement: Package manifests declare bounded exact-key UI configuration rendering
`ui` SHALL be an exact-key object containing only a `fields` array. Every data field ID SHALL appear exactly once in that array, whose order SHALL define render order. Each UI entry SHALL contain canonical `field`, type-compatible `control`, nonblank bounded `label`, and optional bounded `help`. Controls SHALL be exactly `text` for an unconstrained string, `toggle` for boolean, `number` for integer, `choice` for a string with `allowedValues`, or `dynamic-choice` for an unconstrained string whose choices are resolved from a supported host source. A `choice` entry SHALL additionally contain only required `choices`. A `dynamic-choice` entry SHALL additionally contain required `source` and MAY contain `dependsOn`; when present, `dependsOn` SHALL name an earlier unconstrained string field. No control SHALL contain members belonging to another control.

#### Scenario: Valid static UI declaration matches data schema
- **WHEN** every data field has exactly one type-compatible static UI entry and every static choice value set exactly matches its `allowedValues`
- **THEN** validation SHALL accept the declaration
- **AND** the host editor SHALL render controls in declared order

#### Scenario: Valid independent dynamic choice targets unconstrained string
- **WHEN** an unconstrained string field has one `dynamic-choice` UI entry naming a supported source without `dependsOn`
- **THEN** static validation SHALL accept the declaration without resolving the host source or executing Lua
- **AND** the materialized editor field SHALL retain the source identifier for host-time resolution

#### Scenario: Valid dependent dynamic choice names an earlier scalar
- **WHEN** a dynamic choice names an earlier unconstrained string field through `dependsOn`
- **THEN** validation SHALL retain that dependency in materialized generic field metadata
- **AND** host resolution SHALL receive only the dependency's persisted scalar value

#### Scenario: UI field coverage is not exact
- **WHEN** a data field is omitted, referenced more than once, or an unknown field is referenced
- **THEN** validation SHALL reject the package before provider construction

#### Scenario: Static choice declaration mismatches allowed values
- **WHEN** a static choice targets a field without `allowedValues`, omits an allowed value, adds an extra value, duplicates a value, or duplicates a display label
- **THEN** validation SHALL reject the package before activation

#### Scenario: Dynamic choice declaration is incompatible
- **WHEN** a dynamic choice targets a non-string or statically constrained field, names an unknown source, contains `choices`, omits `source`, names itself or a later or incompatible field through `dependsOn`, or combines static and dynamic choice members
- **THEN** validation SHALL reject the complete package before storage, materialization, source loading, or Lua state creation

#### Scenario: Unknown nested UI key
- **WHEN** `ui`, a UI field entry, or a choice item contains a key outside its exact v1 key set for that control
- **THEN** validation SHALL reject the complete package without ignoring that key

#### Scenario: Empty UI matches empty data
- **WHEN** both `data.fields` and `ui.fields` are empty arrays
- **THEN** validation SHALL accept the configuration and render an empty edit card

### Requirement: Host enforces static fail-closed validation bounds on configuration
The host SHALL enforce static, finite bounds on configuration schemas and candidate configuration payloads. A schema declaration SHALL NOT declare more than 32 fields. Field IDs SHALL NOT exceed 64 UTF-8 bytes. UI labels SHALL NOT exceed 128 UTF-8 bytes. UI help strings SHALL NOT exceed 512 UTF-8 bytes. A `choice` control SHALL NOT define more than 64 values. The canonical serialized configuration object size SHALL NOT exceed 64 KiB, and any individual string configuration value SHALL NOT exceed 16 KiB. Configuration schema and payload validation SHALL be static and SHALL NOT execute Lua, create a Lua state, call a plugin callback, acquire a channel capability, or perform network, hardware, or Android access.

#### Scenario: Configuration field count exceeds host limit
- **WHEN** a manifest configuration schema declares 33 fields
- **THEN** static validation SHALL reject the package with a typed bounds error
- **AND** the package SHALL NOT be installed or registered

#### Scenario: Validation is static and executes no Lua
- **WHEN** the host validates a configuration schema or a proposed configuration payload edit
- **THEN** validation SHALL complete using only static host logic
- **AND** no Lua engine execution, module loading, or script state allocation SHALL occur

### Requirement: Channel configuration is persisted losslessly and isolated per instance
The host SHALL persist configuration payloads losslessly and validate them before committing to the catalogue or loading into a runtime generation. Persisted configuration payloads SHALL contain every declared field and no undeclared fields. No type coercion SHALL occur between strings, booleans, integers, floats, or null. Sibling instances of the same provider SHALL maintain completely isolated configuration payloads, actor runtimes, Lua states, timers, and logs. Mutating or editing the configuration of one instance SHALL NOT affect or leak into any sibling instance.

#### Scenario: Valid payload is persisted and validated
- **WHEN** a user commits a configuration payload matching all schema types and constraints
- **THEN** the host SHALL persist the payload losslessly and allow runtime reconstruction

#### Scenario: Payload contains undeclared field
- **WHEN** a persisted or proposed payload contains a key not defined in the configuration schema
- **THEN** validation SHALL reject the payload with a typed configuration error
- **AND** the payload SHALL NOT be committed or used to build a generation

#### Scenario: Payload type coercion is rejected
- **WHEN** a payload contains a string value for a field declared as an `integer`
- **THEN** the validator SHALL reject the payload and SHALL NOT attempt to coerce the string to an integer

#### Scenario: Sibling configurations are isolated
- **WHEN** two separate channel instances of the same provider are configured with different values
- **THEN** each instance SHALL retain its own configuration payload and execute in its own isolated runtime generation
- **AND** there SHALL be no configuration leakage or shared state between them

### Requirement: Configuration edits trigger atomic generation replacement
Editing or updating the configuration of an active channel instance SHALL trigger atomic runtime generation replacement. The host SHALL immediately stop admitting new inputs to the predecessor generation, wait for any committed terminal callback to finish, cancel or drain all active predecessor tasks and timers, revoke all predecessor capability leases, close the predecessor, and construct a fresh successor generation with the new configuration payload. The predecessor generation SHALL NOT be mutated in-place, and no predecessor task or timer SHALL execute after replacement.

#### Scenario: Configuration edit replaces generation atomically
- **WHEN** the user commits an edited configuration payload for a selected, running channel instance
- **THEN** the host SHALL atomically close the predecessor generation, cancel its pending tasks, and launch a successor generation with the new configuration
- **AND** no predecessor callback or event handler SHALL execute post-closure

### Requirement: Lua runtime receives a detached configuration snapshot at startup
The host SHALL pass a detached, normalized Lua table representing the validated configuration snapshot to the Lua `startup(configuration)` callback during generation initialization. The table SHALL contain a `schema_version` field set to `1` and a `values` table mapping declared field IDs to their validated scalar values. The snapshot table SHALL NOT contain any metatable, Kotlin references, host-owned objects, or mutable platform state. Lua code MAY mutate its copy of the snapshot, but this mutation SHALL NOT affect the host's persisted configuration, sibling instances, or future generations. An empty configuration schema SHALL compile to a snapshot with `schema_version = 1` and an empty `values` table.

#### Scenario: Startup callback receives valid snapshot
- **WHEN** a Lua generation initializes and the `startup` callback is executed
- **THEN** it SHALL receive a detached configuration snapshot matching the instance's validated configuration values
- **AND** the callback SHALL be able to access the values dynamically

#### Scenario: Lua mutates configuration snapshot without side effects
- **WHEN** Lua code in the `startup` callback mutates the `configuration.values` table
- **THEN** the mutation SHALL succeed inside the Lua environment
- **AND** the host's persisted configuration and future generations SHALL remain unaffected

#### Scenario: Diagnostics receives empty snapshot
- **WHEN** a Diagnostics Channel generation starts up
- **THEN** its `startup` callback SHALL receive a snapshot table where `values` is empty
- **AND** no missing-field errors SHALL be thrown by the host

### Requirement: Package updates enforce compatibility and preserve invalid payloads
When an installed package is updated, the host SHALL validate the existing persisted configuration payload of all instances of that provider against the updated package's configuration schema. If an existing payload violates the updated schema and cannot be validated, the affected channel instance SHALL be marked explicitly unavailable with a typed configuration-incompatibility reason. The host SHALL preserve the exact persisted payload unchanged. The user SHALL be able to manually roll back to the previously working package revision to restore availability, or reconfigure the instance to satisfy the updated schema.

#### Scenario: Update makes instance unavailable due to configuration incompatibility
- **WHEN** a package is updated to a revision with a schema that rejects the existing configuration payload
- **THEN** the host SHALL preserve the persisted payload bytes
- **AND** it SHALL expose the instance as unavailable with a typed configuration-incompatibility state
- **AND** it SHALL NOT construct a successor generation using default values or coerced types

#### Scenario: Incompatible instance is rolled back to working revision
- **WHEN** the user rolls back the package to the previous revision after an incompatible update
- **THEN** the host SHALL re-validate the preserved configuration payload against the restored schema
- **AND** the channel instance SHALL become available and successfully construct its successor generation

### Requirement: Dynamic scalar references are host resolved and runtime visible
The host SHALL resolve a dynamic-choice source through a bounded host-owned source registry when rendering configuration and refreshing runtime readiness. A dependent source SHALL receive only the current persisted scalar of its declared dependency. The persisted configuration payload and detached startup snapshot SHALL contain only scalar IDs, never a resolver, repository, SDK client, profile object, keymap, transport, credential, or UI state. For every required dynamic field, readiness context SHALL include an exact reference state derived from the current selected scalar, dependency, and source result. Editor resolution SHALL NOT be treated as permanent authorization, and every effect using the selected scalar SHALL revalidate it at call time.

#### Scenario: Editor resolves the keyboard profile hierarchy
- **WHEN** the editor resolves `keyboard-output-platforms`, then `keyboard-output-layouts` with a platform dependency, then `keyboard-output-profiles` with a layout dependency
- **THEN** every stage SHALL publish a bounded set of stable scalar IDs and display labels
- **AND** the final selected scalar SHALL be the exact logical profile ID used by keyboard output
- **AND** the provider and Lua package SHALL receive no host profile or keymap object

#### Scenario: Selected reference remains available
- **WHEN** readiness refresh resolves the persisted selected scalar using its declared source and current dependency
- **THEN** the readiness context SHALL report that configuration reference as `available`
- **AND** this cached state SHALL NOT bypass call-time profile validation

#### Scenario: User explicitly changes a dynamic dependency
- **WHEN** the user selects a different value for a field referenced by one or more dependent dynamic choices
- **THEN** the editor SHALL clear every transitive dependent value from its working state before resolving choices for the new dependency
- **AND** unrelated fields and the persisted payload SHALL remain unchanged until submission

#### Scenario: Selected dependency or reference disappears
- **WHEN** a persisted dependency is missing or stale, the selected scalar is absent, or a required source cannot resolve
- **THEN** readiness context SHALL report the affected reference and its dependents as `unavailable`
- **AND** the host SHALL preserve every scalar payload unchanged for user repair or later source recovery

#### Scenario: Dynamic source lookup exceeds bounds or fails
- **WHEN** host source resolution fails, exceeds its deadline, or returns duplicate, blank, invalid, or over-bound IDs or labels
- **THEN** the editor/readiness resolver SHALL return a typed unavailable state
- **AND** it SHALL NOT partially publish choices, mutate configuration, or execute Lua
