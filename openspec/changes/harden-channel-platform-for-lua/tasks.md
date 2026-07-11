## 1. Provider and Generic Catalogue Foundation

- [x] 1.1 Define stable non-enumerated channel implementation identifiers, immutable opaque JSON configuration payloads, provider configuration schema versions, and typed provider/configuration errors
- [x] 1.2 Define the channel implementation descriptor and provider contracts for metadata, defaults, validation, stepwise migration, native form schema, preparation traits, required capabilities, and suspendable runtime construction
- [x] 1.3 Implement deterministic provider registration and resolution with duplicate-ID rejection and explicit missing-provider results
- [x] 1.4 Implement the native host-rendered configuration field model needed by Journal, Debug, and Keyboard without exposing Compose or Android UI objects through provider contracts
- [x] 1.5 Implement built-in Journal, Debug, and Keyboard descriptors with stable namespaced implementation IDs, defaults, lossless config codecs, validation, and current schema versions
- [x] 1.6 Replace catalogue kind/config validation with generic envelope validation plus provider-owned payload validation while preserving instance ID, ordering, active-selection, and mutation invariants
- [x] 1.7 Implement catalogue document v2 encoding that preserves opaque provider payload fields and keeps document version separate from provider config-schema version
- [x] 1.8 Implement v1 decoding and atomic v1-to-v2 migration that preserves seeded IDs, names, enabled state, order, active ID, and all existing built-in configuration
- [x] 1.9 Preserve valid unavailable-provider definitions without migration or payload rewriting, and fail catalogue migration atomically when a required available-provider migration or commit fails
- [ ] 1.10 Add focused provider/catalogue tests for registration conflicts, defaults, validation, stepwise migration, unknown fields, unavailable providers, v1-to-v2 preservation, atomic failure, and multiple same-provider instances

## 2. Runtime Invocation and Lifecycle Boundary

- [x] 2.1 Introduce typed runtime invocation outcomes for success, busy, unavailable, cancelled, timeout, provider failure, runtime failure, and closed generation
- [x] 2.2 Implement a host-owned bounded worker dispatcher and per-runtime-generation serialized invocation queue that never executes provider/runtime callbacks on Android main
- [x] 2.3 Apply injected callback deadlines and cancellation propagation to provider construction and every runtime lifecycle/input callback without allowing cancellation to become success
- [x] 2.4 Gate status publication, capability effects, and callback results by live runtime generation so timed-out, replaced, retired, or closed work cannot publish late effects
- [x] 2.5 Replace unparented runtime `Job()` scopes with explicit runtime-owned child jobs and make runtime closure suspendable, bounded, idempotent, and join owned work
- [x] 2.6 Normalize callback failures at the invocation boundary without exposing implementation stack traces as channel data or terminating unrelated runtime queues
- [ ] 2.7 Add focused invocation tests for ordering, independent instances, queue saturation, timeout, cancellation, callback exceptions, main-thread handoff, repeated close, ignored cancellation, and late-effect suppression

## 3. Registry Reconciliation and Projection

- [ ] 3.1 Replace kind-keyed factory resolution with provider-reference resolution while retaining one instance-ID-keyed `ChannelRuntimeRegistry`
- [ ] 3.2 Represent missing, incompatible, migration-failed, validation-failed, and construction-failed providers as explicit unavailable registry entries with actionable typed reasons
- [ ] 3.3 Refactor registry synchronization so locks protect only structural state and all provider construction, validation, preparation, readiness, target, cancellation, and close callbacks execute outside registry locks
- [ ] 3.4 Implement generation-checked two-phase reconciliation that closes stale construction results and never publishes results for replaced or removed definitions
- [ ] 3.5 Preserve committed-target leases across selection, reorder, update, removal, provider replacement, and suspended preparation, and release each lease only after its terminal callback completes
- [ ] 3.6 Make shutdown stop admission, await active-session termination and committed lease release, then close live and retired runtimes exactly once outside registry locks
- [ ] 3.7 Expose an ordered aggregate runtime projection that continuously combines catalogue order with live and unavailable entry snapshots
- [ ] 3.8 Update service state and Android Auto invalidation to collect the aggregate projection instead of sampling runtime snapshot values during catalogue/readiness refreshes
- [ ] 3.9 Add focused registry tests for unavailable entries, callback reentrancy, concurrent lookup, stale generations, shutdown with committed leases, close ordering, aggregate status propagation, and exact-once closure

