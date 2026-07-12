## Context

Subspace has a provider-backed channel catalogue, per-instance runtime generations, bounded runtime callbacks, semantic host capabilities, and host-owned PTT/audio terminal cleanup. Current channel work nevertheless ends inside `ChannelInputTarget.onInputReleased`: the host waits for the runtime result before completing route and committed-target cleanup, and returned playback is tied to that active input session. That contract cannot safely contain a remote agent run, repeated model/tool turns, delayed responses, queued user speech, or playback after channel selection changes.

The first OpenAI Agent channel establishes the missing asynchronous layer. A user speaks through the existing PTT path; local STT produces a user turn; the host persists and serializes that turn; a host-owned OpenAI-compatible client performs Chat Completions and configured client-side Keyboard tool calls; the final assistant text becomes a durable inbound message; host-owned TTS and playback deliver it according to current channel selection. The LLM conversation is deliberately volatile across restart even though messages, runs, and exact-once tool effects are durable.

The implementation must also protect the future Lua migration. No OpenAI SDK, Android, audio, Bluetooth, coroutine scope, database, or service object may enter generic provider/runtime contracts. Kotlin and future Lua runtimes must express the same behavior through language-neutral configuration, events, opaque operation identities, and semantic host capabilities.

## Goals / Non-Goals

**Goals:**

- Add globally managed OpenAI-compatible connection profiles containing endpoint and host-owned credentials while keeping model selection per channel.
- Discover models through the selected profile and present them as host-resolved channel configuration choices.
- Add a native provider-backed OpenAI Agent channel with per-instance profile, model, multiline system prompt, optional Keyboard access, and selected keyboard layout/profile.
- Make user turns and responses asynchronous: release the PTT session after STT and durable enqueue rather than awaiting the remote run.
- Serialize additional user turns per channel and preserve durable message/run/tool state across service or process restart.
- Keep completed conversational context only in memory for the current channel runtime lifetime; restart and SOS begin a fresh model conversation.
- Support bounded Chat Completions tool loops with automatic configured `keyboard_type_text` and `keyboard_press_enter` execution, exact tool-call/result pairing, and no replay of ambiguous effects.
- Synthesize final response text through the semantic synthesis boundary and play or queue it according to selected-channel and audio-admission state.
- Reuse host capability, invocation, routing, and projection boundaries in a form directly adaptable to future Lua channel runtimes.

**Non-Goals:**

- Embedding Lua, defining Lua syntax, or loading Lua packages.
- Adding camera or other client-side tools beyond Keyboard text and Enter.
- Per-call tool authorization or approval UI.
- Persisting completed LLM conversation context across restart.
- Token streaming, sentence-by-sentence TTS, OpenAI Realtime audio, or remote audio input/output APIs.
- Replacing Supertonic with Kokoro; both remain host-side synthesis implementations.
- Parallel model runs or parallel client-tool execution within one channel instance.
- Arbitrary model-defined tools, arbitrary HID operations, arbitrary key presses, or model-selected Keyboard targets/profiles.
- A general user-visible conversation transcript editor, cross-channel conversation sharing, or synchronization between devices.

## Decisions

### D1: Separate transient PTT input from durable asynchronous channel work

`onInputReleased` performs only bounded local work required to produce and durably enqueue a user turn:

```text
PTT release
  -> local transcription
  -> durable outbound message + queued run record
  -> return terminal input result
  -> host releases route and committed runtime target
```

A host-owned asynchronous run service then performs:

```text
queued turn
  -> OpenAI completion/tool loop
  -> durable inbound response
  -> synthesis/playback scheduling
```

The channel runtime receives no coroutine scope and does not launch an unparented job. It requests asynchronous work through an instance-scoped semantic capability that returns an opaque durable run/message identity. The host service owns worker bounds, cancellation, restart recovery, database transactions, SDK clients, and diagnostics.

