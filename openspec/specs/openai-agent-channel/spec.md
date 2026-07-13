## Purpose

TBD. Defines OpenAI Agent channel behavior: per-instance profile/model/prompt/keyboard configuration, volatile conversations, durable asynchronous turns, bounded completion/tool loops, semantic synthesis and selection-aware playback, and language-neutral runtime contracts.

## Requirements

### Requirement: Agent instances select profile, model, prompt, and keyboard tools independently
Each OpenAI Agent channel instance SHALL have provider-owned configuration containing a global connection profile ID, one model ID discovered for that profile, a multiline system prompt, and keyboard-tool settings. Keyboard-tool settings SHALL identify whether tools are enabled and, when enabled, the host keyboard profile or layout used by tool execution. The configuration SHALL persist by channel instance ID, preserve system-prompt line breaks, and SHALL NOT contain a base URL, bearer secret, protocol client, SDK object, or global model selection. Multiple instances SHALL be able to reference one profile while selecting different models, prompts, and keyboard settings.

#### Scenario: Two instances use one profile
- **WHEN** two OpenAI Agent instances reference the same global profile and the profile exposes multiple discovered models
- **THEN** each instance SHALL persist its own selected model ID
- **AND** changing one instance's model, prompt, or keyboard settings SHALL NOT change the sibling instance
- **AND** each instance SHALL submit requests using its own configuration

#### Scenario: Multiline prompt is persisted
- **WHEN** a user saves a system prompt containing multiple lines for one agent instance
- **AND** the service or application restarts
- **THEN** the same prompt text and line breaks SHALL be restored for that instance
- **AND** the prompt SHALL be supplied only to requests for that instance

#### Scenario: Keyboard tools are configured
- **WHEN** a user enables Keyboard tools and selects a host keyboard profile or layout for an agent instance
- **THEN** that enabled state and selected profile or layout SHALL be retained in the instance configuration
- **AND** the host SHALL use those settings for the instance's tool calls
- **AND** a sibling agent instance SHALL retain independent Keyboard-tool settings

#### Scenario: Configuration references an unavailable dependency
- **WHEN** an instance references a deleted profile, unavailable profile, or model absent from that profile's discovered choices
- **THEN** the host SHALL preserve the instance configuration without substituting a profile or model
- **AND** the instance SHALL expose a typed unavailable state
- **AND** the host SHALL refuse new agent input before starting capture or remote completion

### Requirement: Agent readiness and configuration errors are typed
An OpenAI Agent instance SHALL be ready only when its referenced profile is available, its selected model is validly discovered for that profile, its required local transcription and semantic synthesis capabilities are available, and every enabled Keyboard tool dependency is available through a host capability. The host SHALL report invalid configuration, unavailable dependencies, and capability failures as typed semantic errors associated with the channel instance and SHALL keep protocol, platform, credential, and transport details out of runtime-visible results.

#### Scenario: All required dependencies are available
- **WHEN** an enabled agent instance has valid profile and model selections, local STT and semantic synthesis are available, and its enabled Keyboard tools can be acquired
- **THEN** the instance SHALL report ready
- **AND** a released PTT recording SHALL be eligible for transcription and queue admission

#### Scenario: A required dependency is unavailable
- **WHEN** the profile, selected model, local STT, semantic synthesis, or an enabled Keyboard capability is unavailable
- **THEN** the instance SHALL report not ready with a typed semantic reason
- **AND** a PTT attempt SHALL not start capture or submit a remote completion
- **AND** the runtime SHALL not receive a platform object or secret as a diagnostic

#### Scenario: A configuration submission is invalid
- **WHEN** a submitted profile ID, model ID, keyboard profile or layout, or other provider-owned value fails validation
- **THEN** the host SHALL reject the submission with a typed configuration error
- **AND** it SHALL not commit a partial configuration or construct a runtime from it

### Requirement: Each runtime generation owns one volatile conversation
The host SHALL maintain exactly one volatile conversation for each OpenAI Agent channel runtime generation and SHALL use it to preserve context across that instance's sequential turns while the generation remains alive. The volatile conversation SHALL include successful user turns and the ordered assistant, tool-call, and tool-result outcomes produced in that generation. The host SHALL discard it when the service or runtime generation restarts, when profile, model, system prompt, or Keyboard-tool configuration is replaced, or when SOS resets the channel. Persisted messages, runs, and responses SHALL remain available for status and playback but SHALL NOT automatically become model context after a reset or restart.