## 4. Exact-Once Audio Session Termination

- [x] 4.1 Consolidate normal release, setup failure, cancellation, timeout, stale preparation, capture failure, and service teardown into one audio-session terminal state machine
- [x] 4.2 Make terminal ownership claim exactly once and independently attempt applicable target notification, capture termination, route release, target-lease release, active-session clearance, and completion publication
- [x] 4.3 Ensure an exception in target notification, capture stop, route release, lease release, playback completion, or diagnostics cannot skip any remaining terminal effect
- [x] 4.4 Cancel pending setup and runtime preparation work when PTT ends, and reject any later acceptance or callback result without starting capture or playing the ready beep
- [x] 4.5 Run terminal cleanup in a host-owned bounded cleanup context that remains available while ordinary service work is cancelled
- [x] 4.6 Make service shutdown await the claimed terminal sequence before runtime closure, capability destruction, audio-resource closure, and final service-scope cancellation
- [x] 4.7 Preserve existing local, SCO, and car endpoint playback/release ordering while applying the unified terminal contract
- [ ] 4.8 Add focused audio-session tests for throwing target callbacks, throwing capture stop, throwing route/lease release, timeout, cancellation propagation, competing terminal signals, service teardown, and exact-once completion

## 5. Host Capability Boundary

- [x] 5.1 Define instance- and runtime-generation-scoped capability acquisition, typed availability, revocable leases, semantic errors, and diagnostics without ambient global access
- [x] 5.2 Implement capability lease revocation and idempotent cleanup for runtime replacement, retirement, timeout, cancellation, and shutdown, rejecting use after revocation without effects
- [x] 5.3 Adapt transcription, synthesis, playback-result creation, Journal storage/derivation, and other current built-in operations behind semantic host ports that expose only host-domain values or opaque handles
- [x] 5.4 Replace channel-visible live PCM arrays, JVM file ownership, concrete controllers, and coroutine jobs with lifecycle-bound opaque audio/storage operations at the runtime boundary
- [x] 5.5 Publish generic `Available`, `Recoverable`, and `Unavailable(reason)` capability state and bind recoverable acquisition to bounded host preparation policy
- [ ] 5.6 Add focused capability tests for undeclared access, cross-instance isolation, generation replacement, revocation races, unavailable resources, normalized failures, cleanup exceptions, and late operations

## 6. Host-Owned Sleepwalker Text Output

- [x] 6.1 Extract one host-owned Sleepwalker text-output service that owns discovery, BLE/GATT setup, connection and reconnect policy, notification/MTU setup, operation serialization, and connection cleanup
- [x] 6.2 Move host-profile validation, keymap lookup, text planning, HID compilation, arm/send/ACK/disarm/kill sequencing, pacing, timeout, and terminal safety cleanup into the text-output service
- [x] 6.3 Implement semantic instance-scoped `sendText` and constrained key-action operations with one operation ID and typed `Delivered`, `Rejected`, `Failed`, or `Indeterminate` terminal outcome
- [x] 6.4 Prevent automatic replay after terminal delivery outcomes, especially when disconnect, timeout, acknowledgement loss, or cancellation makes partial delivery indeterminate
- [x] 6.5 Implement shared bounded preparation that joins or serializes compatible connection attempts and returns only semantic pending, available, failure, or timeout results to runtimes
- [ ] 6.6 Refactor the Keyboard provider/runtime to use its own validated profile, transcription capability, and semantic text-output lease without observing or closing Sleepwalker transport state
- [ ] 6.7 Route SOS/key behavior through the active instance runtime or semantic text-output capability instead of the migrated Keyboard singleton ID
- [ ] 6.8 Add focused text-output and Keyboard tests for multiple profiles/instances, shared connection preparation, no duplicate scans, successful delivery, unrepresentable text, partial-delivery ambiguity, no replay, cancellation, shutdown, and exact-once hardware cleanup

## 7. Provider-Driven Routing and Presentation

