## Context

The app uses Android's Bluetooth SCO API to route audio between the phone and the B02PTT-FF01 headset. The audio pipeline has a three-stage sequence: SCO acquisition → ready beep → recording. On cold SCO (no prior session), the first PTT press produces an inaudible beep because:

1. Android reports `isActive()` as soon as the communication device is *set* but before the low-level SCO link is streaming PCM packets.
2. The `AudioTrack` created by `AndroidPcmOutput` has no explicit device preference, so it routes through Android's default mixer policy, which may not yet resolve to the SCO endpoint.

The existing 30-second `SCO_WARMUP_MS` retention window masks this on subsequent presses — once the SCO PCM stream is established, all further AudioTrack instances route correctly. The bug only manifests on the very first PTT press after SCO becomes inactive.

Three related changes are grouped: (1) fix cold-start beep reliability, (2) remove the `RecordWhileBeepPlays` timing mode to enforce beep-then-record sequencing, (3) pre-warm SCO on short PTT taps so the first "real" press is always from warm SCO.

## Goals / Non-Goals

**Goals:**
- Ready beep is always audible through the Bluetooth headset, including the first PTT press after cold SCO
- Recording always starts after the ready beep completes (no RecordWhileBeepPlays mode)
- Short PTT taps (release before recording starts) pre-warm the SCO route for 30s
- SCO warmup and release behavior is shared across all controllers

**Non-Goals:**
- No changes to the STT, TTS, or STT↔TTS transcription/synthesis pipeline beyond SCO warmup retention
- No changes to the Captain's Log channel output format or persistence
- No changes to the SPP Bluetooth serial connection or button parsing
- No changes to the max recording duration (60s)
- No changes to the SCO acquisition timeout (5s)

## Decisions

### Decision 1: Cold-start priming via silence buffer + device routing

The beep's reliability has two independent factors that need fixing:

**A. Explicit AudioTrack device routing** — Set `AudioTrack.setPreferredDevice(communicationDevice)` so the audio pipeline bypasses the default mixer policy and targets the SCO endpoint directly. This requires plumbing the `AudioDeviceInfo` through from `ScoAudioController` to `AndroidPcmOutput`.

**B. SCO PCM stream priming** — The SCO link-layer stream is not guaranteed to be ready when the first AudioTrack starts, even with explicit device routing. A short silence buffer (100ms of zeros) sent before the beep waveform establishes the streaming channel. Without this, the first few milliseconds of the beep can be dropped.

Alternatives considered:
- **Delay approach** (`delay(200)` after `acquire()`) — Simpler but adds latency to every cold start; wastes 200ms even if the link is faster.
- **Loopback priming** (play 10ms of silence, poll for audio output callback) — More reliable detection but adds complexity; Android AudioTrack doesn't offer a reliable "first frame rendered" callback for static mode.
- **Pre-prime on app startup** (acquire SCO immediately at service start) — Battery impact; the user may never press PTT.

Chosen approach: Prime with silence + explicit routing. The silence buffer is synthesized inline in `playReadyBeep()` and prepended to the samples array. A `coldStart` flag controls whether priming is applied; it is `true` when SCO was just acquired (was `Inactive` before), `false` when SCO was already warm.

### Decision 2: Remove EchoTimingMode, not EchoTimingMode type

The `EchoTimingMode` enum and its `RecordWhileBeepPlays` variant are removed entirely. The `timingMode` property on `EchoController`, the `setTimingMode()` function, and the UI toggle in `MonitorScreen` are also removed. The `RecordAfterBeep` variant's behavior becomes the single code path — renamed to `startRecording()`.

This is a **BREAKING** change: any external code referencing `EchoTimingMode` (none in the current codebase beyond the controller and the UI) would fail to compile. Since this is a single-app project, this is acceptable.

### Decision 3: SCO warmup on short-tap cancellation (not release)

The `cancelBeforeRecording()` method in each controller currently calls `sco.release()`. This is changed to `sco.keepWarm()` which starts the 30-second warmup timer. The method is renamed to `cancelSession()` to reflect that SCO is not necessarily released.

The warmup timer uses the same `releaseScoAfterWarmup()` mechanism that already exists in Echo/STT/TTS controllers. The `CaptainsLogPttController` does not currently have warmup — it releases SCO immediately after `finishSessionIfNeeded()`. This is changed to keep SCO warm post-session as well.

### Decision 4: `coldStart` flag propagated from ScoAudioController

`ScoAudioController.acquire()` returns a `Boolean` (success/failure). We add a side channel — a new `wasColdStart: Boolean` property or return a sealed result — so callers know whether SCO was just activated (cold) or was already warm. This determines whether `playReadyBeep()` applies the priming silence.

Two approaches:
- **Return a data class** `AcquireResult(success: Boolean, coldStart: Boolean)` — clean but breaks the `ScoRoute` interface contract (`suspend fun acquire(): Boolean`)
- **Read-observable property** `ScoRoute.coldStart: Boolean` that resets on warm reuse — avoids interface breakage

Chosen: Add `coldStart: Boolean` to the `ScoRoute` interface with a default getter returning `false`. `ScoAudioController` overrides it to return `true` when it went through the full acquisition path (not the warm-path shortcut). This is the least invasive change to the interface.

### Decision 5: Captain's Log inherits SCO warmup

`CaptainsLogPttController` currently calls `sco.release()` at the end of every session (both in `finishSessionIfNeeded()` and `cancelAndRelease()`). It has no warmup mechanism of its own. We add `releaseScoAfterWarmup()` to keep SCO warm for 30s after a session, matching the test controllers' behavior.

## Risks / Trade-offs

- **[Risk] Priming adds ~100ms to first-PTT latency** → Mitigation: The priming is imperceptible (100ms) and only applies on cold SCO, not on warm reuse. The total cold-start time is dominated by SCO acquisition (500ms–5s).
- **[Risk] `setPreferredDevice` is available only on API 26+ (minsdk is 31)** → No risk: API 26 is well below our minimum SDK.
- **[Risk] Short-tap warmup keeps SCO active when user intended to abort** → Mitigation: 30s is bounded, and the warmup retains the hard-won SCO link. The user experiences faster response on the next press.
- **[Risk] Removing RecordWhileBeepPlays loses an existing feature** → Accepted: The feature was experimental and never produced reliable recordings. The after-beep path is the canonical workflow.
- **[Risk] Captain's Log warmup doubles SCO retention on the channel path** → Accepted (benign): After a Captain's Log session, SCO is held 30s. If the user switches to a test mode and presses PTT within that window, they get instant warm SCO.
