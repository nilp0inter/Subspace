## Why

Subspace's channel model is meant to let a PTT capture drive programmable voice tools, but the current channel set stops at journal capture and diagnostic STT/TTS loops. An on-device Pydantic AI agent channel turns a spoken PTT capture into an asynchronous assistant response while preserving the core radio invariant: the user must be able to switch channels and record elsewhere while the agent is still thinking.

## What Changes

- Add a new Agent channel that accepts PTT audio, transcribes it with the existing Parakeet STT path, and submits the transcript to a Pydantic AI agent hosted in embedded Python through Chaquopy.
- Add an asynchronous agent job lifecycle so LLM processing never holds the capture session, SCO route, UI thread, or active PTT session after transcript submission.
- Add an in-memory pending response inbox for the Agent channel. When a response becomes ready while the Agent channel is active, Supertonic speaks it automatically; when another channel is active, the response remains pending and auto-plays when the Agent channel becomes active.
- Add spoken-only V1 responses through the existing Supertonic TTS path. The agent response is not displayed as a chat UI in this change.
- Add a Chaquopy/Pydantic AI feasibility spike as an explicit implementation gate before wiring the full channel, because `pydantic-ai` and its OpenAI-compatible OpenRouter path depend on native Python packages whose Android wheel support must be proven.
- Keep existing Journal and Debug channel behavior available while agent jobs are thinking.

## Capabilities

### New Capabilities
- `agent-channel`: Defines the Agent channel behavior: PTT-to-transcript input, asynchronous on-device Pydantic AI execution through Chaquopy, pending response handling, and Supertonic spoken playback.

### Modified Capabilities
- `channel-framework`: Extends the channel model and active-channel selection contract to include the Agent channel and its pending-response readiness/status.
- `channel-routing`: Extends PTT routing so the Agent channel can receive captures without blocking other channels after transcript submission.

## Impact

- Android build: add Chaquopy Gradle plugin/configuration and package Python source/dependencies if the feasibility spike passes.
- Kotlin model/service: extend `Channel`, `AppState`, `ChannelRepository`, channel browse projection, PTT dispatch, active-controller lifecycle, and service cleanup for a third built-in channel.
- Kotlin audio pipeline: introduce an `AgentPttController`, agent job runner, pending response inbox, and response playback coordinator around existing `CaptureService`, `PcmTranscriber`/Parakeet, `TtsSynthesizer`/Supertonic, and `PcmOutput` ports.
- Python runtime: add a narrow Chaquopy bridge module exposing JSON-compatible request/response calls around a Pydantic AI agent.
- Tests: add unit coverage for dispatch, non-blocking async job behavior, pending response auto-play rules, failure handling, and Chaquopy bridge boundary fakes; add manual device acceptance for switching to Journal while an agent job is still processing.
- Dependencies/security: requires network permission and OpenRouter API key configuration for the LLM call; V1 uses Pydantic AI's OpenAI-compatible provider path pointed at OpenRouter and does not introduce a backend or external service other than the OpenRouter call.
