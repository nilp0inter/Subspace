## MODIFIED Requirements

### Requirement: Lua provider implements the ChannelImplementationProvider contract
The Lua provider SHALL implement the `ChannelImplementationProvider` contract used by the existing provider registry. A production installed-package provider SHALL expose a stable package-specific implementation identifier derived by the host from its resolved durable repository identity, validated package presentation metadata, an empty schema-version-1 configuration provider, an empty configuration-field list, an empty required-capability set, one immutable program image, and an opaque provider-revision fingerprint equal to the active artifact digest. The provider SHALL NOT trust a package-selected built-in or internal identifier, register an alternative provider registry, add a special-case path in core dispatch, modify catalogue persistence, or create a Lua actor or Lua state at package materialization or provider registration time. Runtime construction SHALL create one Lua state and actor only for the addressed channel instance through standard registry reconciliation.

#### Scenario: Installed Lua provider is registered
- **WHEN** the host materializes and publishes a validated active installed package provider
- **THEN** it SHALL appear alongside built-in Kotlin providers through the same descriptor contract
- **AND** its descriptor, configuration-provider ID, installed snapshot key, and host-derived repository provider ID SHALL agree
- **AND** registration SHALL NOT create a Lua actor or Lua state

#### Scenario: Multiple instances reference one installed provider
- **WHEN** two catalogue definitions reference one installed Lua provider implementation ID
- **THEN** both SHALL resolve the same immutable active provider revision
- **AND** the registry SHALL construct independent runtime generations keyed by their distinct instance IDs
- **AND** each generation SHALL receive its own actor, state, cache, timers, tasks, logs, generation context, and capability scope

#### Scenario: Installed Lua provider constructs a runtime
- **WHEN** the registry invokes the provider's runtime constructor for a validated empty-configuration definition
- **THEN** the provider SHALL validate and load its host-resolved immutable program image
- **AND** it SHALL create one Lua state and one actor for that definition's instance ID
- **AND** it SHALL validate the entry callback table through the existing Lua Runtime v1 contract

#### Scenario: Package attempts to declare configuration or capabilities
- **WHEN** a package-format-v1 manifest contains configuration fields, nonempty defaults, required capabilities, credentials, host-resource references, or another unsupported provider declaration
- **THEN** package validation SHALL reject the artifact as an unknown or unsupported v1 manifest
- **AND** the Lua provider SHALL NOT expose or infer that declaration

#### Scenario: Lua provider is unavailable
- **WHEN** a catalogue definition references an installed Lua provider that is absent, loading, removed, corrupt, incompatible, or failed materialization
- **THEN** the host SHALL preserve and project the instance with a typed unavailability reason
- **AND** it SHALL NOT silently construct a generic Lua provider, default program image, retained old revision, or alternative runtime

### Requirement: Lua provider and runtime do not alter normal startup
Ordinary application startup SHALL register package-specific Lua providers only from the complete validated active installed-provider snapshot. An absent or empty installed-package store SHALL register no production Lua provider. Loading packages, materializing descriptors, and registering providers SHALL create no Lua actor or Lua state. Existing Kotlin providers SHALL remain behaviorally supported and SHALL NOT acquire a Lua runtime. A Lua actor SHALL be created only when the runtime registry constructs a generation for an enabled catalogue definition referencing an available installed Lua provider.

#### Scenario: Application starts with an empty installed store
- **WHEN** the application starts and the installed-provider snapshot is empty
- **THEN** no production Lua provider SHALL be present in provider resolution
- **AND** no Lua actor or Lua state SHALL be created by package loading, provider composition, catalogue loading, selection changes, readiness refresh, or service shutdown
- **AND** existing Kotlin channels SHALL operate normally

#### Scenario: Application loads an installed provider without instances
- **WHEN** startup materializes and registers one valid installed Lua provider but no catalogue definition references it
- **THEN** the provider descriptor SHALL be available for host provider surfaces
- **AND** no Lua actor or Lua state SHALL be created

#### Scenario: Catalogue references an installed provider
- **WHEN** an enabled catalogue definition references a compatible registered installed Lua provider
- **THEN** registration SHALL follow the same provider-resolution and runtime-construction flow as Kotlin providers
- **AND** exactly that instance's runtime construction MAY create one actor and one Lua state
- **AND** definitions using other providers SHALL NOT acquire Lua state

#### Scenario: Installed package loading fails
- **WHEN** the package repository fails to materialize one installed provider during startup
- **THEN** built-in Kotlin providers and valid sibling installed providers SHALL remain registered and operational
- **AND** no Lua state SHALL be created for the failed provider
