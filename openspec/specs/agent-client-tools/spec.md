## Purpose

TBD. Defines agent client tool contracts: channel-scoped configuration-driven exposure, language-neutral semantic values, automatic execution without per-call approval, fixed Keyboard tool semantics, sequential execution, exact-once durable ledger accounting, paired results, and host-owned extensibility.

## Requirements

### Requirement: Tool exposure is channel-scoped and configuration-driven
The host SHALL expose tools to an agent run only when the channel instance's validated configuration enables them. Tool exposure SHALL be isolated by stable channel instance identity; one channel's enabled tools, keyboard profile, or execution state SHALL NOT authorize another channel. A disabled or unavailable tool SHALL NOT be advertised as callable for that channel, and a direct request for it SHALL receive a normalized rejection without executing an effect.

#### Scenario: Keyboard tools are enabled for one channel
- **WHEN** an agent channel configuration enables its Keyboard tools and selects a host keyboard profile/layout
- **THEN** the host SHALL advertise only the enabled semantic Keyboard tool descriptors to that channel's model request
- **AND** the descriptors SHALL be associated with that channel instance

#### Scenario: A tool is disabled
- **WHEN** a run requests a tool that is not enabled in its channel configuration
- **THEN** the host SHALL reject the request with a normalized unavailable or disabled result
- **AND** it SHALL perform no host effect

#### Scenario: Two channels use different tool configuration
- **WHEN** two channel instances run concurrently or have queued work with different enabled tools
- **THEN** each run SHALL receive only its own channel's tool descriptors and configuration
- **AND** no call, result, or keyboard profile SHALL cross the channel boundary

### Requirement: Tool contracts are language-neutral semantic values
A tool request SHALL contain only a channel/run identity, a stable call identity, a language-neutral tool identifier, and validated host-domain arguments. A tool result SHALL contain the matching call identity, a normalized outcome, and language-neutral result data or a semantic failure reason. Tool contracts SHALL NOT expose Android types, SDK request/response types, transport clients, audio objects, credentials, exceptions, or implementation-specific handles to a channel runtime, provider, or future language adapter.

#### Scenario: A runtime receives tool descriptors
- **WHEN** the host prepares an agent request for a channel with enabled tools
- **THEN** it SHALL provide descriptors containing stable names, argument schema, and semantic descriptions
- **AND** it SHALL not provide an Android object, OpenAI SDK object, or transport connection

#### Scenario: A tool executor reports failure
- **WHEN** host-owned tool execution fails
- **THEN** the host SHALL normalize the failure into the tool result's semantic failure outcome
- **AND** the result SHALL not contain a platform exception, SDK response object, credential, or transport diagnostic

#### Scenario: A future runtime consumes the contract
- **WHEN** a future language adapter consumes a tool descriptor, request, or result
- **THEN** it SHALL be able to do so using the same language-neutral values
- **AND** it SHALL not require Android, OpenAI SDK, or provider-specific ABI types

### Requirement: Configured tools execute automatically without per-call approval
When a tool is enabled and its arguments pass the declared schema, the host SHALL execute each requested call automatically as part of the run. The host SHALL NOT pause for, request, or require per-call user authorization. Channel-level configuration SHALL be the authorization boundary for the current behavior; a rejected, disabled, malformed, or unavailable call SHALL not be converted into an approval prompt.

#### Scenario: An enabled call is received
- **WHEN** a run requests an enabled tool with schema-valid arguments
- **THEN** the host SHALL begin host-owned execution without a per-call approval step
- **AND** it SHALL record and return one normalized result for the call

#### Scenario: A call requires unavailable authorization
- **WHEN** a run requests a tool that channel configuration does not authorize
- **THEN** the host SHALL return a normalized disabled or unavailable result
- **AND** it SHALL not ask the user to approve that individual call
- **AND** it SHALL not execute the effect

### Requirement: Keyboard tools have fixed semantic definitions
The current Keyboard tool set SHALL contain exactly the configured semantic operations `type_text` and `press_enter`. `type_text` SHALL accept one text argument and request that the host type that text through the channel's selected host keyboard profile/layout. `press_enter` SHALL accept no arguments and request one Enter action through that same selected profile/layout. Tool contracts SHALL describe intent and outcomes, not Bluetooth, HID, Android input, or transport operations.

#### Scenario: Model requests type_text
- **WHEN** a run requests `type_text` with a valid text argument and Keyboard tools are enabled
- **THEN** the host SHALL submit that text through the selected keyboard profile/layout
- **AND** the result SHALL identify the same semantic call as succeeded or with a normalized terminal failure

#### Scenario: Model requests press_enter
- **WHEN** a run requests `press_enter` with no arguments and Keyboard tools are enabled
- **THEN** the host SHALL request exactly one Enter action through the selected keyboard profile/layout
- **AND** the result SHALL identify the same semantic call as succeeded or with a normalized terminal failure

#### Scenario: Keyboard arguments are invalid
- **WHEN** `type_text` has a missing or non-text argument, or `press_enter` has any argument
- **THEN** the host SHALL reject the call before invoking keyboard output
- **AND** the result SHALL identify a schema or argument failure