**Rationale:** Remote latency and user/tool loops must not retain an audio route or depend on a 120-second runtime callback. Host ownership preserves the lifecycle model required by future untrusted Lua runtimes.

**Alternatives considered:**

- Await the complete model loop in `onInputReleased`: rejected because it couples PTT cleanup to remote latency and prevents durable delayed responses.
- Give each runtime a background `CoroutineScope`: rejected because replacement, shutdown, exact-once effects, and future Lua cancellation would become runtime responsibilities.

### D2: Persist messages and active-run recovery state, but not completed conversation context

The asynchronous store contains:

```text
ChannelMessage
- messageId
- channelInstanceId
- conversationEpoch
- direction: OUTBOUND | INBOUND
- createdAt
- text
- state: QUEUED | PROCESSING | PENDING | PLAYING | HEARD | FAILED | CANCELLED
- correlation/run ID

AgentRun
- runId
- channelInstanceId
- conversationEpoch
- profile/model/system-prompt snapshot
- source message ID
- state and terminal reason
- bounded turn/tool counters

AgentToolCall
- runId + remote tool-call ID
- normalized request and argument hash
- host operation ID
- execution/result-reporting state
- normalized terminal result
```

An active run may persist the minimum assistant/tool messages needed to finish or safely report an exact-once tool loop. After a run reaches a terminal result, its completed assistant/tool history is not reconstructed as LLM context after process restart. Durable outbound and inbound message text remains available for queue, playback, pending/heard state, and diagnostics but is excluded from the new post-restart conversation.

Within one live runtime lifetime, an in-memory conversation holds the system message plus successful user, assistant, assistant-tool-call, and tool-result messages in OpenAI-required order. It is not the durable message store's transcript projection.

On process/service restart:

- a new conversation epoch is opened for subsequent turns;
- queued outbound turns are retained and processed serially as fresh turns under their stored profile/model/system-prompt snapshot;
- an interrupted run with no native side effect may be retried within its stored run envelope;
- an already executed Keyboard call is never executed again; its stored result is reported or the run terminates safely;
- terminal inbound messages remain pending/heard as stored.

**Rationale:** This satisfies durable asynchronous delivery without silently creating permanent conversational memory. Persisting the active tool envelope is execution recovery, not persisted completed conversation context.

**Alternatives considered:**

- Persist full conversation history: rejected by the selected product behavior.
- Keep all runs/messages in memory: rejected because asynchronous responses and Keyboard exact-once effects would be lost or duplicated on process death.

### D3: Serialize turns and model/tool loops per channel instance

Each agent channel instance has at most one executing run. Additional transcribed turns are durably queued in admission order. A completed or terminally failed run releases the next queued turn. Different channel instances may progress independently within a bounded host worker policy.

The first request sets `parallel_tool_calls=false`. The host still validates an array of returned tool calls and, if a compatible endpoint returns more than one, records and executes them sequentially in response order. Each run has explicit limits for total model turns, tool calls, elapsed duration, request/response size, and Keyboard text length.

**Rationale:** Serial execution provides deterministic conversation history and prevents concurrent Keyboard operations. Queueing matches the selected user experience better than refusing or cancelling later PTT turns.

**Alternatives considered:**

- Cancel the active run on new speech: rejected because it loses work and complicates exact-once tools.
- Parallel runs: rejected because assistant response ordering and Keyboard side effects become ambiguous.
- Append speech to a live request: deferred until an explicit interruption protocol exists.

### D4: Use global connection profiles and per-channel model selection

A global `OpenAiConnectionProfile` has a stable profile ID, display name, normalized HTTPS base URL, and credential reference. It owns one shared official OpenAI SDK client/connection pool per effective configuration generation. It does not own a selected model.

