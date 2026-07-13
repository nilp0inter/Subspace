## 1. Language-Neutral Contracts and SDK Compatibility

- [x] 1.1 Add the pinned official `com.openai:openai-java` dependency and resolve its OkHttp, Jackson, Kotlin, Android API 31, and R8 configuration without exposing SDK types outside the host adapter package
- [x] 1.2 Add a focused Android/JVM compatibility fixture proving custom base URL, arbitrary string model ID, Bearer authentication, non-streaming Chat Completions, tool calls, normalized errors, cancellation, and bounded timeouts against a local compatible server
- [x] 1.3 Define Subspace-owned OpenAI profile, model-discovery, chat message, tool definition/call/result, completion outcome, asynchronous run, and delayed playback values with no SDK, Android, coroutine-scope, route, or transport objects
- [x] 1.4 Extend semantic capability contracts for OpenAI model discovery/completion, asynchronous conversation enqueue, and delayed synthesis/playback with opaque operation identities and normalized outcomes
- [x] 1.5 Add declarative host-resolved dynamic configuration choice sources for global connection profiles, profile-dependent model IDs, and text-output profiles without injecting repositories, SDK clients, or Compose into providers
- [x] 1.6 Construct one authoritative provider registry before repository load and inject its descriptor resolver into catalogue migration, add, update, UI creation, and runtime reconciliation, removing production reliance on the built-in-only default resolver
- [x] 1.7 Add contract tests proving language-neutral values, dynamic choice dependency resolution, unavailable sources, custom-provider catalogue load/mutation, capability declaration/denial, generation revocation, cancellation, and late-effect suppression

## 2. Global OpenAI Connection Profiles

- [x] 2.1 Implement immutable stable-ID connection-profile metadata persistence with normalized base URL, display name, credential reference, atomic mutations, and schema migration
- [x] 2.2 Implement Android Keystore-backed bearer credential storage whose secret values never enter catalogue payloads, profile projections, logs, saved UI state, or provider/runtime requests
- [x] 2.3 Implement the host-owned SDK client registry with one reusable client per effective profile generation, explicit retry/timeout policy, deterministic replacement, bounded close, and no silent default profile
- [x] 2.4 Implement bounded model discovery and refresh through each profile, arbitrary string model IDs, typed loading/available/unavailable state, safe cache semantics, and stale-model preservation without fallback
- [x] 2.5 Implement create, edit, test, rename, refresh, and delete profile operations, including referenced-profile deletion behavior that preserves affected channel definitions as unavailable
- [x] 2.6 Add focused tests for profile atomicity, secret isolation, client reuse/replacement/close, model refresh and cache failure, arbitrary model IDs, missing credentials, authentication failure, malformed responses, and referenced deletion

## 3. Durable Asynchronous Message and Run Store

- [x] 3.1 Implement a transactional versioned store for channel messages, queued turns, agent runs, active-run recovery envelopes, tool-call ledger entries, pending/heard state, and configuration/conversation epochs
- [x] 3.2 Implement atomic admission that commits an outbound message and queued run envelope before remote submission, preserving FIFO order independently for each channel instance
- [x] 3.3 Implement terminal inbound-message commit, pending/heard transitions, failed/cancelled states, and correlation between source turns, runs, responses, and tool calls
- [x] 3.4 Implement restart reconciliation that distinguishes unsubmitted, interrupted without side effect, executed-tool/result-pending, terminal response, interrupted playback, and already-heard records without replaying effects
- [x] 3.5 Keep completed OpenAI conversation context in memory only while allowing the minimum active tool-loop envelope to remain durable until its run terminates
- [x] 3.6 Add store and recovery tests for transaction failure, process death at every state boundary, FIFO preservation, duplicate admission, interrupted playback, pending/heard durability, and exclusion of durable messages from post-restart model context

## 4. Host-Owned Agent Run Orchestration

