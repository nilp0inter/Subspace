## Why

Subspace currently completes channel work inside one PTT terminal callback, so it cannot support a durable agent conversation whose remote response may arrive later, accumulate while another channel is selected, invoke configured client-side tools, and play when the user returns. A native OpenAI-compatible agent channel is the highest-ROI way to establish that asynchronous channel model while proving host-owned protocol, capability, message, and tool boundaries that a future Lua runtime can reuse unchanged.

## What Changes

- Add globally configured OpenAI-compatible connection profiles with stable IDs, base URLs, host-owned bearer credentials, shared official OpenAI Java SDK clients, model discovery through the profile's models endpoint, and explicit unavailable/error states.
- Add a provider-backed OpenAI Agent channel whose per-instance configuration selects a global connection profile, selects one discovered model, stores a multiline system prompt, and optionally enables Keyboard tools with a selected host keyboard profile/layout.
- Make the runtime provider registry the authoritative descriptor resolver for catalogue load, migration, add, and update operations so the new provider and future Lua-backed providers are accepted without extending a built-in-only resolver.
- Transcribe each released PTT recording locally, enqueue the resulting user turn, and run serialized OpenAI Chat Completions asynchronously without retaining the PTT audio route or terminal callback.
- Keep one volatile conversation per channel runtime generation. Preserve its context across turns while the service remains alive; reset it when the service/runtime restarts, when relevant channel configuration changes, or when SOS is pressed.
- Persist asynchronous outbound messages, inbound responses, queued turns, terminal run state, pending/heard state, and the minimum active tool-loop envelope needed for exact-once recovery. Persisted messages SHALL NOT automatically become conversation context after restart.
- Process OpenAI tool calls in a bounded host-owned loop. Initially expose only configured Keyboard `type_text` and `press_enter` tools, execute enabled calls automatically without per-call authorization, preserve exact tool-call/result pairing, and never replay ambiguous text-output effects.
- Synthesize final assistant text through the existing semantic synthesis capability (Supertonic initially, replaceable by Kokoro without changing channel behavior) and schedule playback independently of the originating PTT session.
- Play an arriving response immediately when its channel is still selected and the audio subsystem can admit playback; otherwise retain it pending and play it when the user returns to that channel. Serialize queued turns and accumulated responses per channel.
- Add pending-message and processing projection needed by phone and Android Auto surfaces without exposing SDK, Android, audio-route, Bluetooth, or transport objects to channel runtimes.
- Keep the OpenAI protocol client, credentials, endpoint/model discovery, retries, cancellation, networking, durable run ledger, delayed playback, and native tool execution host-owned behind language-neutral semantic contracts suitable for a future Lua adapter.
- Add the official `com.openai:openai-java` SDK as the native OpenAI protocol implementation; SDK request/response types SHALL NOT become persisted models, generic channel contracts, or the future Lua ABI.

## Capabilities

### New Capabilities
- `openai-connection-profiles`: Global OpenAI-compatible endpoint, credential, client lifecycle, model discovery, and profile availability behavior.
- `asynchronous-channel-messages`: Durable queued turns, asynchronous runs, inbound responses, pending/heard state, selection-aware delayed playback, and restart behavior.
- `openai-agent-channel`: OpenAI Agent provider configuration, volatile conversation semantics, STT-to-agent-to-TTS behavior, bounded completion/tool loop, and SOS reset.
- `agent-client-tools`: Per-channel tool exposure, automatic host execution, exact-once tool-call ledger, Keyboard tool definitions, and normalized tool results.

### Modified Capabilities
- `channel-host-capabilities`: Add language-neutral OpenAI completion, asynchronous synthesis/playback, and agent-tool host capabilities while retaining host ownership and generation-safe revocation.
- `channel-framework`: Permit channel work and response publication to outlive the originating PTT terminal callback through host-owned durable operations without granting runtimes ambient scopes or platform resources.
- `channel-implementation-providers`: Support host-resolved global profile choices and asynchronously discovered model choices in provider-owned configuration without embedding SDK or Compose objects.
- `channel-routing`: Define selection-aware admission and playback for delayed channel responses independently of the original PTT route.
- `channel-runtime-registry`: Define ownership, replacement, shutdown, and late-effect rules for durable asynchronous channel runs distinct from transient runtime callback work.
- `main-device-dashboard`: Surface agent processing, queued/pending response state, global OpenAI profile management, model selection, and agent-channel configuration.
- `car-media-channel-browse`: Project pending agent responses and selected-channel delayed playback behavior consistently in Android Auto.

## Impact

- New domain/storage code for durable channel messages, runs, tool-call ledger entries, response queueing, and volatile conversation reconstruction boundaries.
- New host services for global OpenAI profile/secret management, official SDK client reuse, model discovery, asynchronous agent orchestration, and delayed synthesis/playback.
- New provider/runtime and configuration UI for the OpenAI Agent channel, plus global connection-profile management UI.
- Existing transcription, synthesis, text-output, provider registry, runtime invocation, channel catalogue, routing, dashboard, and Android Auto projections gain new consumers or requirements.
- The OpenAI Java SDK adds OkHttp-aligned but Jackson-heavy JVM dependencies; Android/R8 compatibility and Hermes/generic endpoint behavior require focused verification.
- No Lua engine, Lua package format, camera tool, per-call tool authorization, OpenAI Realtime audio, streaming token playback, persistent conversation context across restart, arbitrary tool registration, or replacement of Supertonic is included.
