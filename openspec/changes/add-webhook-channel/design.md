## Context

Subspace already models channels as sealed `Channel` implementations with per-channel readiness, persisted configuration through `ChannelRepository`, dashboard/configuration UI through `PttUiActions`, and explicit PTT dispatch branches in `PttForegroundService`. Journal already proves the production channel path for capture plus Parakeet transcription, while Debug Channel proves selectable diagnostic modes.

The Webhook Channel should be a demo-grade production-shaped channel: a user selects it, holds PTT, speaks, releases, the app transcribes locally with Parakeet, then sends only the resulting text to a configured HTTP endpoint.

## Goals / Non-Goals

**Goals:**
- Add a built-in `WebhookChannel` model with URL, HTTP verb, headers, and body-template configuration.
- Persist Webhook Channel configuration and include the channel in dashboard and Android Auto browse ordering.
- Add a dedicated Webhook Channel settings screen with editable URL, verb, headers, and body template.
- Reuse the existing capture route resolution and Parakeet transcription readiness path.
- Invoke an HTTP webhook by replacing `{{message}}` in the configured body template with the transcript text.
- Surface channel readiness through existing `isReady` routing so not-ready PTT emits the standard error beep.

**Non-Goals:**
- No real third-party integration catalog, OAuth flow, secret vault, or credential manager.
- No webhook retry queue, offline delivery, exponential backoff, or durable delivery log.
- No inbound webhook response handling, TTS response playback, backlog message creation, or channel history.
- No audio upload; only transcript text may leave the device.
- No configurable placeholder syntax beyond the single `{{message}}` body placeholder.
- No background webhook invocation after app/service teardown.

## Decisions

### D1: Model Webhook Channel as a first-class sealed channel

Add `WebhookChannel` beside `JournalChannel` and `DebugChannel`, then extend `AppState`, `ChannelRepository.loadChannels`, `Channel.orderIndex`, `projectChannelBrowseEntries`, `orderedChannelIds`, `PttDispatchDecision`, and explicit dispatch branches.

Alternative considered: represent the webhook demo as a Debug Channel mode. Rejected because it has persistent user configuration, network side effects, and channel readiness semantics that match a real channel rather than a diagnostic mode.

### D2: Use SharedPreferences persistence matching existing channels

Persist `url`, `verb`, `headers`, and `bodyTemplate` through `ChannelRepository`, using simple strings for URL/body and a stable encoded string for headers. Keep the first implementation aligned with current channel configuration persistence instead of introducing a database.

Alternative considered: use Room or DataStore. Rejected for this change because existing channel persistence is SharedPreferences and the demo does not need schema migration or relational queries.

### D3: Treat `{{message}}` as the only body placeholder

The request body template replaces every `{{message}}` occurrence with the transcript text before invoking the webhook. Readiness requires a nonblank HTTP(S) URL, a supported verb, and a body template containing `{{message}}`.

Alternative considered: support placeholders in headers or URL. Rejected to keep the demo explicit and avoid accidental text leakage into path/query/header surfaces.

### D4: Create a small HTTP boundary instead of wiring networking into the controller

Introduce a `WebhookClient` interface and Android implementation that constructs the HTTP request from a pure `WebhookRequest` value. Keep request-template rendering in pure code so URL/verb/header/body tests do not need Android or network.

Alternative considered: call the platform HTTP API directly from the PTT controller. Rejected because it makes request construction harder to test and couples capture/transcription lifecycle code to network details.

### D5: Reuse capture-service plus Parakeet transcription lifecycle

Implement `WebhookPttController` similarly to the STT/Journaling path: start a capture session on PTT press, stop on release, transcribe the recorded PCM with the existing `PcmTranscriber`, render the request body, and invoke the webhook on a background dispatcher. If STT initialization fails, the controller remains constructed with a failing transcriber so the channel can report invocation failure without crashing the service.

Alternative considered: share `SttController` directly. Rejected because `SttController` owns debug UI state and stops at transcript status; Webhook Channel needs post-transcription network dispatch and channel-specific status.

### D6: Keep delivery best-effort and foreground-session-bound

The demo sends once after successful transcription and records a visible status in app state. Failures are surfaced but not retried or persisted.

Alternative considered: durable queue with retry. Rejected as out of scope for a demo channel and because it would introduce background-work, connectivity, and secret-management concerns.

## Risks / Trade-offs

- Network calls can fail or hang -> run webhook delivery off the main thread with a bounded timeout and surface failure state.
- User-entered headers can be malformed -> parse settings into validated name/value pairs before readiness becomes true.
- Transcripts may contain characters requiring escaping -> render body template with JSON-safe escaping when the template is JSON-shaped, and cover literal substitution with unit tests.
- GET/DELETE request bodies are not uniformly supported -> allow the configured verb but keep the first implementation body-template based because this is a demo; document any platform limitation in status if sending fails.
- Sending transcripts to arbitrary URLs is privacy-sensitive -> never send raw audio, require explicit URL configuration, and keep only the transcript text in the outgoing body.
