## Context

Subspace relies on `PttForegroundService` to handle `B02PTT-FF01` hardware inputs via the `SppClient`. Currently, `ButtonStateMachine` tracks physical transitions (like `Group` putting the hardware into `ControlMode`), but the service event loop simply drops subsequent `VolumeUpClicked` and `VolumeDownClicked` actions. To realize a voice-first interaction model, we need to map these hardware inputs to manipulate the `activeChannelId` and provide immediate auditory feedback to the user via the headset.

## Goals / Non-Goals

**Goals:**
- Connect physical `Group`, `VolumeUp`, `VolumeDown`, and `PTT` events to a flat system navigation menu.
- Provide zero-latency auditory feedback for navigation actions over the SCO headset route.
- Ensure the Android Compose UI remains perfectly synchronized with hardware-driven state changes.

**Non-Goals:**
- Introducing external network-based TTS or audio generation.
- Altering the functional behavior of existing channels (Journal, Debug).
- Building an extensible N-depth menu tree (we are only navigating the root channel list).

## Decisions

- **Decision: Memoized Voice-First Registry (`SystemAnnouncer`)**
  Instead of synthesizing TTS phrases dynamically on every button press (which takes ~300-500ms), we will construct a `SystemAnnouncer` abstraction. On service start, it will proactively synthesize a static vocabulary matrix (e.g. "Channels", "Journal Channel", "Journal Channel Selected") using `SupertonicJniSynthesizer`, pre-resample them to 16kHz/8kHz via `TtsAudio.toScoPlayback`, and cache them as `RecordedPcm` arrays in memory.
  - *Rationale*: Instant feedback is critical for an interface operated blindly. Trading ~250KB of heap allocation for a zero-latency UX is an optimal time-space tradeoff.
  - *Alternatives*: Tones/beeps (poor scalability and clarity); Real-time inference (noticeable lag on every button press).

- **Decision: Aggressive Audio Interruption**
  The `SystemAnnouncer.announce(key)` method will cancel any currently executing playback coroutine before playing the new phrase.
  - *Rationale*: Audio feedback must remain perfectly synchronized with physical inputs. If a user clicks Volume Up three times quickly, they should hear the third channel name immediately without waiting for the first two phrases to finish queuing.

- **Decision: Centralized Unidirectional Control Flow**
  Hardware inputs will calculate the adjacent channel and invoke the existing `setActiveChannelId()` mutator.
  - *Rationale*: Because the Compose UI (`MainDashboardScreen`) observes the `AppState` flow, mutating `activeChannelId` inherently synchronizes the screen. Scrolling on the hardware is functionally identical to tapping the UI.

## Risks / Trade-offs

- **Risk**: The user begins clicking buttons immediately on startup before the async Rust TTS model is ready and pre-computation finishes.
  - **Mitigation**: The `SystemAnnouncer` will gracefully fall back to calling `AndroidPcmOutput.playReadyBeep()` on cache misses, guaranteeing that navigation is always audibly confirmed, even if the fidelity is temporarily degraded.
- **Risk**: Fast audio interruption leads to stranded `ScoRoute` locks.
  - **Mitigation**: The announcer's playback coroutine will strictly utilize `finally { sco.release() }` blocks. Coroutine cancellation will safely drop the lock.