- [ ] 7.1 Replace Keyboard kind checks in dispatch and pending-session logic with generic runtime preparation availability and provider-declared capability traits
- [ ] 7.2 Acquire recoverable required capabilities before target commitment and capture, refusing input before the ready beep when acquisition fails, is cancelled, or times out
- [ ] 7.3 Replace dashboard built-in-kind enumeration with registered creatable provider descriptors and repository/domain-generated opaque instance IDs
- [ ] 7.4 Render native configuration forms from provider schemas, validate through the provider before commit, and retain instance IDs through navigation and host-mediated directory selection
- [ ] 7.5 Replace kind-specific card subtitles, unavailable messages, summaries, and configuration navigation with host-rendered provider metadata
- [ ] 7.6 Keep unavailable provider-backed instances visible, ordered, selectable, and PTT-addressable on the phone while retaining generic recovery and management actions
- [ ] 7.7 Keep unavailable instances selectable in Android Auto catalogue order with stable media IDs and actionable unavailable metadata; enforce readiness only when PTT is attempted
- [ ] 7.8 Preserve active selection and committed target identity when provider availability, configuration, status, order, or presentation metadata changes
- [ ] 7.9 Add focused routing and presentation tests for recoverable/non-recoverable providers, native schema forms, unavailable instances, multiple same-provider instances, stable cross-surface order, and provider-neutral dispatch

## 8. Built-In Cutover and Forward Migration

- [ ] 8.1 Run Journal, Debug, and Keyboard exclusively through registered providers, instance-scoped capabilities, generic preparation, the invocation executor, and aggregate runtime projection
- [ ] 8.2 Make Journal recovery and background derivation instance-scoped rather than selecting the first Journal definition, and detach durable derivation from bounded PTT terminal ownership
- [ ] 8.3 Remove shared-controller activation as a readiness source and ensure each built-in runtime reports readiness/status from validated config and semantic host capability state
- [ ] 8.4 Activate catalogue document v2 migration only after all built-in providers and runtime consumers can load the migrated representation
- [ ] 8.5 Remove legacy `Channel`, `JournalChannel`, `DebugChannel`, `KeyboardChannel`, conversion helpers, `loadChannels`, and post-migration legacy active-ID reads
- [ ] 8.6 Remove the production `TEST_FOURTH` kind/config and replace its extensibility proof with a test-only conforming provider outside the production persisted model
- [ ] 8.7 Remove singleton-ID and first-by-kind routing/configuration/resource lookup and verify every action is addressed by stable instance ID and provider reference
- [ ] 8.8 Remove obsolete controller-owned PTT press/release, capture, audio-route, duplicated session state, and cleanup paths after every caller uses host-owned input and capabilities
- [ ] 8.9 Remove exhaustive built-in factory/editor/presentation switches and the unused alternate runtime-definition update path after provider replacement semantics are authoritative
- [ ] 8.10 Verify the v2 file is the sole catalogue source of truth and preserve the pre-migration v1 backup required for operator rollback without dual-writing formats

## 9. End-to-End Verification

- [ ] 9.1 Add composition tests from persisted v1/v2 definitions through provider resolution, registry reconciliation, dispatch, capability acquisition, committed target terminal handling, replacement/removal, and shutdown
- [ ] 9.2 Run the focused JVM test classes covering catalogue/provider migration, invocation bounds, registry leases, terminal cleanup, built-in providers, Sleepwalker text output, routing, dashboard projection, and Android Auto projection
- [ ] 9.3 Build the debug application through the repository devshell and confirm no legacy channel kind, singleton routing, controller-owned capture, or provider callback-under-lock path remains in production code
- [ ] 9.4 Install the debug build on the physical Android device and verify existing Journal, Debug, and Keyboard PTT behavior, per-instance configuration, Sleepwalker connect/reconnect, SOS, cancellation, and disconnect cleanup
- [ ] 9.5 Verify phone and Android Auto add/select/configure/reorder/remove behavior, stable instance identity, unavailable-provider presentation, cross-surface order, and restart persistence after catalogue v2 migration
- [ ] 9.6 Verify service-background operation and teardown leave no active capture, route, capability lease, Sleepwalker operation, runtime callback, or late foreground-service effect
