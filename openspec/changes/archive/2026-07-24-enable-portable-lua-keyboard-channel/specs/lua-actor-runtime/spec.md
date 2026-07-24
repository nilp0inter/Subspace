## ADDED Requirements

### Requirement: Actor broker carries typed keyboard-output requests
The actor kernel SHALL validate Lua keyboard-output arguments and register one bounded typed host-operation request for `send_text` or `send_key` without encoding text, profile, key, JSON, transport data, or operation identity into a yielded label. The request SHALL be bound to the Lua state, runtime generation, execution owner, and one opaque exactly-once claim identity. Kotlin SHALL claim the typed request before capability acquisition or queue admission, and duplicate, foreign, malformed, cancelled, closed, or stale claims SHALL fail before effect.

#### Scenario: Valid text request yields
- **WHEN** an eligible Lua execution calls `send_text` with valid bounded arguments
- **THEN** the kernel SHALL register one typed text request and yield only its opaque request identity
- **AND** the host SHALL obtain text and profile only by exactly-once typed claim

#### Scenario: Request identity is claimed twice
- **WHEN** host code claims a keyboard-output request whose claim was already consumed
- **THEN** the actor boundary SHALL return a typed stale or duplicate result
- **AND** no second capability acquisition, queue admission, output, or Lua resume SHALL occur

#### Scenario: Request belongs to another execution owner
- **WHEN** a completion, cancellation, or claim uses a foreign state, generation, or execution owner
- **THEN** the actor SHALL reject it before Lua entry or host effect
- **AND** the legitimate owner's request SHALL remain unchanged

### Requirement: Keyboard-output suspension and completion are exactly once
A keyboard-output request SHALL suspend only its owning input, SOS, or managed-task coroutine, release the actor execution slot, and race host completion, timeout, owner cancellation, generation revocation, and close through one idempotent terminal gate. While the generation remains live, the winning terminal SHALL resume the owner exactly once with the normalized result. Managed-task cancellation or generation close SHALL discard the coroutine without re-entering Lua and SHALL make every later completion stale.

#### Scenario: Host completes while another coroutine runs
- **WHEN** keyboard output reaches a terminal outcome while another coroutine owns Lua entry
- **THEN** the actor SHALL enqueue the original owner's continuation rather than entering Lua concurrently
- **AND** it SHALL resume only when the actor execution slot becomes available

#### Scenario: Completion races timeout
- **WHEN** host completion and the keyboard-output deadline race
- **THEN** exactly one terminal outcome SHALL win
- **AND** the losing terminal SHALL not resume Lua or authorize another physical effect

#### Scenario: Managed task is cancelled while output may be partial
- **WHEN** a managed task is cancelled after physical output may have begun
- **THEN** the actor SHALL discard the task coroutine without re-entry
- **AND** the host keyboard-output layer SHALL still classify and clean up the physical operation exactly once
