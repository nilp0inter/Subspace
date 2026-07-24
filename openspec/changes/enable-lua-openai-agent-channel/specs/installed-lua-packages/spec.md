## ADDED Requirements

### Requirement: Installed snapshots publish profile types and resolver metadata atomically
Each committed active package revision SHALL publish its validated profile-type and choice-resolver declarations as part of the same immutable installed-provider snapshot used for provider materialization. Installation/update SHALL not expose a type or resolver before the complete artifact, index, source map, declarations, and provider revision commit. Rollback SHALL atomically restore predecessor declarations. Removal/corruption SHALL unpublish affected declarations and cancel active resolver invocations without affecting valid siblings.

#### Scenario: Package installation commits
- **WHEN** a package with profile types and resolvers passes complete validation and store commit
- **THEN** provider, profile types, resolver factories, and configuration sources SHALL become visible from one snapshot generation
- **AND** no partial metadata SHALL be observable

#### Scenario: Candidate update fails
- **WHEN** a new revision has an invalid resolver module or profile schema
- **THEN** the prior active revision and declarations SHALL remain authoritative
- **AND** no candidate actor or profile migration SHALL execute

### Requirement: Package lifecycle preserves repository-owned profiles safely
Profile records and protected secret references SHALL be stored independently from active package artifacts while retaining exact declaring repository/type identity. Update and rollback SHALL revalidate them without coercion. Package removal or active-artifact corruption SHALL preserve records but revoke publication/access and mark dependents unavailable. Reinstall of the same durable repository/type MAY restore access after validation; a different repository or coordinate reuse SHALL not inherit profiles.

#### Scenario: Package is removed
- **WHEN** the user removes a provider with existing profiles
- **THEN** its type and profiles SHALL become unavailable to editors/runtimes
- **AND** metadata and protected references SHALL remain for explicit reinstall or deletion

#### Scenario: Same repository is reinstalled
- **WHEN** a valid later artifact declares the same repository-scoped type
- **THEN** retained profiles SHALL be revalidated against that schema before publication
- **AND** incompatible profiles SHALL remain preserved and unavailable rather than defaulted

### Requirement: Package lifecycle reconciles durable work by exact revision cause
Durable queue records SHALL remain in the generic work store rather than package content/index documents. Ordinary process restart with the same active artifact/configuration/profile revision SHALL permit safe reclamation. Update, rollback, removal, provider corruption, configuration/profile replacement, or instance deletion SHALL retire affected work epochs: unstarted nonterminal work becomes cancelled, started uncommitted effects indeterminate, and terminal tombstones remain bounded. Activating another repository SHALL never claim predecessor work.

#### Scenario: Package update occurs with queued work
- **WHEN** an explicit update activates a new package revision
- **THEN** predecessor safe queued items SHALL be cancelled rather than executed by changed source
- **AND** the successor SHALL start a fresh work epoch

#### Scenario: Startup reloads unchanged package
- **WHEN** exact active revision and dependencies reload after process death
- **THEN** materialization SHALL reconnect safe work to the fresh runtime without storing Lua state in the package index

### Requirement: New declarations remain statically inspectable before trust and activation
Package candidate inspection and trust UI SHALL present bounded declared profile types, resolver presence and requested capabilities, work queue IDs, and security-relevant combinations including `secrets.read` plus unrestricted `network.http` before activation. Inspection SHALL use only validated manifest/source metadata and SHALL not execute a resolver, read a profile/secret, issue HTTP, open a queue, or create an actor. Empty declarations SHALL remain explicit.

#### Scenario: Secret-bearing network package is inspected
- **WHEN** a candidate declares profile secrets, `secrets.read`, and `network.http`
- **THEN** UI SHALL disclose that granted plaintext can be sent to any HTTPS origin
- **AND** inspection SHALL not access any existing credential

### Requirement: Existing official packages require explicit compatible updates
Official Debug, Diagnostics, Journal, and Keyboard package artifacts that omit newly required manifest declarations SHALL remain immutable and unavailable under the revised exact validator until their owning repositories publish explicit compatible revisions. The host SHALL not rewrite stored bytes, infer empty arrays, or silently execute them through an old parser. Updating one package SHALL not alter another package's source, profile, queue, or provider identity.

#### Scenario: Diagnostics is updated with empty declarations
- **WHEN** its official repository publishes a valid revision explicitly declaring empty profile/resolver/work arrays
- **THEN** ordinary update SHALL restore availability under the revised v1 contract
- **AND** it SHALL acquire no network, secret, profile, or work authority
