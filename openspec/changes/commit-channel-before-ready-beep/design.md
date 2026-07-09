## Context

The `state-gated-audio-route-transitions` change improved route-state handling, but physical car testing exposed a separate contract gap. On-the-road Telecom call lifecycle can work while no audio reaches the selected channel: the VU meter remains flat and Journal creates no entry. The ready beep is the user's trusted signal to speak, so it must mean the selected channel has accepted the PTT input and post-beep audio will reach that channel.

Current hazards:

- `CaptureService` plays the ready beep before `source.open()` and before a started capture session is handed to the selected channel.
- `TelecomCapturePcmOutput` delegates to the same `PcmOutput` shape used by SCO output; `AndroidPcmOutput` hard-requires `AudioManager.communicationDevice` to be a Bluetooth SCO device before beep playback. That is correct for Work/RSM, but not a reliable hard proof for Telecom/call-routed car audio.
- `ChannelRouter.onInputStarted()` returns `Unit`, and channel controllers such as Journal can silently return without preparing a writer or input record.
- Problem/error beeps exist in several failure paths, but there is no explicit spec-level distinction between ready beep and problem beep.

The existing architectural boundary remains correct: the audio input subsystem owns route, capture, and beep orchestration; channels consume only channel-level input events and audio.

## Goals / Non-Goals

**Goals:**

- Make ready beep a strict commit signal: post-beep audio from that PTT reaches the selected channel.
- Make problem beep the user-visible signal for a PTT that will not reach the selected channel.
- Add input-subsystem-owned channel input commitment before ready beep.
- Prevent channel controllers from silently dropping a committed input start.
- Make On-the-road ready beep play through the active Telecom/call audio path without treating `AudioManager.communicationDevice` as the sole hard proof.
- Preserve channel isolation from Android route facts, Telecom state, SCO, output objects, and capture sources.
- Add tests that assert ready/problem beep ordering, channel commitment/refusal, route cleanup, and live-channel evidence.

**Non-Goals:**

- No Journal, STT, TTS, keyboard HID, or debug-channel pipeline redesign beyond exposing/using input acceptance and eliminating silent refusal.
- No route-state or beep ownership inside channel controllers.
- No UI layout, Bluetooth pairing, Android Auto browser, release signing, Gradle/Nix, SDK target, or persisted channel config change.
- No hidden Android APIs or reflection.
- No attempt to infer semantic speech content; silence can remain a valid recording after the channel is committed and the ready beep has played.

## Decisions

### Decision 1: Split input setup into pre-commit and committed phases

The input subsystem should explicitly distinguish:

1. candidate PTT request;
2. selected-channel acceptance;
3. route/capture readiness;
4. ready beep;
5. committed post-beep delivery;
6. release/cancellation/failure cleanup.

A PTT is not committed until the selected channel can accept input and the input subsystem can deliver post-beep audio through the channel input contract. Any pre-commit failure plays the problem beep and does not deliver ready beep.

Alternative considered: keep `ChannelRouter.onInputStarted()` fire-and-forget and rely on channels to no-op when not ready. Rejected because it makes ready beep untrustworthy and reproduces the missing-Journal-entry failure.

### Decision 2: Add channel input acceptance without exposing route state

Introduce an input-subsystem-facing acceptance/commit step for the selected channel. The channel may expose only domain readiness and a session-local commitment token or target, such as prepared Journal paths/writer, selected Debug controller/mode, or selected Keyboard controller. It SHALL NOT receive Android route, Telecom, SCO, `PcmOutput`, `CaptureSource`, `ResolvedAudioRoute`, or route-gate details.

The commit result should be structured:

- accepted, with a session-local channel target used for start/release/cancel/fail;
- refused, with a problem-beep reason;
- unavailable, when the controller is missing or not initialized.

Alternative considered: add route/capture facts to channel input sessions so channels can decide whether to accept. Rejected because it violates the route/channel boundary.

### Decision 3: Ready beep belongs after channel acceptance and route/capture preflight

The ready beep should be played only after:

