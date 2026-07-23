## MODIFIED Requirements

### Requirement: Lua provider implements the ChannelImplementationProvider contract
The Lua provider SHALL implement the `ChannelImplementationProvider` contract used by the existing provider registry. A production installed-package provider SHALL expose a stable package-specific implementation identifier derived by the host from its resolved durable repository identity, validated package presentation metadata, a schema-version-1 configuration provider compiled from the package-declared scalar schema, a configuration-field list, validated resource-mount declarations, a required-capability set compiled from package capabilities, one immutable program image, and an opaque provider-revision fingerprint equal to the active artifact digest. Resource declarations SHALL compile into generic editor/runtime metadata and SHALL NOT contain live platform grants or create platform access during validation, materialization, or registration. The provider SHALL NOT trust a package-selected built-in/internal identifier, register an alternative registry, add a package- or Journal-specific core dispatch path, modify catalogue persistence, or create a Lua actor/state during materialization or registration. Runtime construction SHALL create one Lua state and actor only for the addressed channel instance through standard registry reconciliation and SHALL resolve that instance's compatible host-owned resource bindings.

#### Scenario: Installed Lua provider is registered
- **WHEN** the host materializes and publishes a validated active installed package provider
- **THEN** it SHALL appear alongside built-in Kotlin providers through the same descriptor contract
- **AND** its descriptor, configuration-provider ID, resource declarations, installed snapshot key, and host-derived repository provider ID SHALL agree
- **AND** registration SHALL NOT create a Lua actor, Lua state, mount handle, or platform access

#### Scenario: Multiple instances reference one installed provider
- **WHEN** two catalogue definitions reference one installed Lua provider implementation ID
- **THEN** both SHALL resolve the same immutable active provider revision
- **AND** the registry SHALL construct independent runtime generations keyed by distinct instance IDs
- **AND** each generation SHALL receive its own actor, state, cache, timers, tasks, logs, generation context, capability scope, and instance-bound mount handles

#### Scenario: Installed Lua provider constructs a runtime
- **WHEN** the registry invokes the provider constructor for a definition with validated scalar configuration and compatible resource bindings
- **THEN** the provider SHALL validate and load its host-resolved immutable program image
- **AND** it SHALL create one Lua state and actor for that definition's instance ID
- **AND** it SHALL validate the entry callback table through revised Lua Runtime v1

#### Scenario: Package declares configuration, resources, and capabilities
- **WHEN** a revised package-format-v1 manifest contains valid scalar configuration, resource mounts, and capability declarations
- **THEN** package validation SHALL compile the declarations into the provider snapshot without executing Lua
- **AND** the provider SHALL expose scalar fields, generic mount metadata, and capability eligibility to standard host surfaces

#### Scenario: Required resource binding is missing
- **WHEN** a catalogue definition references a valid provider but lacks a usable binding for one required mount
- **THEN** the host SHALL preserve and project the instance with typed resource unavailability
- **AND** it SHALL not grant ambient storage, inject a default path, or call Lua to invent a binding

#### Scenario: Lua provider is unavailable
- **WHEN** a definition references an installed Lua provider that is absent, loading, removed, corrupt, incompatible, or failed materialization
- **THEN** the host SHALL preserve and project the instance with a typed unavailability reason
- **AND** it SHALL NOT silently construct a generic provider, default image, retained old revision, or alternative runtime

#### Scenario: Configuration or resource replacement spawns fresh generation
- **WHEN** an active channel's scalar configuration or mount binding is modified
- **THEN** the host SHALL stop predecessor admission, complete any committed terminal callback, drain/cancel descendants, revoke capabilities and mount handles, close the predecessor, and start a fresh generation with the new immutable inputs
- **AND** it SHALL NOT modify the predecessor actor or its authority in place
