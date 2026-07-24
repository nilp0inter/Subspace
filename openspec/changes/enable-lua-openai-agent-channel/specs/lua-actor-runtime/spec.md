## MODIFIED Requirements

### Requirement: Runtime mechanisms remain distinct from plugin policy and host-native capabilities
The runtime SHALL own scheduling, mailbox admission, coroutine continuation, typed request ownership, instruction/memory policy, failure containment, resolver-state containment, durable-work effect execution ownership, replacement draining, and deterministic teardown. The generic durable-work subsystem SHALL own opaque FIFO admission, effect evidence, recovery safety, epochs, quotas, and tombstones without interpreting payloads. Lua SHALL own package protocol, provider requests/responses, retry decisions, polling, arbitrary mounted-storage layout, conversation state, tool policy, and reconstruction from committed generic effect results. Every blocking operation SHALL yield an opaque typed request and remain lifecycle/capability/resource authorized. No actor/runtime component SHALL embed package-specific policy, OpenAI/Journal operation kinds, provider endpoints/models/tools, package migration, or plugin-selected native transport.

#### Scenario: Blocking host operation is requested
- **WHEN** Lua requests an effect that cannot complete in a deliberately small bounded native call
- **THEN** the runtime SHALL yield an opaque typed request before executing it
- **AND** the Lua thread SHALL not block waiting for completion

#### Scenario: OpenAI policy is expressed in Lua
- **WHEN** the external package constructs requests, interprets responses, iterates tools, or rebuilds a safe Job from committed effects
- **THEN** policy SHALL execute within actor bounds
- **AND** host scheduling, durable evidence, ownership, authorization, and containment SHALL remain authoritative without OpenAI semantics

#### Scenario: Generic work survives restart
- **WHEN** process restart finds an item proven safe to reclaim
- **THEN** a fresh actor MAY receive the opaque payload and committed effect results through public work methods
- **AND** no predecessor Lua stack, coroutine, module cache, or conversation SHALL be restored

## ADDED Requirements

### Requirement: Resolver actors use a restricted one-shot execution mode
The actor kernel SHALL support a resolver mode that creates one independent bounded state, validates/loads the declared resolver module under the effect guard, invokes exactly one protected yield-capable `resolve` function, permits only resolver-authorized host requests, returns one validated terminal result, and closes deterministically. Resolver actors SHALL have separate admission, instruction, memory, task, request, and deadline policy and SHALL not enter the ordinary channel mailbox/startup/readiness lifecycle or create background/deferred tasks.

#### Scenario: Resolver suspends for HTTPS
- **WHEN** the resolve function issues an authorized HTTP request
- **THEN** the resolver actor SHALL release the execution slot and resume the same resolver coroutine at most once
- **AND** closure or deadline SHALL suppress late completion

#### Scenario: Resolver tries to spawn
- **WHEN** resolver code calls `runtime.spawn` or `defer`
- **THEN** the operation SHALL fail before task admission
- **AND** the one-shot actor SHALL remain bounded

### Requirement: Durable work effect scopes integrate with actor scheduling
A managed task calling `Job:effect` SHALL create a task-local protected effect scope after the durable subsystem commits the start marker. Authorized operations from the function SHALL carry both managed-task and work-effect ownership. The actor SHALL permit repeated suspension/resumption, but at most one active work effect per Job and no nested effect. After a valid terminal Lua return, normalization and durable commit SHALL finish before the outer task resumes. Close/interruption after start but before commit SHALL discard Lua and notify work reconciliation of ambiguity exactly once.

#### Scenario: Effect yields multiple times
- **WHEN** one effect function reads a secret and then performs HTTPS
- **THEN** each host request SHALL suspend/resume under the same effect owner
- **AND** one normalized function result SHALL be committed before outer resumption

#### Scenario: Generation closes during effect
- **WHEN** replacement closes the actor after effect start
- **THEN** the function SHALL not resume in a successor
- **AND** the durable job SHALL become indeterminate unless a terminal result was already committed

### Requirement: Actor teardown invalidates new opaque values and releases bounded payloads
Generation/resolver closure SHALL invalidate Queue, Job, Effect, SecretReference, and JSON Null tokens owned by the closing state; cancel receives and host requests; release retained request/effect-function references; and suppress late completions. Durable queue records and committed results SHALL remain owned by the durable subsystem, not the actor. Actor teardown SHALL not delete safe queued work, terminal tombstones, package profiles, or protected secrets except through their explicit lifecycle contracts.

#### Scenario: Worker is suspended in receive at close
- **WHEN** its generation closes
- **THEN** the receive coroutine and Queue token SHALL be discarded
- **AND** durable queue records SHALL be reconciled according to epoch cause