The profile service performs bounded model discovery through the compatible models endpoint and exposes typed `Loading`, `Available(models, freshness)`, and `Unavailable(reason)` state. Model IDs remain open strings rather than host enums. Discovery results may be cached for configuration usability, but saving a channel validates that the selected model was obtained from or remains accepted by the selected profile. A stale or missing model makes the channel unavailable; it is never silently replaced.

The channel definition stores stable `connectionProfileId` and `modelId`. Renaming a profile preserves references. Updating endpoint or credentials replaces the client generation and refreshes models. Removing a referenced profile preserves the channel definition and projects it unavailable.

Credentials are stored by a host-owned Android Keystore-backed credential store and never enter catalogue payloads, logs, runtime snapshots, provider contracts, OpenAI messages, or Lua-facing values.

**Rationale:** Endpoints and credentials are reusable global infrastructure; model and behavior are channel-instance choices. Stable references avoid duplicating secrets and client pools.

**Alternatives considered:**

- Put model in the global profile: rejected because different channels must choose different models on one endpoint.
- Put endpoint/token directly in each channel payload: rejected because it duplicates secrets and exposes transport configuration to runtimes.

### D5: Adopt the official OpenAI Java SDK behind a language-neutral host port

The host uses `com.openai:openai-java` with an OkHttp client, explicit custom base URL, explicit bounded timeout/retry configuration, arbitrary string model IDs, and Chat Completions. One SDK client is shared per live connection-profile generation and closed only by the profile service.

The channel-facing port uses Subspace-owned values such as:

```text
OpenAiChatRequest
OpenAiMessage
OpenAiToolDefinition
OpenAiToolCall
OpenAiToolResult
OpenAiChatOutcome
```

Adapters map those values to SDK types. SDK classes, Jackson trees, HTTP responses, futures, streams, exceptions, and raw headers never cross the port or persist in the message store. Detailed HTTP diagnostics remain host-only; runtimes receive normalized authentication, unavailable, timeout, cancelled, invalid-response, and host-failure outcomes.

The initial implementation is non-streaming. Streaming remains an adapter-level extension so provider-specific SSE events cannot alter channel contracts.

**Rationale:** OpenAI-compatible APIs are a strategic dependency; the official SDK maximizes maintenance and feature coverage. An anti-corruption boundary prevents its generated model from becoming the permanent Lua ABI.

**Alternatives considered:**

- Direct OkHttp wire client: rejected as the strategic default because multiple future channels will consume broad OpenAI-compatible functionality.
- Expose SDK types directly to the Kotlin runtime: rejected because persistence, testing, compatible endpoints, and Lua would inherit SDK churn.
- `aallam/openai-kotlin`: not selected because official schema maintenance outweighs coroutine-native ergonomics; coroutine adaptation stays host-side.

### D6: Resolve dynamic configuration choices through host-owned choice sources

Provider configuration metadata gains a language-neutral dynamic choice description rather than injecting the profile service or SDK into the provider. Conceptually:

```text
DynamicChoiceField
- id
- label
- source: OPENAI_CONNECTION_PROFILES | OPENAI_MODELS | TEXT_OUTPUT_PROFILES
- dependsOnFieldId?
```

The host configuration screen resolves profile choices globally. After a profile is selected, it resolves model choices from that profile's discovery state. Keyboard layout/profile choices are resolved from the existing host text-output profile source. The provider validator remains authoritative and receives only stable selected IDs in opaque configuration.

The OpenAI Agent configuration is:

```text
connectionProfileId
modelId
systemPrompt
keyboardEnabled
keyboardProfileId?
```

When Keyboard is disabled, no Keyboard tool is advertised and its profile value is ignored or removed on normalization. When enabled, the selected profile must be valid. The system prompt is multiline and bounded; an empty prompt is valid and still receives host protocol/tool instructions separately.

**Rationale:** Dynamic host resources cannot be encoded as a static provider descriptor, and future Lua manifests need the same declarative mechanism without service or Compose access.

**Alternatives considered:**

