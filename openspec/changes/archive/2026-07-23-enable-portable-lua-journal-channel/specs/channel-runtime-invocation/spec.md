## ADDED Requirements

### Requirement: Input-deferred work commits after terminal success
The runtime invocation boundary SHALL associate every `runtime.defer` reservation with the exact committed input invocation that created it. Deferred code SHALL not enter Lua, acquire a host capability, start a storage/audio operation, or become visible as a managed task before that input returns exact success and the boundary commits its terminal SUCCESS. After commit, the boundary SHALL admit the function as a new generation-owned task outside the input callback. Any failure, malformed return, throw, cancellation, timeout, replacement, or shutdown that wins before terminal success SHALL discard all reservations for that input.

#### Scenario: Successful input starts deferred work afterward
- **WHEN** an input callback reserves a deferred function and returns exact success
- **THEN** the boundary SHALL commit input SUCCESS before allowing the deferred task to run
- **AND** the task SHALL no longer occupy the committed input callback invocation

#### Scenario: Input is cancelled after reservation
- **WHEN** cancellation wins before exact terminal success
- **THEN** the boundary SHALL discard the deferred reservation without executing its function
- **AND** cancellation SHALL complete exactly once

### Requirement: Deferred work participates in predecessor draining
Once released after input success, a deferred task SHALL be an ordinary descendant of its runtime generation. Replacement or shutdown SHALL stop new task/request admission, cancel or drain released deferred tasks under the configured close bound, revoke their capability/resource authority, close the actor, and only then publish a successor. An unreleased reservation SHALL be discarded. Durable files committed by a package before cancellation MAY remain for successor recovery, but volatile closure/task/request state SHALL not cross generations.

#### Scenario: Replacement begins during deferred derivation
- **WHEN** a released deferred task is suspended in a host operation and replacement retires its generation
- **THEN** predecessor close SHALL cancel/discard the task and suppress its late completion
- **AND** the successor SHALL receive no predecessor closure, coroutine, request token, or audio handle

### Requirement: Typed host-operation waits remain distinct from active callback execution
A filesystem, audio-file, or existing semantic-audio request yielded by an input or managed task SHALL release the serialized adapter/Lua execution slot. Its finite operation wait deadline SHALL not be charged as active Lua execution time. Resumption SHALL reenter only the owning current execution under the normal active slice budget. Request payload size or remote-provider latency SHALL not cause an unbounded invocation queue or main-thread wait.

#### Scenario: Document provider is slow
- **WHEN** a filesystem request remains pending while another actor event is ready
- **THEN** the suspended owner SHALL not retain the Lua execution slot or Android main thread
- **AND** the operation deadline SHALL terminate the wait with one typed outcome if it does not complete
