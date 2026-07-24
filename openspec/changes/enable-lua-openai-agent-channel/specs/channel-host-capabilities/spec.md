## MODIFIED Requirements

### Requirement: Persistent script state is exposed only through declared mounted storage and durable work
The host SHALL NOT provide ambient filesystem access, unrestricted paths, a general persistent key-value/database service, package-writable source tree, installer/verifier capability, or platform storage object. Arbitrary package documents SHALL remain available only through declared user-bound mounts. Separately, a package declaring `work.queue` and named queues MAY use the generic durable-work API solely for bounded opaque FIFO payloads, effect evidence/results, recovery epochs, and terminal tombstones; it SHALL expose no query language, arbitrary keys, files, history database, cross-instance sharing, or host storage object. Both facilities SHALL enforce declaration, ownership, bounds, lifecycle, typed outcomes, and built-in independence.

#### Scenario: Mounted storage is initialized
- **WHEN** the host initializes generic package storage
- **THEN** only declared bound mounts SHALL authorize arbitrary persistent package files
- **AND** no runtime SHALL gain ambient host/source filesystem access

#### Scenario: Durable queue is initialized
- **WHEN** a package declares `work.queue` and a bounded queue ID
- **THEN** the host MAY persist only values and evidence admitted through the exact work API
- **AND** Lua SHALL receive no database, path, transaction, or storage-provider object

#### Scenario: Built-in runtime operates normally
- **WHEN** a built-in Kotlin runtime uses existing capabilities
- **THEN** it SHALL function without a Lua actor, mount, package profile, or work Queue

## ADDED Requirements

### Requirement: Generic HTTP remains transport-level rather than provider-level
The host SHALL expose HTTPS as a bounded semantic transport capability with normalized method, URL, headers, body, and response values. It SHALL own platform networking and TLS but SHALL not construct OpenAI/provider requests, inject provider credentials implicitly, interpret JSON/provider errors, discover models, iterate tools, or retain conversations. Existing host-owned OpenAI completion/model-discovery capabilities SHALL remain available only to the retained built-in and SHALL not be fallback paths for external packages.

#### Scenario: Lua package submits Chat Completion
- **WHEN** package Lua constructs an OpenAI-compatible request and calls generic HTTP
- **THEN** the host SHALL process it as HTTPS bytes/metadata under generic policy
- **AND** no OpenAI Kotlin contract SHALL participate in that request

### Requirement: Profile and secret capabilities are repository, selection, and generation scoped
The host SHALL expose generic profile lookup and protected secret resolution only through package-owned type identity, selected profile grant, declared capability, current profile revision, execution owner, and live generation/resolver scope. Profile lookup SHALL return detached scalar data and opaque secret references; secret resolution MAY return plaintext only under explicit grant. These capabilities SHALL not expose another package's profiles, protected aliases, credential-store objects, SDK clients, or ambient enumeration.

#### Scenario: Runtime selects one profile
- **WHEN** an authorized instance resolves its configured same-package profile
- **THEN** it SHALL receive only detached allowed fields and secret references
- **AND** sibling profile access SHALL remain denied

### Requirement: Durable work capabilities are semantic effect ledgers, not policy engines
The host SHALL own durable submission, FIFO sequence, claims, effect-start/result evidence, safe restart classification, epochs, quotas, purge, and projections through generic values. It SHALL not interpret payload schema, model messages, tool calls, protocol response, retry safety beyond committed effect evidence, conversation context, or package terminal meaning. Lua SHALL choose stable effect keys and application behavior; the host SHALL enforce non-replay when evidence is ambiguous.

#### Scenario: Work effect contains OpenAI response
- **WHEN** Lua commits a normalized provider result under an effect key
- **THEN** the host SHALL persist it as opaque bounded data until terminal purge
- **AND** no host adapter SHALL parse it as an OpenAI response

### Requirement: New capabilities remain explicitly acquired and independently revocable
Network, profile, secret, and work eligibility SHALL compile into the same instance/generation-scoped acquisition and lease model as existing capabilities, with additional repository/profile/queue/effect ownership where applicable. Revoking one authority SHALL not silently grant another or terminate unrelated package instances. Package removal, profile revision, configuration replacement, generation close, and service shutdown SHALL revoke affected leases and complete/cancel/indeterminate operations under their exact effect contracts.

#### Scenario: Secret authority is revoked during request preparation
- **WHEN** a profile revision invalidates the predecessor secret grant before HTTP effect starts
- **THEN** secret resolution/request preparation SHALL fail without remote effect
- **AND** unrelated profiles and queues SHALL remain usable