- Mutate provider descriptors when profiles/models change: rejected because profile-dependent model lists create cross-instance shared mutable metadata.
- Give the provider a profile repository: rejected because it leaks host service ownership into runtime/provider code.

### D7: Keep one volatile conversation epoch per live channel and reset it on SOS

The runtime/run service maintains an in-memory conversation per channel instance and epoch. Each queued turn snapshots the current epoch when admitted. Normal successful runs append user, assistant tool-call, tool-result, and final assistant messages in protocol order.

SOS dispatched to the selected OpenAI Agent channel:

1. advances the conversation epoch;
2. cancels the currently executing remote request where cancellation is still safe;
3. marks non-executed queued turns from the old epoch cancelled;
4. prevents late old-epoch model results from becoming current conversation context or new playback;
5. preserves already terminal durable inbound messages and their pending/heard state;
6. never reverses or replays a Keyboard effect that already began;
7. publishes the fresh idle conversation state.

Service/runtime restart also creates a fresh epoch but retains durable queued/message/tool state according to D2. Relevant profile, model, or system-prompt configuration replacement creates a new runtime and therefore a fresh epoch.

**Rationale:** SOS is the audio-first reset gesture. Epoch checks supply the same late-effect defense used by runtime generations without deleting durable received messages.

**Alternatives considered:**

- Use SOS as Keyboard Enter for every channel: rejected; SOS is provider-specific and the agent channel owns reset semantics.
- Delete all pending responses on reset: rejected because conversation reset is not message deletion.

### D8: Expose only two configured Keyboard tools and execute them automatically

When `keyboardEnabled` is true and the configured text-output profile is available or recoverable, requests advertise strict function schemas for:

```text
keyboard_type_text(text)
keyboard_press_enter()
```

The model cannot select a channel instance, Bluetooth device, profile, layout, keymap, HID sequence, connection policy, retry, or operation ID. The host binds both tools to the configured profile and the calling agent instance. When disabled or terminally unavailable, no Keyboard tools are advertised. A stale/hallucinated call is denied without effect.

The tool broker treats model names and JSON arguments as untrusted, validates strict schemas and host limits, records `(runId, toolCallId)` before execution, and returns exactly one tool-role result for every call. `Delivered`, `Rejected`, `Failed`, and `Indeterminate` map losslessly to model-safe results. An `Indeterminate` result is terminal and explicitly non-retryable. If HTTP continuation fails after execution, the stored tool result is resubmitted without executing the Keyboard operation again.

No per-call approval is added. Enabling Keyboard in that channel instance is the user authorization grant. Current configuration and capability availability are rechecked immediately before execution, so disabling the tool revokes calls not yet begun.

**Rationale:** This is usable through an RSM and reuses existing text-output exact-once/no-replay semantics. Narrow functions constrain authority without interactive friction.

**Alternatives considered:**

- One arbitrary Keyboard/HID tool: rejected because it exposes transport and uncontrolled keys.
- Per-call confirmation: rejected for the initial audio-first workflow.
- Automatic retry on failure: rejected because text output can be partially delivered.

### D9: Make response playback message-owned and selection-aware

A final assistant text is committed as an inbound durable message before synthesis. The host synthesis service produces an opaque, regenerable audio artifact through the existing semantic synthesis implementation; Supertonic is initial composition, and Kokoro may replace it without changing the agent channel or stored message.

Playback is not a `ChannelInputResult` and does not reuse the originating route. A host delayed-playback coordinator resolves the route at playback time using current input mode and serializes with PTT, announcements, and other delayed responses.

Policy:

- if the response's channel is selected when it arrives, the coordinator admits synthesis/playback immediately when audio is idle; if audio is busy, it queues the message in arrival order;
- if another channel is selected, the response remains `PENDING` and is not played;
- when the user selects that channel, its pending responses are admitted in arrival order and begin immediately when audio is available;
- successful complete playback marks the message `HEARD`;
- interruption, route failure, or synthesis failure leaves the text durable and retryable without repeating the OpenAI run or client tools;
- selection changes during active playback do not redirect an already committed playback operation, but later messages follow current selection.