- [x] 4.1 Implement a bounded asynchronous agent-run coordinator with at most one executing run per channel, queued subsequent turns, independent progress across channels, and explicit turn/tool/time/size limits
- [x] 4.2 Implement volatile per-channel conversation epochs that preserve correctly ordered user, assistant tool-call, tool-result, and final assistant messages during one live runtime lifetime
- [x] 4.3 Implement restart adoption that opens a fresh conversation epoch, processes retained queued turns as fresh conversations from stored request snapshots, and safely resumes only the minimum active exact-once tool envelope
- [x] 4.4 Implement configuration replacement/removal cancellation, service-shutdown quiescence and resumability, current-epoch publication gates, and suppression of stale remote results or playback
- [x] 4.5 Implement normalized run status and diagnostics without logging prompts, message text, tool arguments, credentials, SDK exceptions, or response payloads
- [x] 4.6 Add deterministic coordinator tests for serial queueing, independent channels, remote timeout/cancellation/failure, restart, replacement, shutdown, bounded loops, stale epochs, and late completion suppression

## 5. Configured Keyboard Client Tools

- [x] 5.1 Implement a host-owned tool registry and broker that advertises only currently configured and available language-neutral tool definitions and defensively rejects unknown, disabled, stale, malformed, or oversized calls
- [x] 5.2 Define strict OpenAI-compatible schemas for `keyboard_type_text(text)` and `keyboard_press_enter()` with `parallel_tool_calls=false`, no model-selected profile/instance/device/keymap/retry fields, and no per-call authorization path
- [x] 5.3 Bind enabled tools to the channel's configured text-output profile and map execution through `TextOutputCapability.sendText` and constrained Enter delivery
- [x] 5.4 Implement exact-once tool-call accounting keyed by run and remote tool-call ID, argument-hash conflict detection, sequential execution, one matching tool result per call, and result resubmission without native re-execution
- [x] 5.5 Map Delivered, Rejected, Failed, Cancelled, and Indeterminate outcomes losslessly to model-safe results and prohibit automatic replay after ambiguous or potentially partial delivery
- [x] 5.6 Add focused tests for disabled and unavailable tools, automatic configured execution, text limits, Enter behavior, multiple returned calls despite parallel disablement, duplicate IDs, crash after effect, lost continuation, indeterminate delivery, and current-configuration revocation

## 6. Delayed Synthesis and Selection-Aware Playback

- [x] 6.1 Implement a host delayed-playback coordinator that owns synthesis, route resolution at playback time, audio admission, FIFO message playback, interruption cleanup, and terminal message transitions
- [x] 6.2 Commit final assistant text durably before requesting semantic synthesis and keep generated Supertonic audio opaque and regenerable rather than authoritative durable state
- [x] 6.3 Play an arriving response immediately only when its channel remains selected and audio admission succeeds; otherwise retain it pending without acquiring or retaining the originating PTT route
- [x] 6.4 Admit a selected channel's pending responses in arrival order as soon as the user returns and audio becomes available, without redirecting playback already committed before a later selection change
- [x] 6.5 Serialize delayed responses with PTT, announcements, and other host audio work; preserve pending text after synthesis, route, playback, interruption, or process failure until complete playback commits HEARD
- [x] 6.6 Add focused playback tests for selected/unselected arrival, return-to-channel, multiple pending responses, audio contention, route change, selection change during playback, synthesis failure, process restart, exact-once heard transition, and future synthesizer substitution

## 7. OpenAI Agent Provider and Runtime

