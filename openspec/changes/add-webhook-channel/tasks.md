## 1. Channel Model And Persistence

- [x] 1.1 Add `WebhookVerb`, `WebhookHeader`, `WebhookChannel`, and webhook status models to `Channel.kt` / `Models.kt` with readiness validation for URL, verb, headers, and `{{message}}` body placeholder.
- [x] 1.2 Extend `AppState`, `Channel.orderIndex`, `projectChannelBrowseEntries`, and `orderedChannelIds` so Webhook Channel appears in stable channel ordering across phone and Android Auto surfaces.
- [x] 1.3 Extend `ChannelRepository` to load/save Webhook Channel URL, verb, headers, body template, active channel ID, and default configuration through SharedPreferences.
- [x] 1.4 Add model/repository tests covering Webhook Channel readiness, persistence round trip, ordering, and dispatch decision readiness/error-beep behavior.

## 2. Webhook Request Pipeline

- [x] 2.1 Add pure request-template rendering that replaces every `{{message}}` occurrence in the body template and preserves configured URL, verb, and headers.
- [x] 2.2 Add a `WebhookClient` boundary plus Android HTTP implementation with bounded timeout and success/non-success/error result mapping.
- [x] 2.3 Add unit tests for placeholder replacement, multiple placeholders, header inclusion, invalid header rejection, and failed/non-success response mapping.
- [x] 2.4 Add required Android network permission if the manifest does not already allow outbound webhook requests.

## 3. PTT Capture And Dispatch

- [x] 3.1 Implement `WebhookPttController` using `CaptureService`, resolved audio route, and `PcmTranscriber` to record on PTT press, transcribe on release, and invoke the webhook after non-empty transcription.
- [x] 3.2 Handle empty audio, transcription failure, webhook delivery failure, cancellation, and route release without retrying delivery or sending raw audio.
- [x] 3.3 Initialize Webhook Channel controller after STT readiness resolves, using the same real/failing transcriber pattern as Journal initialization.
- [x] 3.4 Extend `PttForegroundService` dispatch press/release/cancel branches and `PttDispatchDecision` to include Webhook Channel.
- [x] 3.5 Add service/controller tests for ready dispatch, not-ready error-beep decision, successful PTT-to-webhook flow, empty audio suppression, transcription failure suppression, and webhook failure status.

## 4. User Interface

- [x] 4.1 Add Webhook Channel card content to `MainDashboardScreen` with activation and phone-side PTT behavior matching existing functional channel cards.
- [x] 4.2 Add `WebhookChannelConfigScreen` with editable URL, verb selector, header editor, body template editor, placeholder hint for `{{message}}`, and activate/deactivate control.
- [x] 4.3 Extend `PttUiActions`, `MainActivity` routing, and service setters so UI edits persist immediately and apply to the next capture.
- [x] 4.4 Add UI tests or Compose-level tests for rendering the Webhook Channel card, opening configuration, editing settings, and displaying readiness/status.

## 5. Verification

- [x] 5.1 Run `nix develop --no-write-lock-file -c gradle test` and fix failures caused by this change.
- [x] 5.2 Run `nix develop --no-write-lock-file -c gradle assembleDebug` and fix build failures caused by this change.
- [ ] 5.3 On device, configure a local/test webhook endpoint, select Webhook Channel, perform a PTT capture, and verify the endpoint receives the transcript text but no audio payload.