#### Scenario: Sequential turns share live context
- **WHEN** two turns for one agent instance complete during the same runtime generation
- **THEN** the second completion request SHALL include the first turn and its ordered assistant and tool outcomes from the volatile conversation
- **AND** the conversation SHALL remain isolated from every other channel instance

#### Scenario: Service restart creates a fresh conversation
- **WHEN** the service or process restarts after durable messages exist for an agent instance
- **THEN** the new runtime generation SHALL begin with a fresh volatile conversation using the current system prompt
- **AND** prior persisted message text SHALL not be automatically submitted as context
- **AND** durable queue, response, pending, and heard records SHALL remain available to host recovery

#### Scenario: Relevant configuration changes
- **WHEN** the profile ID, selected model ID, system prompt, Keyboard-tool enabled state, or selected keyboard profile or layout changes for an agent instance
- **THEN** the host SHALL discard the old volatile conversation before processing a turn under the new configuration
- **AND** prior durable records SHALL remain associated with their original run and channel identities

#### Scenario: SOS resets an agent
- **WHEN** SOS is pressed for an agent instance
- **THEN** the host SHALL discard that instance's volatile conversation immediately
- **AND** subsequent turns SHALL begin a fresh conversation
- **AND** durable user turns, responses, pending state, and heard state SHALL remain available
- **AND** an active completion or tool operation SHALL not publish a late result into the discarded conversation

### Requirement: Released PTT audio becomes a durable asynchronous user turn
After a committed PTT recording is released, the agent channel SHALL hand terminal audio to the host-owned local STT capability and SHALL enqueue the resulting user turn through the durable asynchronous message contract. STT, queue admission, completion, tool execution, synthesis, and playback SHALL continue independently of the originating PTT callback and audio route. The channel SHALL NOT retain an audio route, recorder, PTT callback, or platform audio object while asynchronous work is pending.

#### Scenario: A release produces a valid turn
- **WHEN** a committed PTT recording is released with non-empty audio and local STT returns a transcript
- **THEN** the host SHALL durably record the transcribed user turn before acknowledging queue acceptance
- **AND** the host SHALL process it in acceptance order for that channel
- **AND** the originating PTT callback SHALL be allowed to complete without waiting for remote completion or playback

#### Scenario: STT returns empty audio or failure
- **WHEN** local STT receives empty audio or returns a typed transcription failure
- **THEN** the host SHALL publish a channel-scoped `EmptyAudio` or `TranscriptionFailed` error
- **AND** it SHALL not enqueue a user turn or submit a remote completion
- **AND** the audio route SHALL be released by the host audio lifecycle exactly once

#### Scenario: PTT route ends before remote work completes
- **WHEN** the originating audio route or PTT session terminates while transcription, completion, tool execution, synthesis, or playback remains pending
- **THEN** the asynchronous operation SHALL continue or reach its own typed terminal outcome according to host policy
- **AND** no channel runtime SHALL reacquire or retain the terminated route

### Requirement: Turns are queued and completions are serialized per agent instance
The host SHALL accept later transcribed user turns while an earlier turn for the same agent instance is queued, completing, waiting for a tool, synthesizing, or awaiting playback. It SHALL preserve acceptance order and SHALL execute at most one completion/tool run at a time for each instance. A failed, cancelled, or indeterminate run SHALL reach a typed terminal state without silently discarding or reordering later accepted turns. Queued work and response records SHALL remain durable for recovery, while their text SHALL remain separate from a fresh volatile conversation after restart.

#### Scenario: A second turn arrives during completion
- **WHEN** a second PTT release is transcribed while the first run for that agent instance is active or waiting for a tool
- **THEN** the host SHALL durably enqueue the second turn
- **AND** it SHALL not submit a concurrent completion for that instance
- **AND** it SHALL process the second turn after the first run reaches a terminal state

#### Scenario: The first run fails
- **WHEN** the earliest queued run reaches a failed, cancelled, or indeterminate terminal outcome
- **THEN** the next accepted turn SHALL remain in queue order
- **AND** the host SHALL process it without requiring the user to repeat the PTT turn

