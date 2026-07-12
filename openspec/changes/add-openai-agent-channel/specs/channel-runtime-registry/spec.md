## ADDED Requirements

### Requirement: Runtime generations own durable run authorization
The runtime registry SHALL assign a distinct generation identity to each live runtime instance and SHALL register every durable channel run with its channel instance ID, owning generation, and stable run identity. Completion of a transient PTT target SHALL release only that target's lease; it SHALL NOT implicitly complete or cancel an accepted durable run. Durable work MAY continue after the PTT callback returns, but every remote, tool, synthesis, playback, publication, and cancellation effect SHALL carry the owning generation authorization.

#### Scenario: PTT target releases while run continues
- **WHEN** a runtime accepts a user turn and its PTT target reaches its terminal callback before remote processing finishes
- **THEN** the registry SHALL release the transient target lease exactly once
- **AND** it SHALL retain the durable run owner and permit host-owned processing to continue
- **AND** no route, recorder, or PTT callback resource SHALL remain leased by the durable run

#### Scenario: Runtime is replaced for the same instance
- **WHEN** reconciliation replaces runtime generation G with generation H for one channel instance
- **THEN** new durable runs SHALL be owned by H
- **AND** runs already owned by G SHALL NOT be transferred implicitly to H
- **AND** G's outstanding effects SHALL be revoked or recovered only through the explicit durable-run contract

#### Scenario: Runs for different instances coexist
- **WHEN** durable runs are registered for two channel instances
- **THEN** each run SHALL retain its own instance and generation ownership
- **AND** completion, cancellation, queueing, or failure for one instance SHALL NOT mutate the other instance's runs

### Requirement: Runtime restart creates a fresh generation boundary
On service or process restart, the registry SHALL close and invalidate every prior runtime generation and SHALL allocate fresh generation identities for reconstructed runtimes. Persisted durable run and message records MAY be reconciled only through an explicit host recovery operation under a fresh generation; they SHALL NOT authorize callbacks from the prior process or automatically restore volatile conversation context. A late completion bearing a prior generation SHALL be stale regardless of its run status.

#### Scenario: Service restarts with a nonterminal run
- **WHEN** the service restarts while a durable run is queued, running, waiting for a tool, synthesizing, or pending playback
- **THEN** the registry SHALL reject callbacks carrying the old generation identity
- **AND** the host SHALL retain the persisted run and message state required by the durable-run contract
- **AND** any recovery under the new generation SHALL be explicit and SHALL NOT import the old volatile conversation as runtime context

#### Scenario: Old completion arrives after restart
- **WHEN** a network, tool, synthesis, or playback completion from the old process arrives after a new generation is installed
- **THEN** the registry SHALL classify it as stale or cancelled
- **AND** it SHALL NOT publish a response, play audio, execute a tool, advance a current run, or mutate current runtime status

#### Scenario: Fresh runtime starts after restart
- **WHEN** the host reconstructs a channel runtime after restart
- **THEN** the registry SHALL give it a generation identity distinct from every prior generation
- **AND** capability leases and durable run authorizations issued to the fresh runtime SHALL be distinct from all prior leases and authorizations

### Requirement: Durable run shutdown and recovery are separate from transient target closure
The registry SHALL distinguish transient committed input-target leases from durable run records. Runtime replacement, removal, or shutdown SHALL prevent new transient preparation, complete the existing target-lease ordering, and revoke active generation-bound effects without deleting durable records required for terminal-state, pending-response, or exact-once recovery. A later recovery SHALL use host-owned durable state and SHALL NOT invoke a closed runtime or replay an ambiguous external effect.

#### Scenario: Runtime is removed while a run is active
- **WHEN** a channel instance is removed while its PTT target is released but its durable run is still active
- **THEN** the registry SHALL prevent new input preparation and revoke the removed generation's live effects
- **AND** it SHALL preserve the durable run's terminal or recovery record according to the durable-run contract
- **AND** it SHALL NOT deliver late output to a replacement or unrelated channel instance

#### Scenario: Shutdown interrupts an external effect
- **WHEN** service shutdown interrupts a network request, tool call, synthesis operation, or playback admission
- **THEN** the registry SHALL cancel or detach the active effect according to its semantic contract
- **AND** it SHALL persist a terminal, indeterminate, or recoverable state without automatically replaying an effect whose delivery is ambiguous

#### Scenario: Recovery claims a persisted run
- **WHEN** a fresh runtime generation explicitly claims a persisted nonterminal run
- **THEN** the registry SHALL record the new generation owner before allowing callbacks
- **AND** callbacks from any previous owner SHALL remain stale
- **AND** the claim SHALL NOT reconstruct volatile conversation context unless a separate contract explicitly supplies it

### Requirement: Late durable completions cannot affect current registry state
Every durable callback SHALL include a run identity and generation authorization. The registry SHALL commit a completion, status, publication, playback admission, or tool result only when that authorization belongs to the current owner and the run has not reached a terminal state. Stale, duplicate, cancelled, and out-of-order callbacks SHALL be normalized without invoking provider or runtime code under the registry lock.

#### Scenario: Completion arrives after run cancellation
- **WHEN** a cancelled run later receives a remote, tool, synthesis, or playback completion
- **THEN** the registry SHALL ignore or classify the completion as stale
- **AND** it SHALL NOT publish, play, execute, or mark the cancelled run as successful

#### Scenario: Duplicate terminal completion arrives
- **WHEN** a durable run receives two terminal callbacks for the same stage
- **THEN** the registry SHALL commit at most one terminal transition
- **AND** it SHALL NOT duplicate a message, tool result, playback effect, or pending/heard transition

#### Scenario: Completion callback reenters the registry
- **WHEN** a durable completion callback observes or updates registry state synchronously or asynchronously
- **THEN** the registry SHALL release internal synchronization before invoking the callback
- **AND** it SHALL commit the callback result only if the run generation remains current
- **AND** unrelated registry lookup, projection, reconciliation, and lease release SHALL remain available

### Requirement: Durable registry boundaries remain language-neutral
Runtime-generation identities, durable run ownership, recovery claims, callback envelopes, and stale-effect outcomes SHALL be represented by language-neutral host-domain values and opaque handles. The registry SHALL NOT expose Kotlin coroutine jobs, Android callbacks, SDK futures, transport clients, audio routes, or a Lua-specific runtime ABI to providers or runtimes.

#### Scenario: Future language runtime owns a generation
- **WHEN** a future language adapter constructs a runtime and registers a durable run through the provider-neutral boundary
- **THEN** the registry SHALL apply the same generation, lease, recovery, and late-effect rules
- **AND** the adapter SHALL NOT need access to Kotlin, Android, SDK, or transport objects

#### Scenario: Registry implementation changes
- **WHEN** the host changes its coroutine, persistence, networking, or SDK implementation
- **THEN** generation and durable-run semantic contracts SHALL remain stable
- **AND** no implementation object SHALL become part of persisted run records or provider contracts
