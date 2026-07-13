## Purpose

TBD. Defines OpenAI-compatible connection profiles: global stable identity, host-owned secrets and protocol clients, profile-scoped model discovery, explicit non-destructive deletion/unavailability, and language-neutral profile contracts.

## Requirements

### Requirement: Global profiles have stable identity and host-owned secrets
The host SHALL maintain OpenAI-compatible connection profiles in a global collection independent of channel instances. Each profile SHALL have a stable, non-blank profile ID, a host-visible name, a base URL, and a host-owned bearer credential. Profile IDs and non-secret profile fields SHALL survive service and application restarts. Bearer credential values SHALL remain in host-owned protected secret storage and SHALL NOT appear in channel configuration payloads, generic runtime capabilities, UI projections, logs, persisted messages, or semantic errors. A profile SHALL NOT contain a model selection; model selection SHALL belong to a channel instance.

#### Scenario: A profile survives restart
- **WHEN** a user creates a profile with a stable ID, base URL, and bearer credential
- **AND** the service or application restarts
- **THEN** the host SHALL restore the same profile ID and base URL
- **AND** the host SHALL resolve the credential through host-owned secret storage
- **AND** a channel SHALL still have no access to the credential value

#### Scenario: A profile does not carry a model
- **WHEN** a profile is presented for global management or resolved for a channel
- **THEN** the profile record SHALL contain endpoint and credential information but SHALL NOT contain a selected model ID
- **AND** two channel instances referencing that profile SHALL be able to select different discovered models

#### Scenario: A secret is requested by channel code
- **WHEN** a channel runtime or provider asks for a profile credential
- **THEN** the host SHALL deny direct secret access
- **AND** protocol requests SHALL use the credential only inside the host-owned connection boundary
- **AND** the denial or diagnostic SHALL NOT include the secret value

### Requirement: The host owns protocol clients and client reuse
The host SHALL own creation, authentication, reuse, cancellation, retry policy, and shutdown of the protocol client for each connection profile. Concurrent operations for the same effective profile endpoint and credential revision SHALL reuse the host-managed client according to host serialization policy rather than creating channel-owned clients. Channel providers and runtimes SHALL exchange only language-neutral semantic request and result values and SHALL NOT receive SDK clients, SDK request or response objects, HTTP clients, sockets, or transport exceptions. Changing a profile's endpoint or credential SHALL retire the prior client revision without allowing late work from that revision to affect current channel state.

#### Scenario: Two channels share one profile
- **WHEN** two channel instances submit operations using the same available profile revision
- **THEN** the host SHALL route both operations through the host-managed client for that revision
- **AND** neither channel runtime SHALL construct or close a protocol client

#### Scenario: Profile credentials change
- **WHEN** the user replaces a profile's endpoint or bearer credential
- **THEN** the host SHALL create or select a new client revision for subsequent operations
- **AND** operations already associated with the prior revision SHALL terminate with typed cancellation, failure, or indeterminate outcomes according to observed effects
- **AND** a late prior-revision completion SHALL NOT publish a current response or tool effect

#### Scenario: A protocol failure reaches a runtime
- **WHEN** the host client encounters a network, HTTP, decoding, authentication, or provider failure
- **THEN** the host SHALL normalize it to a language-neutral semantic outcome
- **AND** the runtime SHALL NOT receive SDK or transport exception objects
- **AND** unrelated profiles and channel instances SHALL remain operational

### Requirement: Model discovery is profile-scoped and host-owned
The host SHALL discover model choices through the models endpoint of each configured connection profile and SHALL expose normalized model IDs and display metadata through a language-neutral profile capability. A channel SHALL select a model only from the discovered choices associated with its referenced profile. Discovery SHALL preserve the profile identity, SHALL NOT write a model into the global profile, and SHALL report typed loading, stale, unavailable, or failed states without exposing protocol objects or credentials.

