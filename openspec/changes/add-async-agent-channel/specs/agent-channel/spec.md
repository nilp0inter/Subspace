## ADDED Requirements

### Requirement: Agent channel accepts PTT speech as asynchronous agent input
The system SHALL provide a built-in Agent channel that accepts PTT audio, transcribes the completed capture with Parakeet, and submits the resulting text to an on-device Pydantic AI agent hosted through embedded Python.

#### Scenario: PTT capture submits transcript to agent job runner
- **WHEN** PTT is pressed and released while the Agent channel is active and ready
- **THEN** the system SHALL capture audio using the active input mode route
- **AND** the system SHALL transcribe the completed capture with Parakeet
- **AND** the system SHALL submit a new asynchronous agent job with the transcript text
- **AND** the system SHALL release the capture route before the agent LLM call completes

#### Scenario: Empty transcript does not submit an agent job
- **WHEN** the Agent channel captures audio but Parakeet returns an empty transcript
- **THEN** the system SHALL NOT submit an agent job
- **AND** the system SHALL return the Agent channel to an idle state

#### Scenario: Agent input excludes raw audio
- **WHEN** the Agent channel invokes the Python agent bridge
- **THEN** the bridge request SHALL include text and channel/session metadata only
- **AND** the bridge request SHALL NOT include raw PCM audio samples

### Requirement: Agent processing does not block other channels
The system SHALL run Agent channel LLM work independently of active PTT session ownership, capture service ownership, and audio route ownership.

#### Scenario: User records Journal while agent is thinking
- **WHEN** an Agent channel job is waiting for an LLM response
- **AND** the user switches to the Journal channel
- **AND** the user presses and releases PTT
- **THEN** the system SHALL route the new capture to the Journal channel
- **AND** the system SHALL NOT reject the Journal capture because of the pending Agent channel job

#### Scenario: Agent job does not hold capture session
- **WHEN** an Agent channel job has been submitted after transcription
- **THEN** the capture service SHALL report no active capture session for that completed PTT cycle
- **AND** subsequent PTT dispatches SHALL be evaluated normally against the currently active channel

#### Scenario: Agent job does not hold audio route
- **WHEN** an Agent channel job is waiting for an LLM response
- **THEN** the SCO, Telecom, or local output route used for capture SHALL NOT remain acquired by that job

### Requirement: Agent response playback is gated by active channel
The system SHALL speak Agent channel responses through Supertonic only when the Agent channel is active and audio playback is idle.

#### Scenario: Response arrives while Agent channel is active
- **WHEN** an Agent channel job produces response text
- **AND** the Agent channel is the active channel
- **AND** no capture or response playback is active
- **THEN** the system SHALL synthesize the response text with Supertonic
- **AND** the system SHALL play the synthesized response through the current playback route
- **AND** the system SHALL mark the response as heard after playback completes

#### Scenario: Response arrives while another channel is active
- **WHEN** an Agent channel job produces response text
- **AND** a channel other than the Agent channel is active
- **THEN** the system SHALL store the response as pending
- **AND** the system SHALL NOT play the response immediately

#### Scenario: Switching back to Agent channel auto-plays oldest pending response
- **WHEN** the Agent channel becomes active
- **AND** at least one Agent response is pending
- **AND** no capture or response playback is active
- **THEN** the system SHALL synthesize and play the oldest pending response
- **AND** the system SHALL mark exactly that response as heard after playback completes

#### Scenario: Audio busy keeps response pending
- **WHEN** an Agent response is ready
- **AND** the Agent channel is active
- **AND** a capture or response playback is already active
- **THEN** the system SHALL keep the response pending
- **AND** the system SHALL retry playback when the audio system becomes idle and the Agent channel remains active

### Requirement: Agent channel exposes thinking and pending states
The system SHALL expose Agent channel state transitions sufficient for the dashboard and car surfaces to distinguish capture, transcription, thinking, pending response, playback, and errors.

#### Scenario: Agent job is waiting on LLM response
- **WHEN** an Agent channel transcript has been submitted to the Pydantic AI agent
- **AND** no response has been produced yet
- **THEN** the Agent channel state SHALL include a Thinking status

#### Scenario: Agent response waits off-channel
- **WHEN** an Agent response is pending because another channel is active
- **THEN** the Agent channel state SHALL include a pending response count greater than zero

#### Scenario: Agent execution fails
- **WHEN** the Pydantic AI agent call fails
- **THEN** the Agent channel state SHALL include an error for that job
- **AND** the failure SHALL NOT crash the foreground service

### Requirement: Pydantic AI runs behind a narrow embedded Python bridge
The system SHALL invoke Pydantic AI through a narrow Chaquopy bridge whose Kotlin-facing contract is JSON-compatible and fakeable in tests.

#### Scenario: Kotlin invokes agent runner
- **WHEN** Kotlin submits an Agent channel transcript to the agent runtime
- **THEN** Kotlin SHALL call an `AgentRunner`-style port rather than calling Chaquopy APIs from PTT dispatch code
- **AND** the concrete Chaquopy implementation SHALL map Kotlin request data to a Python bridge function

#### Scenario: Python bridge returns response data
- **WHEN** the Python bridge completes an agent run
- **THEN** it SHALL return JSON-compatible data containing the spoken response text or a structured error
- **AND** Kotlin SHALL map that data to typed Agent channel job state

#### Scenario: Python runtime unavailable
- **WHEN** Chaquopy initialization or Pydantic AI import fails
- **THEN** the Agent channel SHALL be not ready
- **AND** PTT on the Agent channel SHALL follow the not-ready channel behavior

### Requirement: Chaquopy and Pydantic AI OpenRouter feasibility is proven before full channel wiring
The implementation SHALL prove that the selected Chaquopy/Pydantic AI/OpenRouter dependency set works on the target Android ABI before relying on it for channel behavior.

#### Scenario: Feasibility spike succeeds
- **WHEN** the feasibility spike runs on the target `arm64-v8a` build
- **THEN** the app SHALL package the embedded Python runtime, `pydantic-ai-slim[openai]`, and Chaquopy-compatible Android wheels for native dependencies including `pydantic-core` and `jiter`
- **AND** Python SHALL import the selected Pydantic AI package successfully
- **AND** Python SHALL instantiate a Pydantic AI OpenAI-compatible model configured with the OpenRouter base URL
- **AND** Kotlin SHALL receive a JSON-compatible response from a minimal Python agent bridge call

#### Scenario: Feasibility spike fails
- **WHEN** the selected Pydantic AI/OpenRouter dependency set cannot be packaged or imported under Chaquopy
- **THEN** implementation SHALL stop before wiring the Agent channel into production dispatch
- **AND** the failure SHALL be documented as a blocker for the embedded-agent approach