- selected channel acceptance succeeds;
- route/call readiness succeeds;
- capture source can be opened or pre-armed sufficiently to prove setup will not fail immediately;
- PTT remains held;
- no stale-session or wrong-source guard has invalidated the session.

If capture must be opened before the ready beep to prove readiness, the input subsystem may discard or suppress pre-beep frames and begin channel-visible delivery only after ready beep completes. Channels should not be responsible for filtering beep frames.

Alternative considered: keep existing capture-service order `ready beep -> source.open()`. Rejected because ready beep can be heard even though source open or channel handoff fails.

### Decision 4: On-the-road ready beep is Telecom/call-routed

For On-the-road, Telecom call route or call endpoint state is the primary authority that call media can flow to the car. `AudioManager.communicationDevice` may be useful as diagnostic or as a preferred-device hint when it exposes a matching SCO device, but it SHALL NOT be the sole hard proof for car ready beep.

The car ready beep output should:

- require active acceptable Telecom car route/endpoint state;
- use communication/call audio attributes so Android routes the tone through the call path;
- avoid hard failure solely because `AudioManager.communicationDevice` is null or not a matching SCO device when Telecom route state is otherwise valid;
- fail closed if beep playback itself cannot be started/completed.

Alternative considered: make ready beep optional or fall back to local/media output. Rejected because the user relies on the ready beep as the only trustworthy in-car signal that the channel is open.

### Decision 5: Problem beep covers every pre-commit no-channel outcome

The problem beep should be emitted for user-visible PTT attempts that cannot be committed to the selected channel before ready beep. Examples:

- route gate failure or timeout;
- Telecom route timeout or abort;
- car HFP/Telecom readiness conflict;
- capture preflight/open failure;
- selected channel controller missing or unavailable;
- selected channel refuses input due to configuration/readiness;
- stale session or wrong source invalidates setup before commit.

Problem beep may use the best safe route available for the failure state. It does not promise channel delivery.

Alternative considered: only send channel failure/cancellation events. Rejected because in-car users may not see UI state, and problem beep is the intended user feedback.

### Decision 6: Session targets are immutable after commitment

Once a PTT chooses a channel target, start/release/cancel/fail should go to that same session target, not re-read mutable active channel or debug mode on release. This preserves the invariant that post-beep audio reaches the selected target and prevents route/session cleanup from depending on later UI changes.

Alternative considered: continue switching by current app state at start/release time. Rejected because mode/channel changes during active PTT can route start and release to different consumers.

## Risks / Trade-offs

- Opening capture before ready beep can capture the beep. → The input subsystem must discard/suppress pre-beep frames and start channel-visible delivery after the ready beep completes.
- Telecom/call endpoint APIs vary by Android version. → Use `CallAudioState` as the baseline, prefer call endpoint callbacks when available, and keep `AudioManager.communicationDevice` as diagnostic/hint rather than sole proof.
- Channel acceptance adds a new internal contract. → Keep it narrow: accept/refuse and session-local target only; no route facts.
- Problem beep playback may fail in the same route failure that caused rejection. → Treat problem beep as best-effort user feedback, but ready beep remains mandatory for committed capture.
- Tests must distinguish ready beep, problem beep, route readiness, channel commitment, and live PCM. → Add explicit event-order tests instead of only checking final router events.

## Migration Plan

1. Add tests for ready/problem beep ordering and pre-commit failure paths.
2. Add tests for channel acceptance/refusal and immutable session channel target.
3. Add a channel commitment abstraction inside the input subsystem and migrate Journal/Keyboard/Debug routing to accept/refuse rather than silently returning.
4. Update `PttAudioSessionManager` / setup sequencing so ready beep is emitted only after channel acceptance and route/capture preflight succeed.
5. Add a Telecom-aware car beep/output path that routes through the active call path and does not hard-require `AudioManager.communicationDevice` as sole proof.
6. Preserve problem beep for all pre-commit no-channel outcomes.
7. Add live PCM/channel-entry evidence tests and targeted manual car validation.

Rollback strategy: revert the channel commitment and Telecom-aware ready-beep changes, returning to the prior route-gated capture flow. No data migration is required.