#### Scenario: Multiple agent instances have work
- **WHEN** more than one agent instance has queued turns
- **THEN** each instance SHALL preserve its own acceptance order and volatile context
- **AND** one instance's queue or failure SHALL not reorder, cancel, or discard another instance's work

### Requirement: Completion and tool execution use a bounded host-owned loop
For each queued turn, the host SHALL submit semantic Chat Completion requests using that instance's selected profile, model, system prompt, and volatile conversation. When a completion returns tool calls, the host SHALL execute only tools enabled by that instance and SHALL feed an exact paired tool result back into the same run. The host SHALL enforce a finite tool-loop bound and SHALL terminate a run with a typed `ToolLoopLimitExceeded` error when the bound is reached; it SHALL not run an unbounded completion/tool loop. Tool authorization SHALL be determined by channel configuration and SHALL NOT pause for per-call user authorization.

#### Scenario: Completion returns no tool call
- **WHEN** a completion returns final assistant text without a tool call
- **THEN** the host SHALL append the assistant outcome to the volatile conversation
- **AND** the run SHALL leave the tool loop and proceed to semantic synthesis

#### Scenario: Completion returns an enabled Keyboard call
- **WHEN** a completion requests an enabled Keyboard tool for the current agent instance
- **THEN** the host SHALL execute that call automatically through the instance-scoped host capability
- **AND** it SHALL record the tool call and exactly one matching tool result before requesting the next completion
- **AND** the runtime SHALL not request per-call authorization

#### Scenario: Completion requests a disabled or unknown tool
- **WHEN** a completion requests a tool not enabled or not supported by the current agent configuration
- **THEN** the host SHALL not execute the requested effect
- **AND** it SHALL record a normalized tool-unavailable result paired with that call
- **AND** the run SHALL terminate or continue only according to the provider contract without exposing SDK objects

#### Scenario: The tool-loop bound is reached
- **WHEN** completion and tool results continue until the host-owned finite loop bound is reached
- **THEN** the host SHALL stop issuing further completion or tool requests for that run
- **AND** it SHALL record `ToolLoopLimitExceeded` as the run's typed terminal error
- **AND** it SHALL not synthesize or play a final assistant response from that incomplete run

### Requirement: Keyboard tools have exact semantic definitions and safe effects
When Keyboard tools are enabled for an agent instance, the host SHALL expose exactly the configured semantic operations `type_text` and `press_enter`. `type_text` SHALL accept text and SHALL use the configured host keyboard profile or layout; `press_enter` SHALL represent one Enter action without requiring a text payload. The host SHALL execute enabled calls automatically without per-call authorization, preserve exact call/result pairing, and SHALL never replay a text-output effect after an outcome is ambiguous.

#### Scenario: The model requests type_text
- **WHEN** an enabled run requests `type_text` with a text argument
- **THEN** the host SHALL submit that text and configured keyboard profile or layout to the semantic text-output capability
- **AND** it SHALL return one normalized result paired with the exact tool-call identity
- **AND** the agent runtime SHALL not receive HID, BLE, GATT, or keyboard transport objects

#### Scenario: The model requests press_enter
- **WHEN** an enabled run requests `press_enter`
- **THEN** the host SHALL execute one semantic Enter operation through the configured keyboard capability
- **AND** it SHALL return one normalized result paired with that exact call
- **AND** a text argument SHALL not be required for the Enter operation

#### Scenario: Text delivery is ambiguous
- **WHEN** a Keyboard tool operation times out, disconnects, loses acknowledgement, or otherwise cannot prove whether text was delivered
- **THEN** the host SHALL return an `Indeterminate` tool result
- **AND** it SHALL not replay any portion of the text operation
- **AND** the run SHALL reach a typed failed or indeterminate terminal state without duplicating the effect

### Requirement: Final assistant text uses semantic synthesis and selection-aware playback
After a run produces final assistant text, the host SHALL submit that text to the semantic text-to-speech (TTS) synthesis capability and SHALL schedule playback independently of the originating PTT operation. An arriving synthesized response SHALL play immediately only when its channel instance remains selected and host audio admits playback; otherwise it SHALL remain a durable pending response and SHALL play in durable channel order when the user returns and playback is admitted. Playback SHALL never be redirected to another channel, and a response SHALL become heard only after playback completion or an explicit skip.

