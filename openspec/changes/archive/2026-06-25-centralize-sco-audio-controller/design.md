## Context

Currently, the `ScoRoute` interface provides raw access to `audioManager.setCommunicationDevice()` and `clearCommunicationDevice()`. Because multiple audio controllers (`SttController`, `TtsController`, `JournalPttController`) need the SCO route to stay open between PTT sessions to avoid slow Bluetooth negotiation times, they each implement their own 30-second `delay` before calling `sco.release()`. 

Since `ScoAudioController` blindly closes the route whenever `release()` is called, these overlapping decentralized timers cause race conditions. If Controller A's timer expires while Controller B is actively using the route, Controller B's audio path is severed mid-flight. Furthermore, `SystemAnnouncer` immediately calls `release()` after every sound, causing navigation sounds in Control Mode to constantly trigger Bluetooth negotiation, resulting in severe audio popping and lag.

## Goals / Non-Goals

**Goals:**
- Eliminate race conditions over the SCO audio route.
- Ensure the SCO route stays open during Control Mode navigation without requiring `SystemAnnouncer` to manage complex timers.
- Simplify all PTT controller implementations by offloading warmup responsibilities to the core audio layer.

**Non-Goals:**
- We are not changing the Bluetooth SPP architecture or how the app connects to the remote device.
- We are not changing the actual duration of the keep-warm timer (it will remain 30 seconds).

## Decisions

### 1. Centralized Reference Counting in ScoAudioController
**Decision:** `ScoAudioController` will implement a thread-safe `activeClients` counter.
**Rationale:** This guarantees that the SCO route is only closed when *all* components are finished with it.
- **`acquire()`**: Increments the `activeClients` count. If it transitions from `0` to `1`, the actual Bluetooth device acquisition occurs. If a keep-warm timer is currently running, it is cancelled immediately.
- **`release()`**: Decrements the `activeClients` count. If it hits `0`, it spawns a coroutine to wait 30 seconds (`SCO_WARMUP_MS`) before physically calling `audioManager.clearCommunicationDevice()`.

### 2. Remove Decentralized Timers
**Decision:** All `delay(30_000)` keep-warm implementations will be deleted from consumer controllers (`SttController`, `TtsController`, `SttTtsController`, `EchoController`, `JournalPttController`).
**Rationale:** The consumers no longer need to care about link persistence. They will simply call `try { sco.acquire(); ... } finally { sco.release() }`.

## Risks / Trade-offs

- **Risk:** Coroutine scope leaks. If `ScoAudioController` spawns the 30-second `keepWarmJob`, it needs a proper `CoroutineScope` that survives controller lifecycles but is still tied to the overall foreground service.
- **Mitigation:** `ScoAudioController` will be updated to accept a `CoroutineScope` (e.g., the service scope) to launch its keep-warm job, or it can implement its own structured concurrency scope.