Generated audio need not be durable; pending text can be re-synthesized after restart or artifact loss.

**Rationale:** Selection controls audibility without losing responses. Resolving the route at playback time fits delayed delivery and avoids retaining stale SCO/Telecom objects.

**Alternatives considered:**

- Preserve the originating PTT route: rejected because it may be unavailable or inappropriate later.
- Play every response on arrival regardless of selection: rejected because it breaks channel focus.
- Require explicit playback for every response: rejected by the selected immediate-on-selection behavior.

### D10: Keep runtime and durable-operation ownership distinct

Runtime generations still own callback admission, transient readiness/status publication, and capability leases. Durable asynchronous runs additionally carry channel instance ID, conversation epoch, configuration fingerprint, profile/client generation, and operation identity.

Retirement causes differ:

- configuration replacement or channel removal invalidates the old conversation epoch and cancels not-yet-terminal old work while preserving exact-once tool records and terminal messages;
- service shutdown stops workers after transactions reach a recoverable state and leaves eligible queued/processing records for adoption at next start;
- process death is recovered solely from committed store state;
- selection changes never cancel runs and only affect response playback admission.

The host rejects late publication when the operation epoch/configuration is no longer current. A terminal old-epoch response may remain in history only if it was durably committed before invalidation; it is never appended to the new volatile conversation or auto-played as a new response after reset.

Capability cleanup and registry shutdown must carry enough termination context to distinguish replacement/removal from resumable host shutdown, rather than treating every revocation as the same cancellation.

**Rationale:** Runtime generation is process-local; durable work cannot use it as its sole restart identity. Explicit operation epochs preserve clean replacement and Lua-safe host authority.

### D11: Project asynchronous state without exposing conversation or SDK internals

Runtime snapshots retain generic preparation and execution state and derive `pendingCount` from durable pending inbound messages. Additional host projection distinguishes queued/processing/pending/failed counts where required by the dashboard while keeping raw prompts, user text, tool arguments, credentials, and SDK errors out of logs and generic media metadata.

Phone UI adds global connection-profile management and OpenAI Agent configuration. Android Auto preserves catalogue order and stable media IDs, shows actionable unavailable/processing/pending metadata, and triggers the same selection behavior; it does not expose profile credentials, edit prompts, or render full conversations.

**Rationale:** The existing aggregate projection is the correct cross-surface source; SDK- or provider-specific polling would recreate a closed-world host.

### D12: Use one authoritative provider registry for catalogue and runtime resolution

The current service constructs `ChannelRepository(applicationContext)` before the runtime provider registry exists. That repository therefore uses `BuiltInChannelDescriptors.configurationResolver`, while runtime creation and UI creation use the later `ChannelImplementationProviderRegistry`. A newly registered OpenAI Agent provider could appear creatable through the runtime registry yet be rejected by catalogue migration or mutation as missing.

Composition shall construct and register all native providers first, then inject the same descriptor resolver into catalogue repository load/migration/mutation and runtime reconciliation. Repository loading remains generic and may preserve structurally valid definitions for unavailable providers; it shall not fall back to a separate built-in-only resolver after provider registration becomes authoritative.

Provider registration remains native and deterministic for this change. The shared resolver contract, stable implementation IDs, opaque payloads, and unavailable-provider preservation are the seam a future Lua package loader will extend; no OpenAI-specific branch is added to repository validation.

**Rationale:** Two provider universes make extensibility illusory and would block both the OpenAI provider and future Lua providers at persistence boundaries even when runtime construction supports them.

**Alternatives considered:**

- Add OpenAI Agent to `BuiltInChannelDescriptors.configurationResolver`: rejected because every future provider would require another closed-world repository edit.
- Let repository mutations skip provider validation: rejected because invalid available-provider configuration would reach persistence and fail later during runtime construction.

