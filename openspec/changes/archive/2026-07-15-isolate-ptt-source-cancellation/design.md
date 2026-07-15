## Context

`PttAudioSessionManager` owns one pending or active input session and already claims terminal ownership atomically. Normal release is source-checked, but several asynchronous service callbacks bypass that ownership boundary through `PttDispatcher.forceReleaseActivePtt()`, whose contract explicitly cancels the current session regardless of source.

The damaging path is RSM SPP recovery. Service startup enables monitoring for a bonded `B02PTT-FF01`; when the RSM is powered off, `SppClient.events()` repeatedly ends after `BluetoothSocket.connect()` fails. `handleSerialSessionEnded()` then force-releases whichever input session exists. This can cancel Phone capture, abort pending or active CarTelecom input, and make Android Auto return from Playing to Paused. Telecom route timeout and car setup cleanup contain the same class of broad cancellation risk when their callbacks arrive after ownership has moved to another source.

Persisted field logs show the resulting capture starts and route cleanup but cannot attribute the initiating cancellation because serial attempt termination, cancellation disposition, and ordinary terminal claims are not logged. The configured-car selector and exact-device route checks are independent: the observed first-use Android Auto route refusal occurred after successful car resolution and HFP priming and is not solved by changing cancellation ownership.

## Goals / Non-Goals

**Goals:**

- Make every non-global cancellation atomic and conditional on the expected `PttSource`.
- Prevent RSM serial recovery and explicit serial disconnect from terminating Phone or CarTelecom input.
- Prevent late Telecom and car-setup callbacks from terminating Phone or RSM input.
- Keep automatic RSM reconnect serialized and eligible during unrelated input sessions.
- Preserve exactly-once terminal target notification, route release, lease release, active-state clearance, and completion publication.
- Persist enough semantic diagnostics to identify the caller, requested owner, actual owner, terminal claim, and reconnect disposition without hardware identity or content.

**Non-Goals:**

- Changing automatic reconnect eligibility, retry delay, Bluetooth pairing, or the RSM protocol.
- Pausing reconnect attempts whenever non-RSM capture is active.
- Changing configured-car persistence, HFP priming, exact-device Telecom validation, route retry frequency, route timeout, or Android Auto media controls.
- Adding a second car-call attempt after Telecom route timeout.
- Claiming to fix an Android/OEM refusal to activate the configured car route after first selection.
- Changing phone hold/slide-lock gestures, channel terminal behavior, capture duration, transcription, response playback, or audio payload handling.
- Adding permissions, hidden APIs, dependencies, or persisted schema migrations.

## Decisions

### 1. Put the source match and terminal claim in `PttAudioSessionManager`

Add an atomic source-scoped cancellation entry point that accepts the expected `PttSource`, semantic reason, and whether only a pending session is eligible. Under the manager's existing lock it shall inspect the active session, reject absence or mismatch, enforce the pending-only constraint when requested, and claim `TerminalClaim.Cancellation` in the same critical section. Return a typed disposition such as `Accepted`, `NoActiveSession`, `SourceMismatch`, or `NotPending` together with a content-free session snapshot suitable for orchestration and diagnostics.

The dispatcher may expose service-facing wrappers, but it must not decide ownership from `activePttSession` and then call an unconditional manager operation: that check-then-act sequence would allow ownership to change between the snapshot and terminal claim.

Alternatives considered:

- **Check `activePttSession.source` only in each callback:** rejected because it duplicates policy and leaves a time-of-check/time-of-use race.
- **Reuse normal `release(source)`:** rejected because serial loss and route timeout are cancellation, not successful recording release, and must retain existing target-cancellation semantics.
- **Add source checks only to the known RSM callback:** rejected because late Telecom and car-setup callbacks have the same ownership defect class.

### 2. Keep one explicitly named global teardown path

Replace ordinary uses of `forceReleaseActivePtt()` with source-scoped cancellation. Retain an all-source operation with a name and visibility that communicate its narrow purpose, such as `cancelAnyActivePttForServiceTeardown(reason)`. Only whole-service/runtime shutdown, where every session is invalid by definition, may call it.

Caller ownership shall be:

| Lifecycle caller | Expected source | Eligibility |
|---|---|---|
| RSM SPP stream ended or reconnect failed | `Rsm` | pending or active |
| Explicit RSM serial disconnect | `Rsm` | pending or active |
| Car setup failure before capture | `CarTelecom` | pending only |
| Telecom route timeout | `CarTelecom` | pending or active |
| Telecom capture stop | `CarTelecom` | existing normal release |
| Phone gesture release/stop | `Phone` | existing normal release |
| Runtime/service teardown | any | explicit global cancellation |

Alternatives considered:

- **Delete global cancellation entirely:** rejected because service teardown must invalidate any source and join the existing deterministic terminal sequence.
- **Treat explicit serial disconnect as global:** rejected because the action controls the RSM link, not Phone or CarTelecom ownership.

### 3. Separate source-session cancellation from subsystem-local cleanup

A source mismatch prevents audio-session effects, but the callback's own subsystem must still retire stale state:

- Failed or ended SPP attempts still close their socket, publish connection state, and schedule or stop reconnect according to `ReconnectPolicy`.
- Telecom timeout or abort still cancels its route retry/stability callbacks, releases its expected-device reservation, and closes its connection.
- Car setup failure still abandons its operation-owned capture admission and Telecom reservation.