#### Scenario: Response arrives while its channel is selected
- **WHEN** semantic synthesis completes for an agent response while that channel instance is selected and audio playback is admitted
- **THEN** the host SHALL begin playback without waiting for another PTT session
- **AND** the response SHALL be associated with that channel's run
- **AND** the host SHALL mark it heard only after playback completes

#### Scenario: Response arrives while another channel is selected
- **WHEN** a synthesized response becomes playable while a different channel instance is selected
- **THEN** the host SHALL retain it as pending
- **AND** it SHALL not play the response on the selected channel
- **AND** the pending response SHALL remain associated with its original channel

#### Scenario: User returns to pending responses
- **WHEN** the user selects a channel with one or more pending synthesized responses and audio playback is admitted
- **THEN** the host SHALL play pending responses in durable response order
- **AND** each response SHALL transition to heard only after playback completion or explicit skip

#### Scenario: Synthesis or playback fails
- **WHEN** semantic synthesis or host playback returns a typed failure, cancellation, or indeterminate outcome
- **THEN** the host SHALL preserve the response and run's terminal state with a channel-scoped error
- **AND** it SHALL not report the response as successfully heard solely because synthesis or playback was attempted
- **AND** it SHALL not replay an effect whose delivery state is ambiguous

### Requirement: Agent failures are isolated and language-neutral
The host SHALL normalize profile, model, STT, completion, tool, synthesis, playback, cancellation, timeout, and shutdown failures into typed semantic outcomes associated with the affected channel instance and run. A failed agent operation SHALL not expose credentials, SDK request or response objects, Android audio routes, Bluetooth or HID transports, or raw provider exceptions to the runtime or surfaces. Failures for one channel SHALL not terminate unrelated channel runtimes or discard their queued turns, pending responses, or conversation state.

#### Scenario: Remote completion fails
- **WHEN** a completion request fails because of authentication, endpoint, protocol, timeout, cancellation, or provider error
- **THEN** the host SHALL mark the affected run failed, cancelled, or indeterminate with a normalized reason
- **AND** it SHALL preserve the run and user-turn record for recovery and projection
- **AND** it SHALL not claim that a final assistant response was produced

#### Scenario: One channel fails while another runs
- **WHEN** one agent instance encounters any typed processing failure while another instance has queued or active work
- **THEN** the failed instance SHALL expose its own error and terminal state
- **AND** the other instance SHALL continue according to its own queue and runtime generation

#### Scenario: A late completion follows reset or closure
- **WHEN** a completion, tool result, synthesis result, or playback callback arrives after its run or runtime generation was cancelled, reset, or closed
- **THEN** the host SHALL ignore the stale effect for current conversation and projection state
- **AND** it SHALL not publish duplicate assistant text, tool effects, or playback

### Requirement: Agent runtime contracts remain language-neutral
The OpenAI Agent provider SHALL use only generic lifecycle events, host-owned STT/completion/tool/synthesis/playback capabilities, profile and model IDs, semantic text values, and normalized results. SDK request and response types SHALL NOT become persisted models, generic channel contracts, runtime state exposed to adapters, or a future Lua ABI. The provider SHALL NOT introduce a Lua engine, package runtime, ambient credential access, per-call authorization channel, camera tool, OpenAI Realtime audio, or streaming token playback.

#### Scenario: A runtime requests an agent operation
- **WHEN** an agent runtime needs transcription, completion, a Keyboard effect, synthesis, or playback
- **THEN** it SHALL invoke the corresponding host capability with language-neutral values
- **AND** it SHALL receive only typed semantic results or opaque lifecycle-bound handles
- **AND** it SHALL not receive Android, Bluetooth, audio-route, HTTP, SDK, or credential objects

#### Scenario: An adapter is implemented in another language
- **WHEN** a future language adapter implements the same agent provider contract
- **THEN** it SHALL use the same profile/model IDs, queue/run semantics, tool names, and normalized outcomes
- **AND** the host SHALL not require that adapter to import the OpenAI SDK or expose language-specific transport objects