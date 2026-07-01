## ADDED Requirements

### Requirement: Webhook Channel type
The system SHALL provide a built-in channel type named "Webhook Channel" that transcribes accepted PTT captures with the on-device Parakeet model and sends the transcript text to a configured HTTP webhook.

#### Scenario: Channel exists in channel list
- **WHEN** the app is launched
- **THEN** the Webhook Channel SHALL appear as an available channel
- **AND** the channel SHALL participate in the same active-channel selection, readiness projection, and channel ordering surfaces as the other built-in channels

#### Scenario: Channel receives selected PTT capture
- **WHEN** the Webhook Channel is active and ready and the user completes a PTT capture
- **THEN** the system SHALL transcribe the captured voice with Parakeet
- **AND** the system SHALL invoke the configured webhook with the transcribed text

### Requirement: Webhook Channel configuration screen
The system SHALL provide a dedicated Webhook Channel configuration screen where the user can edit URL, HTTP verb, headers, and request body template.

#### Scenario: Configuration screen opened
- **WHEN** the user opens the Webhook Channel configuration screen
- **THEN** the system SHALL show editable controls for the webhook URL, HTTP verb, headers, and body template
- **AND** the screen SHALL show that `{{message}}` is the placeholder for the transcribed message text in the body template
- **AND** the screen SHALL provide an activate/deactivate control for the channel

#### Scenario: Configuration changes persist
- **WHEN** the user changes the Webhook Channel URL, verb, headers, or body template
- **THEN** the system SHALL persist the changed configuration
- **AND** the changed configuration SHALL be restored after app restart
- **AND** the changed configuration SHALL apply to the next accepted PTT capture without restarting the service

### Requirement: Webhook Channel readiness
The system SHALL consider the Webhook Channel ready only when it has a valid HTTP(S) URL, a supported HTTP verb, and a body template containing the `{{message}}` placeholder.

#### Scenario: Complete webhook configuration
- **WHEN** the Webhook Channel has a nonblank `http` or `https` URL, a supported HTTP verb, valid headers, and a body template containing `{{message}}`
- **THEN** the channel readiness state SHALL evaluate to true

#### Scenario: Missing URL
- **WHEN** the Webhook Channel URL is blank
- **THEN** the channel readiness state SHALL evaluate to false

#### Scenario: Missing message placeholder
- **WHEN** the Webhook Channel body template does not contain `{{message}}`
- **THEN** the channel readiness state SHALL evaluate to false

#### Scenario: Invalid header configuration
- **WHEN** any configured header lacks a valid header name or value
- **THEN** the channel readiness state SHALL evaluate to false

### Requirement: Webhook request construction
The system SHALL construct each webhook request from the saved channel configuration by using the configured URL, HTTP verb, headers, and body template with every `{{message}}` placeholder replaced by the transcript text.

#### Scenario: Body placeholder is replaced
- **WHEN** the configured body template is `{"text":"{{message}}"}` and Parakeet returns `hello road`
- **THEN** the webhook request body SHALL contain `hello road` in place of `{{message}}`

#### Scenario: Multiple body placeholders are replaced
- **WHEN** the configured body template contains more than one `{{message}}` placeholder
- **THEN** the webhook request body SHALL replace every `{{message}}` occurrence with the same transcript text

#### Scenario: Headers are configured
- **WHEN** the user configures one or more webhook headers
- **THEN** the webhook request SHALL include those headers with their configured names and values

### Requirement: PTT-to-webhook processing
The system SHALL record Webhook Channel PTT audio through the resolved active input mode route, transcribe the finalized PCM with Parakeet, and invoke the webhook only after a non-empty transcript is available.

#### Scenario: PTT is pressed while Webhook Channel is ready
- **WHEN** the Webhook Channel is active and ready and the user presses PTT
- **THEN** the system SHALL resolve the audio route for the active input mode
- **AND** the system SHALL start recording mono PCM audio for transcription

#### Scenario: PTT is released after speech is recorded
- **WHEN** the Webhook Channel is recording and the user releases PTT after audio has been captured
- **THEN** the system SHALL stop recording
- **AND** the system SHALL submit the captured audio to the local Parakeet transcriber
- **AND** the system SHALL invoke the configured webhook after transcription succeeds

#### Scenario: Empty audio is captured
- **WHEN** the Webhook Channel capture finalizes with no audio samples
- **THEN** the system SHALL NOT invoke Parakeet
- **AND** the system SHALL NOT invoke the webhook
- **AND** the system SHALL expose an empty-audio status for the channel

#### Scenario: Transcription fails
- **WHEN** Parakeet transcription fails for a Webhook Channel capture
- **THEN** the system SHALL NOT invoke the webhook
- **AND** the system SHALL expose a transcription error status for the channel

#### Scenario: Webhook invocation fails
- **WHEN** the configured webhook returns a network error, timeout, or non-success response
- **THEN** the system SHALL expose a webhook delivery error status for the channel
- **AND** the system SHALL NOT retry the webhook automatically

### Requirement: Webhook privacy boundary
The system SHALL NOT send captured audio to the webhook endpoint. Only the transcribed text inserted into the configured body template may leave the device for this channel.

#### Scenario: Webhook invocation occurs
- **WHEN** the Webhook Channel invokes the configured webhook after a successful transcription
- **THEN** the system SHALL send the rendered request body and configured headers
- **AND** the system SHALL NOT include raw PCM, WAV, OGG, or other audio payload data in the request
