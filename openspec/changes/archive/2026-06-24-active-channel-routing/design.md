## Context

Subspace currently uses independent boolean toggles (`enabled`) to activate or deactivate communication channels. In the PTT routing logic, these channels have an implicit, hardcoded priority (e.g. Captain's Log > Debug Channel). Furthermore, configuration controls (like selecting a directory) live directly on the channel cards in the main dashboard, which clutters the UI and conflates configuration with activation.

We need a physical "radio-like" mental model:
1. Channels are mutually exclusive ("turning the dial").
2. Selecting a channel makes it active, but not necessarily "ready" to broadcast.
3. Attempting to broadcast (PTT) on a not-ready active channel emits a characteristic hardware beep, simulating physical radio feedback.

## Goals / Non-Goals

**Goals:**
- Centralize active channel state to a mutually exclusive property.
- Separate configuration concerns from the main activation UI via dedicated screens.
- Provide a robust readiness check on channels to safely gate PTT routing.
- Provide immediate, physical-style audio feedback (two-tone beep) over the SCO headset when PTT fails due to readiness.

**Non-Goals:**
- We are *not* implementing the physical Group button behavior (changing hardware modes). PTT remains the sole physical trigger evaluated here.
- We are not changing the underlying behavior of the Captain's Log or Debug channels (e.g., how STT/TTS operate), only how they are selected and configured.

## Decisions

**1. Active State Management**
- *Decision*: Introduce `activeChannelId: String` in `AppState` and remove `enabled` from `Channel`.
- *Rationale*: A single ID enforces mutual exclusivity by design, removing the need for priority checks in the routing logic.
- *Alternatives*: Keep booleans and enforce mutual exclusivity via state reducers, but this is error-prone.

**2. Readiness Derivation**
- *Decision*: Expose `isReady` on channels. `CaptainsLogChannel.isReady` checks if `baseDirectory != null && (saveVoice || saveText)`. `DebugChannel.isReady` is hardcoded to `true`.
- *Rationale*: Channels know best what constitutes their readiness. Deriving this prevents invalid PTT operations.

**3. Two-Tone Error Feedback**
- *Decision*: Add `playErrorBeep(coldStart: Boolean)` to `PcmOutput` (`AndroidAudio`), generating a descending two-tone sequence (e.g., 400Hz followed by 300Hz for 150ms each) using the existing sine wave generator.
- *Rationale*: Gives immediate auditory feedback directly mirroring a physical radio error.
- *Alternatives*: Play a pre-recorded WAV file, but generating it in-memory using `generateSinePcm16` avoids adding new assets and stays consistent with the ready-beep approach.

**4. SCO Routing for Error Tone**
- *Decision*: The error beep must be played over the headset, requiring an SCO link acquisition if it's a cold start.
- *Rationale*: Feedback must reach the user where they are listening. Failing to acquire SCO would result in the phone speaker blaring, violating stealth/headset expectations.

## Risks / Trade-offs

- **[Risk] SCO Acquisition Latency for Errors** -> *Mitigation*: The `playErrorBeep` will accept `coldStart` to handle the SCO warmup delay (just like the ready beep), ensuring the tone isn't cut off.
- **[Risk] UI Navigation State Loss** -> *Mitigation*: Compose Navigation will manage the new Config screens, ensuring that when the user taps "Back", the state changes (like directory selection) are preserved in the ViewModel/AppState.
