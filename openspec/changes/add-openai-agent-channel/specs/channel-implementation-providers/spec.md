## ADDED Requirements

### Requirement: Provider configuration uses global profile and per-channel model choices
A provider that uses a remote model SHALL represent its connection choice as a stable global profile identifier and its model choice as a per-channel stable model identifier. The global profile SHALL own the endpoint and host-managed credential, SHALL NOT own a model selection, and SHALL NOT be copied into the channel payload. The provider SHALL resolve profile and model choices through semantic host-domain configuration services rather than embedding credentials, clients, network connections, SDK values, Android objects, or Compose state.

#### Scenario: Provider configuration selects a profile and model
- **WHEN** a user configures a remote-model channel instance
- **THEN** the provider SHALL persist the selected profile identifier and model identifier in its versioned opaque configuration
- **AND** the host SHALL resolve the endpoint and credential from the global profile at operation time
- **AND** the channel payload SHALL NOT contain the bearer credential or protocol client

#### Scenario: One profile serves multiple channel instances
- **WHEN** two channel instances select the same global profile with different model identifiers
- **THEN** each instance SHALL retain its own model choice and configuration identity
- **AND** changing a profile endpoint or credential SHALL NOT overwrite either instance's selected model

#### Scenario: Profile is unavailable
- **WHEN** a selected profile is deleted, disabled, invalid, or unavailable
- **THEN** provider validation or runtime readiness SHALL expose a typed unavailable reason for the addressed instance
- **AND** it SHALL NOT select another profile, endpoint, credential, or model implicitly

### Requirement: Provider model choices are dynamically discovered and schema-safe
A provider SHALL obtain model-choice metadata through an asynchronous host-owned discovery capability scoped to the selected profile. Provider schema validation SHALL remain deterministic and SHALL NOT perform network access; it SHALL validate a selected model against the latest available host discovery snapshot or return a typed pending or unavailable result when discovery is incomplete. A missing or retired model SHALL remain explicit and SHALL NOT be silently replaced by a default or another discovered model.

#### Scenario: Model discovery refreshes
- **WHEN** the host refreshes the model list for a selected profile
- **THEN** the provider SHALL receive host-domain model identifiers and display metadata without protocol SDK types
- **AND** a newly discovered model SHALL become selectable without a provider implementation or schema-version change

#### Scenario: Persisted model is no longer discovered
- **WHEN** a channel payload names a model that the selected profile no longer advertises
- **THEN** the provider SHALL preserve the model identifier in the payload
- **AND** it SHALL expose a typed model-unavailable state rather than substituting a different model or rewriting the payload

#### Scenario: Discovery is unavailable during configuration
- **WHEN** model discovery is pending or fails while a user edits provider configuration
- **THEN** the provider SHALL expose a typed pending or unavailable choice state
- **AND** schema processing SHALL NOT make a network request, fabricate choices, or commit a silently changed model

### Requirement: Provider choices use stable semantic references
Provider configuration SHALL encode external host resources as stable identifiers and scalar host-domain values only. A provider SHALL be able to declare dynamic choice requirements for profiles, models, keyboard profiles, or other host capabilities while leaving resolution and lifecycle ownership to the host. Provider configuration and presentation contracts SHALL remain independent of Android UI, SDK, transport, and connection objects.

#### Scenario: Host choice is rendered
- **WHEN** a provider editor requests choices for a profile, model, or keyboard profile field
- **THEN** the host SHALL supply typed choice metadata and availability reasons through a provider-neutral contract
- **AND** the provider SHALL retain only the selected stable identifier or semantic scalar

#### Scenario: Choice resource changes
- **WHEN** a referenced host resource changes, is removed, or becomes unavailable
- **THEN** the provider SHALL expose an explicit typed configuration or readiness error for that instance
- **AND** it SHALL NOT retain or mutate the removed host object, connection, or UI state

### Requirement: Provider runtime receives resolved semantic operations only
A provider SHALL construct a runtime from validated profile and model identifiers plus instance-scoped semantic host capabilities. Runtime construction SHALL NOT resolve credentials, instantiate protocol clients, call model endpoints, or inspect model-discovery transport state. The runtime SHALL request asynchronous completion, tool, synthesis, and playback work through the supplied capability contracts and SHALL remain usable by a future language adapter.

#### Scenario: Valid remote-model configuration constructs
- **WHEN** profile and model choices are valid according to provider schema and host availability
- **THEN** the provider SHALL construct a runtime with the stable selections and authorized semantic capabilities
- **AND** the host SHALL retain endpoint, credential, SDK client, network, tool transport, and playback ownership

#### Scenario: Runtime attempts platform access
- **WHEN** a provider runtime attempts to access an Android object, SDK client, credential, or transport connection
- **THEN** the host SHALL deny that access at the provider boundary
- **AND** the runtime SHALL receive a typed unavailable or denied result rather than a platform object
