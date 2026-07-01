## Why

Subspace needs a concrete demo channel that shows the product model beyond local diagnostics and local journaling: speak into a selected channel, transcribe locally, then forward the resulting text to an external automation endpoint. A Webhook Channel demonstrates the shape of future programmable channels while keeping the first implementation small and explicit.

## What Changes

- Add a built-in channel named "Webhook Channel".
- Route accepted PTT voice captures on that channel through the existing on-device Parakeet transcription path.
- Invoke a user-configured HTTP webhook with the transcribed message text.
- Add a dedicated Webhook Channel settings surface where the user can configure URL, HTTP verb, headers, and request body template.
- Support a special message placeholder in the configured request body so the transcript can be inserted into the webhook payload.
- Expose readiness based on the webhook configuration needed to safely accept a PTT capture.

## Capabilities

### New Capabilities
- `webhook-channel`: Defines the built-in Webhook Channel, its configuration surface, readiness rules, Parakeet transcription flow, request templating, and HTTP webhook invocation behavior.

### Modified Capabilities

None.

## Impact

- Android channel model and repository entries for the new built-in channel.
- Main dashboard/channel card presentation and dedicated settings screen for webhook configuration.
- PTT channel controller wiring to reuse the existing capture and Parakeet transcription path.
- New HTTP client boundary for demo webhook delivery.
- Persistent channel configuration for URL, verb, headers, and body template.
- Tests for readiness, placeholder substitution, HTTP request construction, and PTT-to-webhook control flow.