Telecom error feedback and media-state mutation shall occur only when the callback owns the CarTelecom operation it is reporting. A stale timeout must not play a car error beep through a current Phone route or publish Phone input as idle.

For a source-matched active CarTelecom cancellation, Telecom abort remains required after the audio terminal claim. For a mismatch, Telecom may abort only its stale coordinator/connection state; the source-scoped audio request remains rejected.

Alternatives considered:

- **Skip all callback work after a source mismatch:** rejected because stale sockets, retry callbacks, reservations, or connections would leak.
- **Let coordinator abort implicitly cancel audio:** rejected because coordinator callbacks are asynchronous and still require source ownership at the audio boundary.

### 4. Continue RSM reconnect during unrelated input

Do not use the host-audio coordinator to suppress SPP reconnect while Phone or CarTelecom input is active. SPP connection establishment does not own the microphone or SCO route, and source-scoped teardown removes the destructive coupling. Preserving retries allows a powered-on RSM to recover without waiting for unrelated recording or channel processing to finish.

Alternatives considered:

- **Suspend reconnect during every active input session:** rejected because it masks rather than fixes ownership, delays hardware recovery, and leaves explicit disconnect or late callback races unsafe.
- **Stop monitoring after the first powered-off failure:** rejected because it violates existing automatic reconnect requirements.

### 5. Preserve service ownership while unrelated input remains active

Explicit serial disconnect shall clear RSM monitoring and close RSM resources without force-cancelling non-RSM input. Service/foreground ownership cleanup must observe current non-RSM session and other existing owners before calling `stopSelf`; terminal completion may reevaluate idle shutdown afterward. This keeps the source-scoped session alive rather than preserving it in memory only to destroy its service immediately.

Automatic failed reconnect already retains monitoring service ownership and requires no new lifetime policy.

Alternative considered:

- **Keep unconditional `stopSelf` after explicit serial disconnect:** rejected because it would reintroduce global cancellation indirectly through service teardown.

### 6. Log cancellation provenance at the ownership boundary

Use `SubspaceLogger` so events reach both Logcat and the persistent ring. Add stable machine-readable events at these points:

- SPP attempt/session end: automatic/manual, ever connected, monitoring requested, semantic reconnect disposition.
- Source-scoped cancellation request: semantic caller, requested source, current source/phase when present, disposition, and normalized reason.
- Global cancellation request: explicit global caller and current source/phase.
- Terminal claim and completion: session ID, source, claim category, normalized reason, and cleanup failure categories.

Do not log Bluetooth addresses, PCM sizes or payloads, transcripts, channel messages, credentials, or exceptions containing sensitive platform identifiers. Device labels are unnecessary for these ownership events.

Alternatives considered:

- **Log only in `handleSerialSessionEnded`:** rejected because it cannot prove whether terminal ownership was accepted or rejected and misses Telecom races.
- **Infer cancellation from route-release snapshots:** rejected because route release is a downstream cleanup effect, not cancellation provenance.

### 7. Test ownership as concurrency behavior, not callback plumbing

Manager tests shall exercise atomic match/mismatch claims and terminal races. Service/dispatcher tests shall prove each lifecycle callback supplies the correct source and performs subsystem-local cleanup without cross-source audio effects. Reconnect tests shall model a powered-off bonded target by ending an automatic attempt while Phone or CarTelecom owns input. Logging tests shall assert semantic fields and absence of addresses/content.

No test should depend on Android timing alone; the field cadence motivates the cases, while deterministic fakes control callback order.

## Risks / Trade-offs

- **[Risk] A legitimate global invalidation is accidentally converted to source-scoped cancellation.** → Keep one explicit teardown-only API and test runtime/service shutdown for every source.
- **[Risk] A source mismatch preserves audio but leaks stale Telecom or SPP state.** → Separate the typed audio disposition from unconditional subsystem-local cleanup and test both results.
- **[Risk] Telecom abort callbacks recurse into another terminal request.** → Rely on the manager's first-terminal-owner invariant and make repeated source-scoped requests observable no-ops.
- **[Risk] Explicit serial disconnect stops the service behind a preserved Phone or Car session.** → Gate idle service shutdown on non-RSM active ownership and reevaluate after terminal completion.
- **[Risk] Reconnect continues to consume Bluetooth work during capture.** → Preserve the existing five-second serialized retry policy; this change removes cancellation coupling rather than changing retry policy.
- **[Risk] Additional debug events increase ring churn.** → Emit one event per lifecycle transition or terminal claim, not per reconnect poll or audio frame.
- **[Trade-off] The first-selection Android Auto route refusal remains possible.** → Keep it explicit as a separate downstream route-acquisition issue; do not hide it with an unrequested call retry.

## Migration Plan

1. Introduce the typed source-scoped cancellation API and diagnostics while retaining the teardown-only global operation.
2. Migrate RSM serial, car setup, and Telecom lifecycle callers to their declared source ownership.
3. Remove the broad service-facing force-release path after all callers are classified.
4. Preserve existing preferences, configured-car identity, reconnect state, and channel data; no data migration is required.
5. Rollback restores the previous binary behavior without persistent-data changes; new diagnostic lines remain harmless text in the bounded ring.

## Open Questions

None. The change intentionally fixes source ownership and observability without changing Telecom route-acquisition or RSM reconnect policy.
