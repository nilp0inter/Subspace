## Purpose

Defines public Lua v1 opaque captured/synthesized audio values and semantic transcription, synthesis, and deferred playback operations with typed asynchronous lifecycle behavior.

## Requirements

### Requirement: Audio values are unforgeable, generation-owned, execution-scoped native full-userdata
The Lua environment SHALL represent captured and synthesized audio as private, host-constructed native full-userdata values with locked metatables and no public fields or methods. A value SHALL contain only a Lua-state-local opaque token and kind tag; it SHALL NOT contain raw PCM bytes, filesystem paths, device or route identities, JVM/Kotlin pointers, native pointers, capability leases, or public operation identities. The host SHALL maintain a registry scoped to exactly one Lua state and runtime generation. Each token SHALL map to exactly one host-owned `OpaqueAudioRecording` or `OpaqueSynthesizedAudio` and exactly one execution owner: a `handle_input` invocation or runtime-managed spawned task. A token from a foreign state, instance, generation, or execution owner SHALL be rejected before any host effect.

The host SHALL enforce finite positive limits on one audio artifact's retained bytes and duration plus live registry token count and retained bytes per execution owner, runtime generation, and process. If current registry capacity cannot admit an otherwise valid captured or synthesized artifact, the host SHALL dispose that artifact and fail before userdata delivery with the applicable typed busy or capture failure. If a completed artifact itself exceeds the single-artifact byte or duration bound, the host SHALL dispose it and return a typed host failure without creating a token. Lua allocation limits SHALL NOT substitute for these host-resource limits.

#### Scenario: Audio userdata has a locked metatable and cannot be inspected
- **WHEN** Lua attempts to read or write a field, call a method, or retrieve the real metatable of an audio userdata
- **THEN** the operation SHALL fail with the standard locked-metatable behavior
- **AND** Lua SHALL NOT obtain any underlying token, pointer, path, route, or PCM data

#### Scenario: Audio userdata is rejected by normalization and logging
- **WHEN** a callback returns audio userdata inside a table, passes it to `subspace.log`, or includes it in configuration or an error payload
- **THEN** the normalization boundary SHALL reject the whole payload with `E_INVALID_VALUE`
- **AND** no partial serialization, log entry, or database commit SHALL occur

#### Scenario: Garbage collection does not own host resource lifetime
- **WHEN** audio userdata becomes unreachable in Lua and is collected
- **THEN** collection SHALL NOT release an audio route, close capture, cancel playback, or otherwise operate the host resource

#### Scenario: Foreign audio userdata is rejected
- **WHEN** an operation receives audio userdata owned by a different instance, Lua state, runtime generation, callback invocation, or spawned task
- **THEN** registry resolution SHALL fail before any host effect
- **AND** the operation SHALL return `(nil, {error = "E_INVALID_ARGUMENT", reason = "Foreign audio handle"})`

#### Scenario: Live audio registry capacity is exhausted
- **WHEN** captured-audio injection or synthesis completion would exceed an execution-owner, generation, or process live-token or retained-byte limit
- **THEN** the host SHALL create no userdata token, dispose the rejected host artifact, and return the applicable typed busy or capture failure

#### Scenario: Host audio artifact exceeds individual bound
- **WHEN** a captured or synthesized artifact exceeds the finite per-artifact byte or duration bound
- **THEN** the host SHALL dispose it without registry publication
- **AND** no callback SHALL receive an inspectable, truncated, or partially registered audio value

### Requirement: Captured and synthesized audio handles have strict lifetime, borrow, consume, and dispose rules
Captured audio userdata SHALL be created only for a `handle_input` invocation and remain valid only while that invocation and its runtime generation remain authorized. Synthesized audio userdata SHALL be owned by the authorized execution that called `subspace.synthesis`: either the current `handle_input` invocation or the current runtime-managed spawned task. Audio userdata SHALL NOT be transferable to another callback invocation or spawned task. `subspace.transcription.transcribe` SHALL borrow a captured handle without consuming it. `subspace.playback.schedule` SHALL atomically consume a captured or synthesized handle only after successful queue admission. A consumed handle SHALL return `E_STALE` on every subsequent use. Failed queue admission SHALL leave the handle unconsumed until the owning execution terminates. When an owning callback invocation or spawned task terminates, the host SHALL dispose its unconsumed audio resources and invalidate their tokens. Generation retirement or close SHALL invalidate every remaining token and dispose every unscheduled resource.

#### Scenario: Borrowing captured audio then scheduling playback
- **WHEN** `handle_input` transcribes a captured-audio handle and then schedules that same handle for playback
- **THEN** transcription SHALL complete without invalidating the handle
- **AND** successful queue admission SHALL atomically consume it
- **AND** a subsequent operation on it SHALL return `(nil, {error = "E_STALE"})`

