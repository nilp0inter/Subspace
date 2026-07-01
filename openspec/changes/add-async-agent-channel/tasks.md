## 1. Chaquopy and Pydantic AI feasibility gate

- [x] 1.1 Add the minimal Chaquopy Gradle configuration for `arm64-v8a` without wiring it into channel dispatch
- [x] 1.2 Add a minimal Python bridge module that returns a JSON-compatible canned agent response
- [ ] 1.3 Package `pydantic-ai-slim[openai]` plus Chaquopy-compatible Android wheels for `pydantic-core` and `jiter`, then verify Python imports it on device/build target
- [x] 1.4 Add a Kotlin smoke call that invokes the Python bridge and receives a JSON-compatible response
- [ ] 1.5 Measure APK size impact and record the result in the change notes or task evidence
- [x] 1.6 Stop implementation and document the blocker if `pydantic-core` or Pydantic AI cannot package/import under Chaquopy

## 2. Agent channel model and repository integration

- [ ] 2.1 Add `AgentChannel` to the sealed `Channel` model with id, display name, configuration fields, and computed readiness
- [ ] 2.2 Add Agent channel configuration and active-channel persistence to `ChannelRepository`
- [ ] 2.3 Extend `AppState` with the Agent channel and any Agent channel runtime status needed by UI/car projections
- [ ] 2.4 Extend channel ordering and `projectChannelBrowseEntries` so Agent appears after Journal and Debug with pending count support
- [ ] 2.5 Update channel repository/model tests for Agent channel persistence, readiness, ordering, and browse projection

## 3. Kotlin agent runtime boundary

- [ ] 3.1 Define JSON-compatible `AgentInput`, `AgentOutput`, `AgentJobState`, and error/result types
- [ ] 3.2 Define an `AgentRunner` port with a suspend call for transcript-based agent execution
- [ ] 3.3 Implement a fake `AgentRunner` for unit tests and non-Chaquopy controller tests
- [ ] 3.4 Implement `ChaquopyAgentRunner` behind the `AgentRunner` port, mapping Kotlin requests to the Python bridge
- [ ] 3.5 Map Python bridge success/failure responses to typed Kotlin agent output without throwing through service dispatch code

## 4. Asynchronous agent job and inbox lifecycle

- [ ] 4.1 Implement `AgentJobRunner` with service-scoped coroutine execution and observable job state
- [ ] 4.2 Implement in-memory pending response queue ordered by response/capture sequence
- [ ] 4.3 Ensure submitted agent jobs do not hold `activePttSession`, `CaptureService`, SCO, Telecom, or local output routes
- [ ] 4.4 Add cancellation behavior for foreground-service teardown without cancelling completed pending responses earlier than teardown
- [ ] 4.5 Unit-test that a thinking agent job does not block a subsequent Journal PTT dispatch
- [ ] 4.6 Unit-test agent job success, failure, empty transcript, and pending response queue ordering

## 5. Agent PTT controller

- [ ] 5.1 Implement `AgentPttController` press/release flow using `CaptureService` and the active `ResolvedAudioRoute`
- [ ] 5.2 Reuse the existing Parakeet transcription path to convert completed `RecordedPcm` into transcript text
- [ ] 5.3 Submit non-empty transcripts to `AgentJobRunner` and release the capture route before the LLM call completes
- [ ] 5.4 Treat empty audio or empty transcript as no-job terminal states
- [ ] 5.5 Expose Agent channel statuses for Recording, Transcribing, Thinking, Pending, Synthesizing, Playing, Idle, and Error
- [ ] 5.6 Unit-test capture release and no-job behavior across empty audio/transcript and transcription failure

## 6. Response playback coordinator

- [ ] 6.1 Implement `AgentResponsePlaybackCoordinator` that observes active channel, pending responses, audio-idle state, and Agent channel readiness
- [ ] 6.2 Synthesize one oldest pending response with Supertonic when Agent is active and audio is idle
- [ ] 6.3 Keep responses pending when another channel is active or audio is busy
- [ ] 6.4 Auto-play the oldest pending response when the user switches back to the Agent channel
- [ ] 6.5 Mark exactly one response heard after playback completes and leave any later pending responses queued
- [ ] 6.6 Unit-test response-arrival-active, response-arrival-inactive, switch-back autoplay, and audio-busy retry behavior

## 7. Foreground service dispatch integration

- [ ] 7.1 Initialize Agent channel model, runner, controller, job runner, and playback coordinator in `PttForegroundService`
- [ ] 7.2 Extend `decidePttDispatch` and PTT press/release branching to include Agent channel dispatch
- [ ] 7.3 Extend `forceReleaseActivePtt`, disconnect, and service destroy cleanup for Agent capture/controller state
- [ ] 7.4 Ensure Agent job thinking state does not affect `activePttSession` or `CarMediaPttState.Recording`
- [ ] 7.5 Update active-channel switching to trigger Agent pending response playback when applicable
- [ ] 7.6 Add service-level tests for switching to Journal while Agent is thinking and switching back to trigger playback

## 8. Python agent bridge

- [ ] 8.1 Add Python package/source layout consumed by Chaquopy under the Android app module
- [ ] 8.2 Implement `run_agent(request: dict) -> dict` with a Pydantic AI agent using the OpenAI-compatible provider pointed at OpenRouter
- [ ] 8.3 Keep OpenRouter API key and model configuration outside hardcoded secrets
- [ ] 8.4 Return structured JSON-compatible errors for missing provider credentials, model errors, and unexpected failures
- [ ] 8.5 Add Python-side lightweight tests or smoke checks where the repository tooling can run them

## 9. UI and car-surface projection

- [ ] 9.1 Add Agent channel card/configuration state to the main dashboard without adding a chat UI
- [ ] 9.2 Show Agent channel readiness and runtime statuses including Thinking and pending count
- [ ] 9.3 Ensure Android Auto browse projection includes Agent channel and its pending count subtitle
- [ ] 9.4 Ensure now-playing metadata does not imply active recording while an Agent job is merely thinking
- [ ] 9.5 Update UI/projection tests for Agent channel status and pending count behavior

## 10. Verification and documentation

- [ ] 10.1 Run `nix develop --no-write-lock-file -c gradle test`
- [ ] 10.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug`
- [ ] 10.3 Manually verify Agent channel PTT: capture, transcription, LLM response, Supertonic playback when active
- [ ] 10.4 Manually verify non-blocking behavior: start Agent job, switch to Journal, record Journal while Agent is thinking
- [ ] 10.5 Manually verify pending behavior: response arrives while Journal active, switch back to Agent, oldest pending response auto-plays
- [ ] 10.6 Update `STATUS.md` or relevant project documentation with verified device behavior and known Chaquopy/Pydantic AI constraints
