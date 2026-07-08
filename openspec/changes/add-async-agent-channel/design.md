## Context

Subspace already has the ingredients for a spoken agent loop, but they are wired today as diagnostic channel modes rather than a production channel. `PttForegroundService` owns `CaptureService`, Parakeet STT, Supertonic TTS, audio route resolution, active-channel selection, and the foreground-service lifecycle. `SttTtsController` demonstrates the linear path `capture -> transcribe -> synthesize -> play`, while `JournalPttController` demonstrates a channel controller that captures and then performs post-processing off the capture session.

The Agent channel adds a slower, network-bound LLM phase between transcription and synthesis. That phase must not hold `activePttSession`, `CaptureService`, SCO, Telecom call audio, or the main thread, because the user must be able to switch to Journal and record while an agent response is still pending.

## Goals / Non-Goals

**Goals:**

- Add a third built-in channel, `AgentChannel`, visible anywhere channel lists are derived from the channel repository/projection.
- Convert PTT audio into text with the existing Parakeet path, then submit that text to an on-device Python Pydantic AI agent through Chaquopy.
- Make agent LLM processing fully asynchronous relative to capture and channel switching.
- Speak agent responses with Supertonic when the Agent channel is active at response-arrival time or becomes active while a response is pending.
- Preserve Journal and Debug capture behavior while agent jobs are thinking.
- Keep the Kotlin/Python boundary narrow and JSON-compatible so most channel behavior remains testable without Chaquopy.

**Non-Goals:**

- No server-side agent host in V1. The only external service is the LLM provider call made by Pydantic AI.
- No chat UI, transcript history screen, or durable conversation archive in V1.
- No durable pending-response storage across service/app process death in V1; pending responses are foreground-service memory only.
- No multi-agent routing, tool marketplace, MCP integration, RAG index, or document upload support.
- No generic dynamic plugin system for arbitrary channels. The Agent channel is a first-class built-in channel like Journal and Debug.
- No agent audio input in Python. Python receives text after Parakeet transcription.

## Decisions

### D1 - Split capture/STT from agent response work

`AgentPttController` handles PTT press/release only through capture finalization and transcription. Once transcription succeeds, it submits an `AgentJob` and releases the audio/capture route. The LLM request and response synthesis are handled by separate job/playback components.

**Rationale.** `activePttSession` represents ownership of an active PTT capture, not ownership of arbitrary post-capture work. Keeping it alive during an LLM call would block other channels and make Journal unusable while the agent thinks.

**Alternatives considered.** Reusing `SttTtsController` directly would be simpler, but its single pipeline shape couples post-release work to one controller flow and is wrong for a long-running LLM phase.

### D2 - Agent jobs are foreground-service scoped and memory-only in V1

`AgentJobRunner` lives under `PttForegroundService`'s service scope. It exposes job state through `StateFlow` and keeps pending/completed response text in memory until playback marks it heard.

**Rationale.** This is enough to guarantee non-blocking behavior while the service is alive and avoids creating a storage/migration design before the first agent loop works on device.

**Alternatives considered.** Persisting pending text is useful later for backlog reliability, but it expands scope into storage, cleanup, and privacy semantics. Persisting full job state is rejected for V1 because in-flight LLM calls cannot be meaningfully resumed after process death.

### D3 - Playback is active-channel gated and auto-plays one pending response

When an agent response becomes ready, playback starts only if `activeChannelId == AgentChannel.ID` and the audio system is idle. If another channel is active, the response remains pending. When the user switches to the Agent channel, the oldest pending response auto-plays once the audio system is idle. V1 auto-plays one pending response at a time rather than draining the entire inbox.

**Rationale.** This matches the radio model: tune to a channel and hear the next waiting transmission, without hijacking audio for a long backlog.

**Alternatives considered.** Draining all pending responses is more inbox-like but can monopolize playback. Requiring explicit replay after switching back is safer but contradicts the desired auto-speak behavior.

