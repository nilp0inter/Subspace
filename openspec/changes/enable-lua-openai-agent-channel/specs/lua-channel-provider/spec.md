## ADDED Requirements

### Requirement: Provider materialization compiles profiles, resolvers, queues, and new capabilities generically
The installed-package materializer SHALL deterministically compile validated profile-type declarations, profile-backed and package-resolver dynamic sources, work-queue declarations, multiline fields, and `network.http`, `profiles.read`, `secrets.read`, and `work.queue` eligibility into one package-specific provider descriptor and immutable runtime resources. Materialization SHALL execute no Lua, resolver, profile lookup, secret lookup, HTTP request, queue mutation, or capability acquisition. It SHALL contain no OpenAI repository, endpoint, model, prompt, tool, or channel special case.

#### Scenario: OpenAI package is materialized
- **WHEN** the validated OpenAI package snapshot is published
- **THEN** the generic provider SHALL expose its compiled configuration/profile/resolver/queue metadata and eligibility
- **AND** no actor, profile, secret, request, or work item SHALL be created

#### Scenario: Package declares no new facilities
- **WHEN** an existing package declares empty profile, resolver, and queue arrays and no new capabilities
- **THEN** materialization SHALL preserve its ordinary provider behavior
- **AND** it SHALL not construct unused subsystem adapters

### Requirement: Runtime construction binds exact profile and work revisions
When constructing an enabled instance, the provider SHALL validate the selected profile type/ID, current profile revision, every current dynamic reference required for readiness, declared queue namespaces, package revision, channel configuration revision, and capability eligibility before activation. It SHALL pass only opaque generic adapters and detached scalar configuration to the Lua runtime. It SHALL not place plaintext secrets, mutable profiles, resolver actors, durable-store records, HTTP clients, or platform objects in construction requests.

#### Scenario: Selected profile is current
- **WHEN** runtime construction receives valid configuration selecting an available same-package profile
- **THEN** it SHALL bind generation-scoped profile and queue authority
- **AND** Lua SHALL still resolve secret plaintext only through `subspace.secrets`

#### Scenario: Selected profile is unavailable
- **WHEN** the profile is missing, foreign, incompatible, or unavailable
- **THEN** construction/readiness SHALL produce typed unavailability without creating a partially authorized actor

### Requirement: Provider exposes package resolver factories separately from channel actors
A package dynamic-choice invocation SHALL resolve through a provider-owned resolver factory bound to the exact active package revision and declaration, not through a live channel runtime actor. Creating or closing a resolver actor SHALL not construct, replace, select, or mutate a channel runtime. Resolver results SHALL return only to the requesting configuration/readiness operation and SHALL be rejected when their package/profile/dependency revision is stale.

#### Scenario: Editor resolves models before instance startup
- **WHEN** the editor invokes a declared package resolver for a draft configuration
- **THEN** the provider SHALL create a restricted resolver execution without channel startup
- **AND** no durable queue worker or lifecycle callback SHALL run

### Requirement: Provider reconciliation includes profile and work dependencies
The provider's dependency projection SHALL observe active package revision, selected profile revision/availability, resolver-backed reference state, queue-store availability, and declared host capability availability. A change to one of those dependencies SHALL reconcile only affected instances. Process restart with unchanged revisions SHALL preserve safe work epoch recovery, whereas configuration/profile/package replacement SHALL retire predecessor work authority according to the durable-work contract.

#### Scenario: Selected profile changes
- **WHEN** a profile edit commits a new revision
- **THEN** dependent runtime generations SHALL be replaced through the ordinary runtime registry
- **AND** unrelated package instances SHALL remain live

#### Scenario: Work store is unavailable
- **WHEN** a declared queue cannot load safely
- **THEN** the instance SHALL project typed unavailability
- **AND** it SHALL not accept input that claims durable admission