- [x] 7.1 Define the built-in OpenAI Agent implementation ID, versioned opaque configuration codec, descriptor metadata, dynamic field schema, defaults, validation, and provider construction through semantic host capabilities only
- [x] 7.2 Implement channel configuration for stable connection profile ID, discovered model ID, bounded multiline system prompt, Keyboard enabled state, and required keyboard profile only when enabled
- [x] 7.3 Implement bounded local transcription and durable asynchronous turn enqueue on input release, returning without waiting for OpenAI, tools, synthesis, or delayed playback
- [x] 7.4 Implement non-streaming Chat Completions with system prompt, volatile conversation context, arbitrary selected model, configured tool declarations, assistant tool-call preservation, exact tool results, and repeated bounded turns until final assistant text
- [x] 7.5 Implement generic readiness from profile/model, transcription, synthesis, asynchronous-run, delayed-playback, and conditional text-output capability state without built-in identity branches
- [x] 7.6 Implement SOS conversation reset by atomically advancing the epoch, cancelling safe old-epoch work and queued turns, suppressing late results, preserving terminal pending/heard messages and exact-once tool outcomes, and publishing fresh idle state
- [x] 7.7 Add provider/runtime tests for all configuration combinations, missing/deleted profile, stale model, empty STT, queued speech, tool and non-tool responses, loop bounds, restart context reset, configuration reset, SOS races, and language-neutral host interaction

## 8. Configuration and Global Profile Surfaces

- [x] 8.1 Add global OpenAI connection-profile management to the phone UI with redacted credential editing, model refresh/test state, actionable errors, and stable-ID mutations
- [x] 8.2 Extend host-rendered channel configuration to resolve profile choices, refresh dependent model choices, preserve unavailable selected IDs, render the multiline system prompt, and enable keyboard-profile selection only when Keyboard access is enabled
- [x] 8.3 Add OpenAI Agent creation/configuration actions without SDK-specific UI models, direct repository/service exposure, raw exceptions, or secret values in navigation state
- [x] 8.4 Add UI tests for profile creation/edit/delete/reference behavior, secret redaction, loading/error/refresh model states, dependent selection, unavailable values, multiline prompt preservation, and Keyboard conditional fields

## 9. Runtime, Routing, and Cross-Surface Projection

- [x] 9.1 Integrate durable-run ownership with runtime registry reconciliation so replacement/removal differs from resumable shutdown, committed PTT targets remain exact-once, and process-local generations do not become durable restart identity
- [x] 9.2 Project generic queued, processing, failed, and pending/heard message state through the aggregate runtime snapshot, including accurate pending counts without exposing message content
- [x] 9.3 Trigger pending-response playback from stable channel selection changes on phone and Android Auto while preserving catalogue order, active identity, and audio ownership
- [x] 9.4 Update phone channel cards and Android Auto browse metadata for agent readiness, processing, queued turns, pending responses, and missing profile/model states without provider-specific routing branches
- [x] 9.5 Add composition tests from persisted Agent definition through dynamic configuration, registry construction, PTT enqueue, durable run, Keyboard tool, inbound response, selection-aware playback, SOS/reset, replacement, restart, and shutdown

## 10. Service Composition and End-to-End Verification

- [x] 10.1 Compose profile storage, credential store, SDK client registry, model discovery, durable stores, run coordinator, tool broker, delayed playback, capabilities, and Agent provider under the foreground-service lifecycle with one coherent shutdown budget
- [x] 10.2 Verify existing Journal, Debug, and Keyboard instances retain provider-neutral readiness, PTT, playback, text-output, selection, replacement, and shutdown behavior after the new asynchronous services are composed
- [x] 10.3 Run focused JVM tests for profile, capability, store, recovery, coordinator, tools, playback, provider/runtime, registry, routing, dashboard, Android Auto, and composition behavior
- [x] 10.4 Build the debug and release variants through the repository devshell and verify Android API 31 compatibility, dependency resolution, R8 rules, unsigned release behavior, and no OpenAI SDK types in generic channel/runtime/persistence APIs
- [x] 10.5 Install on the physical Android device and verify profile/model configuration, multiple queued PTT turns, background agent completion, selected-channel immediate playback, pending response on another channel, playback on return, Keyboard text and Enter tools, SOS reset, restart context reset, disconnect/error handling, and foreground-service teardown
- [x] 10.6 Verify process/service restart recovery leaves no duplicate Keyboard effect, lost terminal response, stale conversation publication, stranded audio route, unreleased runtime generation, exposed credential, or late foreground-service effect