#### Scenario: Models are discovered for a profile
- **WHEN** a configured profile's models endpoint returns model records
- **THEN** the host SHALL publish a normalized model-choice set associated with that profile ID
- **AND** a channel editor SHALL be able to select one of those model IDs
- **AND** a model from another profile SHALL NOT be substituted

#### Scenario: Model discovery fails
- **WHEN** model discovery cannot complete because the endpoint, credential, network, or response is invalid
- **THEN** the profile SHALL expose a typed unavailable or discovery-failed state
- **AND** the host SHALL preserve the profile definition and any existing channel model IDs
- **AND** no channel SHALL be treated as ready solely because a model name was previously typed or cached

#### Scenario: A selected model becomes stale
- **WHEN** a channel references a model ID that is absent from the latest successful discovery for its profile
- **THEN** that channel SHALL expose a typed model-unavailable state
- **AND** the host SHALL NOT silently choose another model or change the channel configuration
- **AND** the channel SHALL become eligible again only after its selected model is validly discovered

### Requirement: Profile deletion and unavailability are explicit and non-destructive
The host SHALL expose explicit availability and typed actionable reasons for profiles that are missing, disabled, malformed, unauthenticated, unreachable, discovery-failed, or otherwise unusable. Deleting a profile SHALL stop new protocol work for that profile while preserving channel instances that reference its stable ID and projecting those channels as unavailable; deletion SHALL NOT silently rebind a channel to another profile or discard its provider-owned configuration. A newly created profile SHALL use a distinct stable ID rather than implicitly reusing a deleted profile's identity. Restoring a profile or correcting its endpoint or credential SHALL make dependent channels eligible again only after the referenced model is discovered and valid.

#### Scenario: A referenced profile is deleted
- **WHEN** the user deletes a profile referenced by one or more OpenAI Agent channel instances
- **THEN** the profile SHALL be absent from the active global profile collection
- **AND** each referencing channel instance SHALL remain in the ordered channel catalogue with its profile ID and configuration intact
- **AND** each channel SHALL expose a typed unavailable reason
- **AND** no new completion, model discovery, tool, or synthesis operation for that profile SHALL start

#### Scenario: A profile is unavailable without deletion
- **WHEN** a stored profile cannot authenticate, reach its endpoint, or complete model discovery
- **THEN** the host SHALL retain the profile and its stable ID
- **AND** dependent channels SHALL remain addressable but SHALL refuse new input with the typed unavailable reason
- **AND** unrelated profiles and channels SHALL remain operational

#### Scenario: A profile is restored
- **WHEN** the user repairs an unavailable profile's endpoint or credential and model discovery succeeds
- **THEN** the host SHALL retain the profile's stable ID
- **AND** dependent channels SHALL use their existing selected model only if that model is present in the successful discovery result
- **AND** the host SHALL NOT reset or silently rewrite the channel's system prompt or keyboard configuration

### Requirement: Profile operations expose language-neutral semantic contracts
The profile boundary SHALL expose only host-domain values for profile identity, endpoint configuration status, credential presence, model choices, availability, and normalized errors. It SHALL keep protocol implementation details, SDK types, HTTP behavior, endpoint retries, cancellation, and secret handling host-owned so that a future language adapter can use the same contracts without importing an SDK or platform API.

#### Scenario: A runtime consumes profile status
- **WHEN** a channel runtime needs to decide whether its configured profile or model is usable
- **THEN** the host SHALL provide a typed semantic availability and model-choice result
- **AND** the result SHALL contain no SDK object, HTTP client, credential value, or platform transport object

#### Scenario: A future language adapter uses the boundary
- **WHEN** a non-Kotlin channel adapter consumes profile discovery and completion capabilities
- **THEN** it SHALL use the same profile IDs, model IDs, semantic requests, and normalized outcomes
- **AND** the profile contract SHALL NOT require a Lua engine, package system, or language-specific ABI