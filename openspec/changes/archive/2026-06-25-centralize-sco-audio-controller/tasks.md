## 1. ScoAudioController Centralization

- [x] 1.1 Update `ScoAudioController` constructor to accept a `CoroutineScope` for launching the keep-warm job.
- [x] 1.2 Add `activeClients` counter and a `keepWarmJob: Job?` reference to track state.
- [x] 1.3 Modify `acquire()`: increment `activeClients`, cancel `keepWarmJob`, and execute Bluetooth routing logic only on `0 -> 1` transitions (fast return `true` if already active).
- [x] 1.4 Modify `release()`: decrement `activeClients`, and if it reaches `0`, launch a 30-second `delay(30_000)` before clearing the communication device.

## 2. Refactor Decentralized Controllers

- [x] 2.1 Refactor `SttController`: Remove `SCO_WARMUP_MS`, delete `releaseScoAfterWarmup()`, and call `sco.release()` directly in `finally` and `cancelAndRelease()`.
- [x] 2.2 Refactor `TtsController`: Remove local keep-warm timer, simplify `releaseScoAfterWarmup()` to immediately release, and call `sco.release()` synchronously.
- [x] 2.3 Refactor `SttTtsController`: Remove local keep-warm timer, simplify release paths.
- [x] 2.4 Refactor `EchoController`: Remove local keep-warm timer, simplify release paths.
- [x] 2.5 Refactor `JournalPttController`: Remove local keep-warm timer, simplify release paths.

## 3. Integration and Verification

- [x] 3.1 Update `PttForegroundService` to pass its `serviceScope` when initializing `ScoAudioController`.
- [x] 3.2 Build project and verify compilation succeeds.