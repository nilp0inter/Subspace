## MODIFIED Requirements

### Requirement: Runtime construction is instance scoped
A provider SHALL construct a runtime only from one channel instance's stable ID, effective definition metadata, validated current-version configuration, and an instance-scoped host capability acquisition boundary. The provider SHALL also receive an opaque host-owned generation execution context that supplies typed, generation-bound timer scheduling and background-task admission without exposing a Kotlin `CoroutineScope`, `ActorRuntime`, registry gate, Android object, or any other platform implementation type. This context SHALL be bound exclusively to the single generation being constructed. Admission after that generation is closed or replaced SHALL return a typed `CLOSED` rejection; bounded resource exhaustion SHALL return a distinct typed `CAPACITY_EXHAUSTED` rejection. Accepted timer callbacks SHALL be suppressed after close. The host SHALL NOT expose operation tokens, coroutine references, userdata, native handles, or platform values through this context.

#### Scenario: Runtime is constructed for a valid definition
- **WHEN** a registered provider successfully migrates and validates one available channel definition
- **THEN** the host SHALL invoke that provider's runtime constructor with the definition's own instance ID, validated configuration, and an opaque generation execution context
- **AND** any host capabilities made available to the runtime SHALL be scoped to that instance

#### Scenario: Generation execution context is opaque and does not expose platform objects
- **WHEN** the provider inspects the generation execution context supplied to its runtime constructor
- **THEN** the context SHALL expose only lifecycle-continuation authority through typed operations
- **AND** it SHALL NOT expose a `CoroutineScope`, actor identity, registry gate, `lua_State*`, Android `Context`, coroutine reference, userdata, or any native or platform object

#### Scenario: Generation execution context rejects post-close admission
- **WHEN** the constructed runtime generation is closed or replaced and a provider attempts to schedule a timer or admit a task through the execution context
- **THEN** the context SHALL reject the admission with the typed reason `CLOSED`
- **AND** it SHALL NOT schedule work, invoke a callback, forward the operation to an actor or Lua state, or conflate closure with capacity exhaustion

#### Scenario: Accepted timer becomes stale after close
- **WHEN** a provider scheduled a timer while its generation was live and that generation closes before the timer callback is admitted
- **THEN** the host SHALL suppress the callback and dispose its generation-bound timer resources
- **AND** it SHALL NOT invoke the provider, actor, or Lua state

#### Scenario: Sibling configuration remains isolated
- **WHEN** a provider constructs or updates one of multiple instances using the same implementation identifier
- **THEN** it SHALL use only the addressed instance's configuration and capability scope
- **AND** each instance SHALL receive its own generation execution context
- **AND** it SHALL NOT read or mutate a sibling instance by implementation identifier or catalogue order

#### Scenario: Existing Kotlin provider constructs a runtime
- **WHEN** a built-in Kotlin provider constructs a runtime without using the opaque generation execution context
- **THEN** the host SHALL still supply a generation execution context even if the Kotlin provider does not consume it
- **AND** the existing behavior of the Kotlin provider SHALL remain unchanged
- **AND** the provider SHALL NOT be required to reference the context
