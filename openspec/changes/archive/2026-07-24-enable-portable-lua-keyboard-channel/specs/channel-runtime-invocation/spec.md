## ADDED Requirements

### Requirement: Recoverable preparation completes before input commitment
When cached Lua readiness is false with a valid nonempty preparation request, the runtime invocation boundary SHALL execute registered capability preparers under the current `PREPARE_INPUT` generation gate before returning input acceptance. It SHALL apply a finite host policy deadline, propagate release/cancellation/replacement/shutdown, refresh readiness once after successful preparation, and accept input only when refreshed readiness is true. The host SHALL NOT commit an input target, play the ready beep, or start capture while preparation is pending or unsuccessful.

#### Scenario: Preparation establishes readiness
- **WHEN** current bounded preparation succeeds and refreshed Lua readiness returns true
- **THEN** `PREPARE_INPUT` SHALL return an accepted target bound to that generation
- **AND** ordinary route preflight, ready beep, and capture MAY proceed afterward

#### Scenario: Preparation fails or times out
- **WHEN** any requested preparer fails, times out, is cancelled, or completes stale
- **THEN** `PREPARE_INPUT` SHALL return a typed refusal or unavailability
- **AND** no accepted target, ready beep, capture, or later effect SHALL be produced for that attempt

#### Scenario: Release races preparation
- **WHEN** PTT release or cancellation wins while preparation is suspended
- **THEN** the input attempt SHALL remain terminal and uncommitted
- **AND** later preparation or readiness completion SHALL be suppressed

### Requirement: SOS supports bounded yielded operations
The `HANDLE_SOS` invocation phase SHALL support a host-managed Lua SOS coroutine that may yield authorized opaque operations, release the serialized Lua execution slot while suspended, and resume under the same generation gate. SOS SHALL have a finite phase/operation deadline, exact terminal success or contained failure, and idempotent cancellation. It SHALL NOT hold runtime-registry or catalogue locks, execute on the Android main thread, or admit spawn/defer work.

#### Scenario: SOS yields for keyboard output
- **WHEN** a Lua SOS callback yields an authorized keyboard-output request
- **THEN** the invocation boundary SHALL release its execution slot while the host operation is pending
- **AND** it SHALL resume the SOS owner exactly once when the terminal result wins and the generation remains live

#### Scenario: Generation closes during yielded SOS
- **WHEN** close, replacement, or service shutdown revokes the generation while SOS is suspended
- **THEN** the boundary SHALL cancel or detach the operation according to its effect state and discard predecessor continuation
- **AND** late completion SHALL not re-enter Lua, emit another key, or mutate a successor

### Requirement: Selection changes do not cancel generation-authorized keyboard output
The invocation boundary SHALL NOT treat active-channel selection changes as cancellation or revocation of keyboard-output work owned by a live generation. It SHALL continue to gate results by execution owner and generation. Only explicit owner cancellation or generation retirement SHALL invalidate the operation.

#### Scenario: Selection changes during managed-task output
- **WHEN** a managed task is suspended in keyboard output and another channel becomes selected
- **THEN** the original operation SHALL remain pending under its existing generation gate
- **AND** its terminal result SHALL not be published to the selected channel
