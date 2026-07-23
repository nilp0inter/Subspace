# Design: Lua audio echo + bundled Debug example

## Context

Subspace Lua Runtime v1 (`establish-lua-channel-runtime-v1`, complete, unarchived) defines the public Lua contract and proves it with immutable fixtures. But the production Lua input path is inert: `LuaAdapterRuntime.onInputReleased` builds the capture event, invokes `handle_input` synchronously, maps the return value to a `ChannelExecutionStatus` via `strictInputStatus()`, and **always returns `ChannelInputResult.None`**. A Lua channel can observe input but cannot act on it.

Two facts make a minimal, correct first behavior available now:

- The capture event already carries `session = <sessionId>` (a valid-UTF-8 string) into Lua. Under v1 normalization, only `nil`, boolean, finite number, string, and plain table may cross the boundary — userdata is rejected — so any handle must be a string or plain table. The session token already qualifies.
- `ChannelInputResult.PlaybackOperation(OpaqueAudioOperation)` is a live-but-unused seam: `PttAudioSessionManager` consumes it (and `Playback`) and routes audio through the host playback pipeline. No current channel returns it — Kotlin Debug self-schedules via the `DeferredAudioPlayback` capability and returns `None`.

The vision (`PLUGIN_SYSTEM_VISION.md`, Durable Output and Playback) is explicit: *"The plugin may express the content and requested delivery intent. The host makes the final playback decision."* Playback intent as a returned directive is the vision-aligned shape.

## Goals / Non-Goals

**Goals:**

- Let a Lua channel request host playback of the audio captured in the current input session, synchronously, from `handle_input`.
- Keep the raw `RecordedPcm` host-side; Lua only ever holds the opaque session token.
- Keep the final playback decision, half-duplex admission, and routing host-owned by reusing the existing `ChannelInputResult.PlaybackOperation` pipeline.
- Prove the engine end-to-end on a physical device with one fixed-mode bundled echo channel.
- Leave every existing v1 contract (callbacks, modules, value shapes, fail-closed validation) unchanged.

**Non-Goals:**

- Package/archive/manifest format, GitHub identity, installation, discovery, update, rollback, signing, provenance.
- Program-image capability declaration (a `requires` field) — deferred to the STT/TTS leg where Lua declares needs.
- STT, TTS, text-output, conversation, or any capability beyond the host-side `AudioOperation` used to build the playback operation.
- Declarative configuration; the bundled channel is fixed-mode.
- Asynchronous, deferred, proactive, or unsolicited playback; the decision is synchronous within `handle_input`.
- Replacing, modifying, or removing the existing Kotlin Debug channel or any Kotlin provider.
- A distinct synthesized-audio handle family (arrives with TTS).

## Decisions

### D1. Playback intent is a returned directive, not an imperative host call

`handle_input` returns `{ ok = true, play = <session> }`; the adapter translates this into `ChannelInputResult.PlaybackOperation` and the existing pipeline schedules it.

**Rationale:** v1 event callbacks are synchronous and non-yielding — returning a table needs no host operation. The vision assigns the final playback decision to the host; a returned directive is a *request* the host can validate and veto, not a *command*. The `PlaybackOperation` seam already routes through `PttAudioSessionManager`, so admission, routing, and failure recording stay in one host-owned place.

