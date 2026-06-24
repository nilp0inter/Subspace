## Why

Currently, SCO warmup (keep-open) logic is decentralized and scattered across multiple audio controllers (`SttController`, `TtsController`, `JournalPttController`, etc.). Because `ScoAudioController` blindly closes the route whenever `release()` is called, any controller's 30-second timer expiration will kill the SCO connection for all other active controllers. This causes severe audio popping and slow cold-start negotiation delays, especially during fast interactions like RSM Control Mode navigation where `SystemAnnouncer` immediately releases SCO after every beep.

## What Changes

- **BREAKING**: Decentralized `delay(30_000)` keep-warm calls will be completely removed from all individual PTT and system audio controllers.
- `ScoAudioController` will be refactored to implement centralized reference counting (`acquire()` increments, `release()` decrements).
- `ScoAudioController` will internally manage a single 30-second keep-warm timer. The timer will only start when the active client count reaches 0, and will be cancelled if a new client acquires the route.
- Individual controllers will simply call `acquire()` before working and `release()` immediately after, safely relying on the central controller to manage the physical route lifecycle.

## Capabilities

### New Capabilities

### Modified Capabilities
- `sco-audio`: Update SCO lifecycle requirements to specify centralized reference counting and a unified keep-warm timer, rather than decentralized session timers.

## Impact

- All classes implementing or using `ScoRoute` (`SttController`, `TtsController`, `SttTtsController`, `EchoController`, `JournalPttController`, `SystemAnnouncer`) will be simplified.
- Audio popping and lag during rapid navigation (e.g., using Volume Up/Down in Control Mode) will be eliminated because the SCO route will remain continuously open across sequential announcements.
- Race conditions between overlapping PTT sessions on different channels will be resolved.