## MODIFIED Requirements

### Requirement: Package manifests declare bounded exact-key UI configuration rendering
`ui` SHALL be an exact-key object containing only `fields`. Every data field ID SHALL appear exactly once in render order. Each entry SHALL contain canonical `field`, type-compatible `control`, bounded nonblank `label`, and optional bounded `help`. Controls SHALL be exactly `text` or `multiline` for an unconstrained string, `toggle` for boolean, `number` for integer, `choice` for a string with `allowedValues`, or `dynamic-choice` for an unconstrained string resolved from a supported host source, a profile type declared by the same package, or a package choice resolver declared by the same package. A `choice` SHALL additionally contain only required `choices`. A `dynamic-choice` SHALL additionally contain required `source` and MAY contain `dependsOn`; when present, it SHALL name an earlier unconstrained string field. No control SHALL contain another control's members.

#### Scenario: Valid static UI matches data schema
- **WHEN** every data field has one type-compatible entry and every static choice exactly matches allowed values
- **THEN** validation SHALL accept the declaration and retain render order

#### Scenario: Multiline control targets string
- **WHEN** an unconstrained string field uses `multiline`
- **THEN** validation SHALL accept it and the editor SHALL preserve bounded line breaks

#### Scenario: Dynamic choice uses supported source
- **WHEN** an unconstrained string field names a supported host source, same-package profile type, or same-package resolver
- **THEN** static validation SHALL retain the exact source without resolving it or executing Lua

#### Scenario: Dependent dynamic choice names earlier scalar
- **WHEN** a dynamic choice names an earlier unconstrained string through `dependsOn`
- **THEN** materialized metadata SHALL retain that dependency
- **AND** resolution SHALL bind only that current draft/persisted scalar and its authorized referenced revision

#### Scenario: UI field coverage is not exact
- **WHEN** a field is omitted, duplicated, or unknown
- **THEN** validation SHALL reject the package

#### Scenario: Static choice mismatches allowed values
- **WHEN** a static choice adds, removes, duplicates, or mislabels allowed values
- **THEN** validation SHALL reject the package

#### Scenario: Dynamic choice declaration is incompatible
- **WHEN** a dynamic choice targets an incompatible field, names an unknown/foreign source, contains static choices, omits source, or names itself/later field as dependency
- **THEN** validation SHALL reject the complete package before source loading or state creation

#### Scenario: Unknown nested UI key
- **WHEN** an entry contains a key outside its control's exact set
- **THEN** validation SHALL reject the complete package

#### Scenario: Empty UI matches empty data
- **WHEN** data and UI field arrays are empty
- **THEN** validation SHALL accept and render an empty edit card

### Requirement: Dynamic scalar references are host resolved and runtime visible
The host SHALL resolve each dynamic source through either the bounded host-source registry, the same-package profile-type registry, or the bounded short-lived package resolver declared for that source. A dependent resolution SHALL bind the current draft or persisted scalar and, for a profile dependency, the exact authorized profile revision. Persisted configuration and startup snapshots SHALL contain only scalar IDs, never resolver functions/states, repository clients, profile objects, secrets, SDK/HTTP clients, keymaps, transports, or UI state. Readiness SHALL include exact current reference state. Editor results SHALL not become permanent authorization, and effects SHALL revalidate current profile/capability authority.

#### Scenario: Editor resolves keyboard hierarchy
- **WHEN** the editor resolves the existing platform, layout, and profile host sources
- **THEN** it SHALL publish bounded IDs/labels and persist only the final logical scalar
- **AND** no keymap or transport object SHALL enter configuration or Lua

#### Scenario: Editor resolves package profile and models
- **WHEN** the editor lists profiles of a same-package declared type and invokes a model resolver depending on the selected profile
- **THEN** it SHALL bind each result to the exact package/profile revision
- **AND** configuration SHALL persist only profile and model IDs

#### Scenario: Selected reference remains available
- **WHEN** readiness resolves a persisted scalar against a current successful source result
- **THEN** it SHALL report available
- **AND** cached/editor resolution SHALL not bypass call-time validation

#### Scenario: User changes dependency
- **WHEN** the user changes a field referenced by dependent choices
- **THEN** the editor SHALL clear all transitive dependent draft values before new resolution
- **AND** persisted values remain unchanged until submission

#### Scenario: Dependency or reference disappears
- **WHEN** a dependency is missing/stale, selected scalar absent, profile unavailable, or resolver fails
- **THEN** affected reference states SHALL become unavailable while preserving persisted IDs
- **AND** no fallback value SHALL be substituted

#### Scenario: Dynamic lookup fails or exceeds bounds
- **WHEN** a host source, profile source, or package resolver fails, times out, is cancelled, or returns invalid choices
- **THEN** the whole lookup SHALL return typed unavailable state without partial publication or configuration mutation
- **AND** package Lua SHALL execute only when the source explicitly names its declared resolver

## ADDED Requirements

### Requirement: Multiline configuration remains bounded and lossless
A `multiline` control SHALL edit the same bounded string data type as `text`; it SHALL add no rich text, file, markup, secret, or platform value. The editor, canonical payload, startup snapshot, update validation, and rollback SHALL preserve exact valid-UTF-8 line breaks without normalizing newline content or trimming. Existing per-string and payload bounds remain authoritative.

#### Scenario: Agent prompt round trips
- **WHEN** a user saves a multiline system prompt and the generation restarts
- **THEN** Lua SHALL receive the exact persisted text and line breaks
- **AND** sibling configuration remains unaffected