### D4 - Chaquopy is hidden behind a Kotlin `AgentRunner` port

Kotlin calls an `AgentRunner` interface with JSON-compatible request/response models. `ChaquopyAgentRunner` owns Python startup/import and calls one Python bridge function, e.g. `run_agent(request: dict) -> dict`. Pydantic AI setup and provider logic stay inside Python.

**Rationale.** Pydantic AI types, Python exceptions, and Chaquopy APIs should not leak into `PttForegroundService` or channel dispatch. Tests can use fake `AgentRunner` implementations.

**Alternatives considered.** Calling Python modules directly from the service would reduce wrappers but makes lifecycle, error mapping, and tests brittle.

### D5 - Feasibility spike gates Chaquopy/OpenRouter wiring

Implementation begins by proving the Android build can package and import `pydantic-ai-slim[openai]` under Chaquopy for `arm64-v8a`, instantiate an agent with Pydantic AI's OpenAI-compatible provider pointed at OpenRouter, run one call, and return JSON-compatible output to Kotlin.

**Rationale.** `pydantic-ai` depends on `pydantic-core`, and the OpenAI-compatible provider path depends on the OpenAI SDK, which depends on `jiter`. Both `pydantic-core` and `jiter` are native/Rust-backed packages that need Chaquopy-compatible Android wheels. Failing this spike should stop the change before invasive channel integration.

**Alternatives considered.** A custom OpenRouter model over `httpx` would avoid `jiter`, but the selected path is to preserve the standard Pydantic AI OpenAI-compatible provider integration. Building the full Kotlin channel first would create integration churn if Chaquopy packaging fails. Server-side agent hosting is explicitly out of scope for this change.

### D6 - Agent readiness is configuration plus runtime capability

The Agent channel is ready only when its provider/model configuration is present, Chaquopy/Python initialization succeeded, the agent runner is available, and the STT/TTS prerequisites needed for the voice loop are available.

**Rationale.** A ready channel must be able to accept a PTT capture and eventually speak a response. If Python cannot import or model credentials are missing, PTT should use the existing not-ready error-beep path.

## Risks / Trade-offs

- [Chaquopy cannot package `pydantic-ai` dependencies] -> Run the feasibility spike first; require compatible `pydantic-core` and `jiter` Android wheels before continuing.
- [APK size grows too much] -> Measure debug/release APK size in the spike; keep ABI restricted to `arm64-v8a` per current project constraints.
- [LLM call latency is long] -> Surface `Thinking` state and keep the job out of capture/session ownership so the rest of the app remains usable.
- [On-device API key is extractable] -> Treat the OpenRouter credential as user/device configuration, avoid hardcoding secrets, and document that V1 has no backend secret boundary.
- [Python process cannot be restarted after fatal initialization issues] -> Model Python availability as channel readiness; recovery requires app process restart if Chaquopy enters an unrecoverable state.
- [Response arrives while audio is busy] -> Keep the response pending and retry playback on active-channel/audio-idle transitions.
- [Agent response playback conflicts with new PTT capture] -> Capture has priority; pending playback waits until no active capture/session is running.

## Migration Plan

1. Run the Chaquopy/Pydantic AI/OpenRouter feasibility spike on `arm64-v8a` before channel model changes depend on it.
2. Add the Agent channel as a new built-in channel after Journal and Debug in stable ordering.
3. Add fakeable Kotlin ports for agent execution and response playback before wiring real Chaquopy.
4. Wire real Chaquopy/Python only behind `ChaquopyAgentRunner`.
5. Keep existing channels unchanged; rollback is removing the Agent channel from the repository/projection and disabling the controller initialization.

## Open Questions

- Which OpenRouter model string is the V1 default, and how should the user configure its OpenRouter API key on device?
- Should message history be passed to Pydantic AI in V1, or should each PTT transcript be a stateless run?
- Should failed agent responses create a spoken error, a silent pending error state, or only a dashboard-visible error?