## Risks / Trade-offs

- **[Risk] OpenAI-compatible endpoints diverge from the official schema.** → Keep SDK use behind tolerant Subspace values, test custom base URLs/model IDs/errors, normalize missing optional fields, and begin with non-streaming Chat Completions.
- **[Risk] The official SDK adds Jackson, schema-generation, and method-count/R8 cost on Android.** → Run an API-31/R8 compatibility spike, inspect dependency resolution/APK impact, use one shared client per profile generation, and pin the verified SDK version.
- **[Risk] Durable runs and volatile conversation appear contradictory.** → Persist message delivery and only the minimum active-run envelope; explicitly start a new conversation epoch after restart and never reconstruct completed history as model context.
- **[Risk] Process death occurs after Keyboard execution but before tool-result submission.** → Commit tool execution outcome transactionally before continuation and deduplicate by run/tool-call ID; never infer failure from missing HTTP continuation.
- **[Risk] Automatic Keyboard tools can type harmful content.** → Make exposure an explicit per-channel setting, bind a fixed profile, provide only text and Enter schemas, validate/rate-limit/serialize calls, audit metadata without payloads, and never expose arbitrary HID operations.
- **[Risk] A model loops on tools or produces oversized content.** → Enforce strict schemas, one-at-a-time execution, maximum turns/calls/text/request sizes, host deadlines, and deterministic terminal failure.
- **[Risk] Model discovery is slow or unavailable during configuration.** → Publish typed loading/error state, cache non-authoritative choices, allow explicit refresh, preserve existing selected IDs, and refuse silent fallback.
- **[Risk] A selected model disappears after save.** → Preserve configuration and project the channel unavailable until the user selects a valid model or the endpoint restores it.
- **[Risk] Response playback races with PTT or selection changes.** → Centralize delayed playback admission, resolve route at playback time, serialize audio ownership, and keep durable text pending on interruption/failure.
- **[Risk] SOS races with a completion or Keyboard call.** → Advance epoch atomically before cancellation, gate all later publication by epoch, and preserve terminal/indeterminate tool outcomes without replay.
- **[Risk] Queued turns accumulated before restart lose their old context.** → Process them after restart as fresh serialized turns using stored request snapshots; surface that restart resets conversation semantics.
- **[Trade-off] Non-streaming responses increase perceived latency.** → Accept for the first cut; asynchronous delivery avoids route blocking and preserves a clean later streaming extension.
- **[Trade-off] Generated TTS audio is not durable.** → Persist authoritative text and regenerate audio when needed, avoiding model-specific audio storage and simplifying the Kokoro cutover.

## Migration Plan

1. Add storage schemas and host services behind disabled composition without changing existing built-ins or catalogue behavior.
2. Add global connection-profile storage, Keystore-backed credentials, SDK client/model discovery, and profile-management UI.
3. Add asynchronous message/run/tool stores and recovery, then the delayed playback coordinator, each exercised with test-only providers before OpenAI integration.
4. Add language-neutral OpenAI and agent-tool capabilities backed by the official SDK and existing text-output service.
5. Register the OpenAI Agent provider only after dynamic choices, model validation, runtime projection, and host services are available.
6. Enable creation/configuration of agent instances, then verify queued turns, restart reset, SOS reset, tool exact-once behavior, pending playback, and missing profile/model states.
7. Existing catalogue documents require no rewrite; the new provider uses its own schema version and unavailable-provider preservation. Removing or rolling back the APK leaves agent definitions as unavailable opaque instances rather than corrupting the catalogue.

## Open Questions

No product-level questions remain for proposal readiness. Exact numeric queue, timeout, model-turn, tool-call, message-size, and Keyboard-text limits shall be selected conservatively during implementation and fixed by tests before enabling the provider.