### Requirement: Tool calls execute sequentially within a run
The host SHALL process tool calls for one run in the order provided by the model and SHALL wait for each call's terminal result before submitting the next call's result-bearing continuation. It SHALL NOT execute two calls from the same run concurrently or reorder calls. The host-owned tool loop SHALL enforce a finite call bound and SHALL terminate with a normalized limit outcome when that bound is reached.

#### Scenario: The model returns multiple calls
- **WHEN** one completion contains an ordered sequence of tool calls
- **THEN** the host SHALL execute the first call and obtain its terminal result before executing the second
- **AND** the host SHALL preserve the model-provided order

#### Scenario: A call is slow
- **WHEN** an earlier call has not reached a terminal result
- **THEN** later calls in that run SHALL remain unexecuted and ordered
- **AND** the host SHALL not start them concurrently to reduce latency

#### Scenario: The tool loop reaches its bound
- **WHEN** a run requests more tool calls than the finite host-owned loop bound permits
- **THEN** the host SHALL stop issuing further tool effects
- **AND** it SHALL complete the run with a normalized tool-loop-limit or equivalent terminal outcome
- **AND** it SHALL retain the already recorded call/result pairs

### Requirement: The durable tool ledger provides exact-once call accounting
The host SHALL durably record every accepted tool call before attempting its effect, keyed by channel instance, run identity, and stable call identity. The ledger SHALL record the tool identifier, validated argument identity, attempt state, and terminal normalized result. Under known outcomes, a call SHALL have exactly one host effect attempt and exactly one terminal result; when the outcome of an attempted effect cannot be proven, the ledger SHALL preserve that call as indeterminate rather than claiming a duplicate-safe success. A duplicate observation of the same call SHALL reuse its existing terminal result rather than execute the effect again. A call whose prior effect outcome is unknown SHALL be recorded as indeterminate and SHALL NOT be replayed automatically.

#### Scenario: A new call is reserved
- **WHEN** a valid tool call is first accepted for a run
- **THEN** the host SHALL durably reserve the call before invoking its effect
- **AND** recovery or duplicate delivery SHALL observe that reservation

#### Scenario: A completion is delivered twice
- **WHEN** the same run and call identity are observed again with the same arguments
- **THEN** the host SHALL return the previously recorded result
- **AND** it SHALL not perform another Keyboard or future-tool effect

#### Scenario: The host stops during an effect
- **WHEN** service interruption occurs after a tool effect may have started but before its terminal outcome is durably known
- **THEN** recovery SHALL mark that call indeterminate
- **AND** it SHALL not invoke the effect again merely to obtain a result
- **AND** the run SHALL receive a normalized indeterminate outcome

#### Scenario: A call identity is reused with different arguments
- **WHEN** a call identity already has a ledger entry but a later observation supplies a different tool or argument identity
- **THEN** the host SHALL reject the conflicting observation as invalid
- **AND** it SHALL not execute either observation a second time

### Requirement: Every accepted call has exactly one paired result
For every accepted tool call, the host SHALL produce exactly one durable terminal result carrying the same channel instance, run identity, and call identity. The result SHALL be supplied to the model continuation in the original call order, including normalized failure, disabled, limit, or indeterminate outcomes. A result from one run or channel SHALL never satisfy a call in another run or channel.

#### Scenario: Keyboard call succeeds
- **WHEN** an accepted Keyboard call reaches a successful terminal outcome
- **THEN** the host SHALL persist one succeeded result with the exact call identity
- **AND** the model continuation SHALL receive that result paired to the original call

#### Scenario: Keyboard call fails
- **WHEN** an accepted Keyboard call reaches a rejected, failed, or indeterminate terminal outcome
- **THEN** the host SHALL persist one normalized non-success result with the exact call identity
- **AND** the host SHALL not fabricate a successful result or omit the call from the continuation

#### Scenario: Result delivery is interrupted
- **WHEN** service interruption occurs after a result is durably recorded but before the model continuation is sent
- **THEN** recovery SHALL resend the recorded result to that same run without re-executing the call
- **AND** the ledger SHALL continue to contain one result for that call

### Requirement: Tool extensibility preserves host ownership
The host SHALL permit future host-owned semantic tools to use the same descriptor, validation, invocation, ledger, and normalized-result contracts without changing the channel runtime ABI. Adding a tool SHALL require a host-owned descriptor and executor and SHALL NOT require a runtime to know Android or SDK types. This change SHALL expose only the configured Keyboard tools; arbitrary package-provided tool registration, camera behavior, and per-call approval workflows are outside current behavior.

#### Scenario: A future semantic tool is added
- **WHEN** the host registers a future tool with a descriptor, schema validator, and semantic executor
- **THEN** an eligible channel SHALL be able to expose it through the existing language-neutral contract
- **AND** sequential execution, durable ledger, exact pairing, and indeterminate no-replay rules SHALL apply unchanged

#### Scenario: A provider attempts platform leakage
- **WHEN** a provider or runtime attempts to register or consume an Android, SDK, transport, camera, or credential object as a tool contract
- **THEN** the host SHALL reject that contract as outside the semantic tool boundary
- **AND** no such object SHALL be passed into the channel runtime

#### Scenario: Current tool set is enumerated
- **WHEN** a channel requests its available tools under this change
- **THEN** the host SHALL expose only configured `type_text` and `press_enter`
- **AND** it SHALL expose neither camera capabilities nor a per-call approval tool