**Alternative considered:** an imperative `subspace.audio.playback(session)` host call from inside the callback. Rejected — it hands scheduling to Lua (against the vision's ownership split), forces a non-yielding host operation mid-callback with its own error model, and bypasses the live return seam.

### D2. The captured-audio handle IS the session token

No new handle is minted. The host retains the session's `RecordedPcm` for the life of the input session, keyed by `sessionId`. The token Lua already receives is the handle; the host looks the recording up by that token when a playback request arrives.

**Rationale:** the session token already crosses the boundary legally (string), is unique per input, and is exactly the granularity echo needs. It avoids introducing handle-lifecycle machinery for a single handle family.

**Alternative considered:** mint a distinct opaque audio handle per capture. Rejected for this change — added lifecycle machinery with only one family in play. Synthesized-audio output (TTS) will introduce a separate, operation-scoped handle family later; the two families never share a namespace, so there is no coupling to break.

### D3. Outcome shape adds one key: `{ ok = true, play = <session> }`

The strict outcome parser is extended to extract the execution status **and** an optional playback directive. `{ ok = true }` (no playback) and `{ error = { code, detail } }` (failure, no playback) are unchanged. The only new accepted shape is `{ ok = true, play = <session> }`.

**Rationale:** an additive key is the smallest delta to the existing strict `{ ok = true }` / `{ error = … }` contract and keeps status orthogonal to intent.

**Alternative considered:** restructure to `{ result = "ok", play = … }`. Rejected — it rewrites the established status shape for no benefit and breaks the existing parser's accepted forms.

### D4. The host builds the operation via the adapter's own `AudioOperation` capability

The adapter holds the `AudioOperation` capability to run `createPlaybackResult(opaqueAudioRecording(pcm))` for the session's retained recording. The bundled channel's `ChannelImplementationDescriptor` declares `requiredCapabilities = setOf(ChannelCapability.AudioOperation)`, satisfying the existing `RevocableChannelCapabilityScope.isDeclared` gate. **Lua declares no capabilities; no program-image `requires` field is introduced.**

**Rationale:** the operation must be built from the host-retained PCM, which only the host can touch. The declaration is a host-side descriptor concern for this bundled channel; exposing a declaration contract to Lua program images is a separate, later design.

**Alternative considered:** declare `DeferredAudioPlayback` too and let the adapter self-schedule like Kotlin Debug. Rejected — self-scheduling bypasses the `PlaybackOperation` return seam and contradicts D1's host-ownership decision.

### D5. Fail-closed token validation

On a playback request the host requires the token to equal the current input session. Unknown, stale, or cross-session tokens yield `ChannelExecutionStatus.FAILED`, a structured `subspace.log` entry, and `ChannelInputResult.None` (no playback). An empty/absent recording or an unavailable `AudioOperation` capability likewise yields no playback through the existing pipeline's failure recording.

**Rationale:** consistent with v1's fail-closed validation; a silent ignore would hide plugin bugs and make the echo channel hard to diagnose on device.

**Alternative considered:** ignore-and-succeed on a bad token. Rejected — silent failure is worse than an explicit, logged rejection.

### D6. Bundle the echo channel as an immutable asset; register at startup

The fixed-mode echo channel ships as an immutable program image bundled as an Android asset. A `LuaChannelImplementationProvider` is registered for it at startup alongside the four Kotlin providers — the explicit production registration the v1 spec reserved. The Kotlin Debug channel is untouched.

**Rationale:** registration is cheap (v1 guarantees no Lua state or actor is created at registration — only when a catalogue definition references the provider), so ordinary startup cost and stability are preserved. Keeping Kotlin Debug alongside preserves the Kotlin test path and all five configurable modes.

**Alternative considered:** replace the Kotlin Debug channel with the Lua echo. Rejected — premature; full Debug migration needs declarative configuration and packaging, both explicitly out of scope.

### D7. The playback decision is synchronous within `handle_input`

The directive is only meaningful as the return value of the current `handle_input`. Spawned tasks and other callbacks cannot request playback of a past session.

**Rationale:** matches the session-scoped handle lifetime (D2) and the synchronous-callback contract (D1). Proactive/asynchronous output is the vision's separate durable-pending-message concern, not this change.

## Risks / Trade-offs

- **Audio payload leak to Lua** → Lua never receives PCM; only the opaque session token crosses. The host builds the operation from host-retained `RecordedPcm`; the token is a lookup key, not the audio.
- **Token forgery / confusion** → a plugin can fabricate a session string. Host validates the token equals the current input session and fails closed otherwise (D5), so a forged token yields logged rejection, never playback.
- **Handle used after session end** → token validity is scoped to the input session; the host rejects stale tokens (D5, D7). Retained PCM is released when the session completes.
- **Startup regression from registering a Lua provider** → v1 guarantees registration creates no Lua state/actor; the asset image is loaded only when a definition references the provider. Boot path otherwise unchanged.
- **Scope creep toward full Debug migration** → this change is one fixed mode via one directive. Configuration, the other four modes, and replacing Kotlin Debug are explicitly non-goals.

## Open Questions

- Exact synthesized-audio handle family and its lifetime (TTS leg).
- Program-image capability-declaration contract for when Lua declares STT/TTS needs (STT/TTS leg).
- Whether `handle_input` later carries additional directives (e.g., transcribe) and how multiple directives compose with `play`.
