## MODIFIED Requirements

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

## ADDED Requirements

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