#### Scenario: Failed playback admission leaves handle live
- **WHEN** playback queue admission fails with `E_UNAVAILABLE`
- **THEN** the handle SHALL remain unconsumed and usable by its owning execution until that execution terminates

#### Scenario: Synthesized audio belongs to spawned task
- **WHEN** a runtime-managed spawned task successfully synthesizes audio
- **THEN** the returned userdata SHALL be owned by that task and current generation
- **AND** that task MAY schedule the audio for playback
- **AND** another callback or task SHALL NOT use the handle

#### Scenario: Unconsumed audio is disposed on execution termination
- **WHEN** a `handle_input` callback or runtime-managed spawned task terminates while owning unconsumed audio
- **THEN** the host registry SHALL dispose of that execution's unconsumed audio resources and invalidate their tokens
- **AND** a later use of those tokens SHALL return `(nil, {error = "E_STALE"})`

#### Scenario: Generation close invalidates all audio handles
- **WHEN** a runtime generation retires or closes while any execution owns an audio handle
- **THEN** the host SHALL invalidate every registry mapping in that generation and dispose all unscheduled audio
- **AND** a subsequent attempted use SHALL return `(nil, {error = "E_CLOSED"})`

### Requirement: Three additional public modules expose bounded yielding semantic audio operations
The host SHALL add exactly three semantic-audio modules to the existing stable `subspace` namespace: `subspace.transcription`, `subspace.synthesis`, and `subspace.playback`. Requiring a module SHALL NOT grant capability eligibility. Each function call SHALL validate context, arguments, manifest eligibility, live capability lease, and resource bounds before starting a host effect.

The operations SHALL be exactly:
- `transcription.transcribe(captured_audio)`: accepts one live captured-audio userdata and returns `({text = <bounded valid-UTF-8 string>}, nil)` on success.
- `synthesis.synthesize(params)`: accepts one exact-key table containing nonblank bounded valid-UTF-8 `text`, nonblank bounded BCP-47 `language`, nonblank bounded logical `voice`, and optional finite positive `speed` defaulting to `1.0`; success returns `(synthesized_audio_userdata, nil)`.
- `playback.schedule(audio, options)`: accepts one live captured- or synthesized-audio userdata and one exact-key table containing optional finite nonnegative `delay_seconds` defaulting to `0`; success returns `({status = "scheduled"}, nil)` only after atomic deferred-queue admission and handle consumption.

The host SHALL configure finite positive deadlines for transcription, synthesis, and deferred-queue admission, measured from successful operation admission until terminal host completion. The host SHALL configure a finite maximum `delay_seconds` plus finite queued-entry-count and retained-audio-byte limits per channel instance, per runtime generation, and globally. A delay above the maximum SHALL return `E_INVALID_ARGUMENT`; unavailable queue count or byte capacity SHALL return `E_BUSY`. Validation, eligibility, or capacity rejection SHALL occur before suspension or effect and failed queue admission SHALL leave the audio handle unconsumed.

