## Why

Subspace Lua Runtime v1 defines the public contract and proves it with black-box fixtures, but no Lua program performs a real channel behavior: the Lua input path discards every callback return value except an execution status and always yields `ChannelInputResult.None`, so a Lua channel cannot produce audio. Exercise the engine with a first real behavior — PTT audio echo — through the live, host-owned playback seam, before packaging, installation, or configurable channel migration come to depend on an unproven input path.

## What Changes

- Define the captured-audio handle contract: the input `session` token is the opaque handle for the audio captured in that input session, valid only for the duration of that session's `handle_input` callback.
- Extend the `handle_input` outcome contract to accept a playback-request directive `{ ok = true, play = <session> }` in addition to the existing `{ ok = true }` and `{ error = { code, detail } }` shapes.
- Translate a validated playback request into `ChannelInputResult.PlaybackOperation`, built host-side through the adapter's `AudioOperation` capability and scheduled by the existing `PttAudioSessionManager` pipeline. The host retains the final playback decision; unknown, stale, or cross-session tokens fail closed with no playback.
- Bundle one fixed-mode Lua echo channel as an Android asset (an immutable program image) and register a `LuaChannelImplementationProvider` for it at startup alongside the four Kotlin providers — the explicit production registration change the v1 spec anticipated.
- Do not define the package/archive/manifest format, GitHub repository identity, installation, discovery, update, rollback, signing, program-image capability declaration, declarative configuration, STT/TTS/text-output/conversation capabilities, asynchronous or proactive playback, unsolicited interruption policy, or plugin-author tooling in this change. Do not replace, modify, or remove the existing Kotlin Debug channel.

## Capabilities

### New Capabilities

- `lua-channel-audio`: Captured-audio handle semantics and the synchronous, host-owned playback-request outcome for Lua channel input, including token validation and fail-closed behavior.

### Modified Capabilities

None. This change is additive. It layers the audio outcome on the `lua-channel-api` input contract without altering any existing v1 requirement. (`lua-channel-api` is introduced by `establish-lua-channel-runtime-v1`, which is complete but not yet archived, so no existing main spec is modified here.)

## Impact

- Lua channel surface: additive playback-request outcome on `handle_input`; no change to existing v1 callbacks, reserved modules, value normalization, or the established `{ ok = true }` / `{ error = … }` result shapes.
- Android integration: the `LuaAdapterRuntime` input path translates a validated playback directive into `ChannelInputResult.PlaybackOperation`; the adapter holds the `AudioOperation` capability to build the operation host-side; the bundled channel's descriptor declares `AudioOperation`.
- App bootstrap: a Lua program image is bundled as an asset and a `LuaChannelImplementationProvider` is registered at startup (ordinary startup previously registered no Lua provider). No catalogue, persistence, or configuration-schema change.
- Verification: JVM tests for outcome translation, session-token validation, and fail-closed rejection; physical-device instrumentation proving PTT → capture event → Lua `play` directive → audible echo through the headset.
- Existing Kotlin providers, PTT/audio routing, capability host, foreground-service behavior, permissions, SDK levels, and release signing otherwise unchanged.
- Ordering: builds on `establish-lua-channel-runtime-v1` (complete); that change should be archived before this one is applied and archived.