These operations MAY yield only from the host-managed `handle_input` coroutine or a runtime-managed spawned task. During entry or lazy-module evaluation, the effect-call-during-load guard SHALL fail the complete load without returning a normal pair. After module loading, calls from synchronous event callbacks or unmanaged coroutines SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` before suspension or effect. Every ordinary failure SHALL return `(nil, error_table)` with stable `error` and optional bounded language-neutral `reason`; codes SHALL be exactly `E_INVALID_ARGUMENT`, `E_INVALID_VALUE`, `E_INVALID_CONTEXT`, `E_CAPABILITY_UNDECLARED`, `E_UNAVAILABLE`, `E_BUSY`, `E_TIMEOUT`, `E_CANCELLED`, `E_CLOSED`, `E_STALE`, or `E_HOST_FAILURE`.

#### Scenario: Successful transcription returns text
- **WHEN** an eligible execution transcribes a live captured-audio handle and the host completes within its deadline
- **THEN** the coroutine SHALL resume exactly once with `({text = "hello world"}, nil)`

#### Scenario: Invalid synthesis parameters are rejected
- **WHEN** synthesis receives an unknown key, missing or blank required string, non-BCP-47 language, invalid voice, nonpositive speed, or non-finite speed
- **THEN** it SHALL return `E_INVALID_ARGUMENT` without suspension, host synthesis, or audio allocation

#### Scenario: Undeclared capability is denied
- **WHEN** a package that did not declare `audio.synthesis` calls synthesis
- **THEN** the call SHALL return `E_CAPABILITY_UNDECLARED` before capability acquisition or host effect

#### Scenario: Module evaluation attempts audio operation
- **WHEN** entry or lazy-module top-level evaluation calls a semantic audio function
- **THEN** the module-load effect guard SHALL fail and discard the complete evaluation without returning a normal error pair

#### Scenario: Loaded callback uses invalid operation context
- **WHEN** `handle_readiness` or another ineligible loaded callback calls a semantic audio function
- **THEN** the call SHALL return `E_INVALID_CONTEXT` without suspending, failing the callback by itself, or creating a host operation

#### Scenario: Playback request exceeds delay bound
- **WHEN** `delay_seconds` exceeds the finite host maximum
- **THEN** scheduling SHALL return `E_INVALID_ARGUMENT` and leave the audio handle unconsumed

#### Scenario: Playback queue lacks bounded capacity
- **WHEN** admitting an entry would exceed any applicable entry-count or retained-audio-byte bound
- **THEN** scheduling SHALL return `E_BUSY`, leave the audio handle unconsumed, and create no partial queue entry

### Requirement: Audio operations resolve exactly once under execution-owner and generation authorization
Each admitted semantic audio operation SHALL carry a host actor-operation token, current execution-owner identity, and current generation capability identity. Yielding SHALL suspend only the owning coroutine and release the serialized adapter slot. Success, typed host failure, finite operation deadline, explicit live-input cancellation, spawned-task cancellation, or generation revocation SHALL race through one atomic terminal gate. Completion MAY resume Lua only while the actor, execution owner, applicable audio token, capability lease, and generation remain current.

When an operation deadline expires, the host SHALL atomically win with `E_TIMEOUT`, cancel or detach backend work, and resume the still-live owning execution exactly once. A timed-out transcription borrow SHALL leave its captured handle valid for the rest of the owning execution; timed-out synthesis SHALL create no audio token; timed-out queue admission SHALL create no queue entry and leave its input audio handle unconsumed. Late backend completion SHALL be stale and SHALL NOT resume Lua or create an effect.

Explicit cancellation of a live input invocation SHALL resume its pending operation exactly once with `E_CANCELLED` when bounded terminal delivery remains possible. Cancelling a spawned-task owner SHALL cancel its pending operations, discard its suspended coroutine without re-entry, and dispose its unconsumed audio. Generation replacement or close SHALL cancel all pending operations, discard every suspended coroutine without re-entering Lua, invalidate audio tokens, revoke capability leases, remove generation-authorized queued playback, and suppress every late effect.

When a resumed `handle_input` callback yields another authorized semantic operation before terminating (for example, synthesis followed by playback scheduling in TTS, or transcription then synthesis then playback scheduling in STT_TTS), the host SHALL dispatch each successive chained yield under the same input invocation owner until the callback terminates, fails, or is cancelled. Each chained yield SHALL be a distinct semantic operation carrying its own actor-operation token and atomic terminal gate; an earlier operation's claimed terminal gate SHALL NOT prevent a later chained operation from claiming its own gate, resuming Lua, or reaching its own terminal outcome.

#### Scenario: Yielding operation releases execution slot
- **WHEN** an authorized execution yields in a semantic audio operation
- **THEN** the host SHALL release the actor execution gate while retaining the owning execution identity
- **AND** unrelated admitted actor work MAY enter the Lua state while the operation is pending

#### Scenario: Operation deadline expires
- **WHEN** transcription, synthesis, or queue admission does not complete before its finite deadline and the execution owner remains live
- **THEN** the owning coroutine SHALL resume exactly once with `(nil, {error = "E_TIMEOUT"})`
- **AND** late completion SHALL be rejected without resource creation, queue admission, Lua re-entry, or another result

#### Scenario: Live input cancellation resumes E_CANCELLED
- **WHEN** a live generation cancels its PTT input while `handle_input` is suspended in an audio operation
- **THEN** the host SHALL resume that callback exactly once with `(nil, {error = "E_CANCELLED"})`
- **AND** the callback MAY perform bounded cleanup and return its terminal result

#### Scenario: Spawned task cancellation discards suspended execution
- **WHEN** a runtime-managed spawned task is cancelled while its audio operation is pending
- **THEN** the host SHALL cancel the operation, discard the task coroutine without re-entering Lua, and dispose the task's unconsumed audio
- **AND** late completion SHALL NOT create audio, resume Lua, enqueue playback, or publish another effect

#### Scenario: Generation close suppresses all late effects
- **WHEN** a generation retires or closes while any execution is yielded in a semantic audio operation
- **THEN** the host SHALL discard the suspended execution, revoke related operation, capability, audio, and queue state, and SHALL NOT resume Lua
- **AND** later completion SHALL be rejected as stale without audio creation, playback, status, or log publication

#### Scenario: Chained semantic operations within one input invocation
- **WHEN** a `handle_input` callback yields a semantic operation, is resumed with its result, and then yields a further authorized semantic operation before returning
- **THEN** the host SHALL dispatch the further operation under the same input invocation owner without requiring a new PTT capture
- **AND** the further operation SHALL claim and complete its own terminal gate independently of the earlier operation's gate
- **AND** the callback SHALL terminate exactly once after the final chained operation resolves
