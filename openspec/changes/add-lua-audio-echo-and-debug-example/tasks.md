## 1. Prerequisite: Baseline And Contract References

- [ ] 1.1 Sync the completed `establish-lua-channel-runtime-v1` delta specs into the main specs and archive that change before editing the Lua adapter for this change
- [ ] 1.2 Confirm the archived `lua-channel-api` and `lua-channel-provider` requirements contain the input-outcome and provider-registration contracts this change extends
- [ ] 1.3 Record `lsp references` for `LuaAdapterRuntime`, `LuaAdapterRuntime.onInputReleased`, `strictInputStatus`, `ChannelInputResult`, `ChannelInputResult.PlaybackOperation`, `LuaChannelImplementationProvider`, `ChannelImplementationDescriptor`, `opaqueAudioRecording`, `AudioOperation`, and `PttAudioSessionManager` before changing exported contracts
- [ ] 1.4 Run the existing focused Lua-adapter and channel-runtime-registry contract tests as the pre-change baseline

## 2. Playback-Request Outcome Contract

- [ ] 2.1 Extend the `handle_input` outcome parsing in `LuaAdapterRuntime` to extract both the execution status and an optional playback directive from the returned table
- [ ] 2.2 Accept `{ ok = true, play = <session string> }` as a valid success-with-playback outcome while preserving exact acceptance of `{ ok = true }` and `{ error = { code, detail } }`
- [ ] 2.3 Reject malformed playback directives — non-string `play`, `play` alongside `error`, missing `ok = true`, or unexpected keys — as an invalid outcome, not a playback request
- [ ] 2.4 Map a well-formed playback directive to a distinct internal request value carrying the session token, separate from the plain success/failure status
- [ ] 2.5 Add outcome-parser tests covering the new directive, both preserved shapes, and every malformed-directive rejection

## 3. Session Audio Retention And Token Validation

- [ ] 3.1 Retain the session's `RecordedPcm` host-side, keyed by `sessionId`, from `onInputReleased` until the input completes, without exposing it to Lua
- [ ] 3.2 Validate that a playback-request token equals the current input session token before any operation construction
- [ ] 3.3 Fail closed on an unknown, forged, cross-session, or stale token: set `ChannelExecutionStatus.FAILED`, emit a structured `subspace.log` rejection entry, and produce no playback
- [ ] 3.4 Release the session's retained recording when the input session completes so it cannot be referenced afterward
- [ ] 3.5 Add validation tests for wrong-token, forged-token, cross-session, and post-completion (stale) rejection paths

## 4. Host-Side Playback Operation Construction

- [ ] 4.1 Make `LuaAdapterRuntime` hold the `AudioOperation` capability for building playback operations host-side
- [ ] 4.2 On a validated request, build the `OpaqueAudioOperation` from the session's retained recording via `AudioOperation.createPlaybackResult(opaqueAudioRecording(pcm))`
- [ ] 4.3 Return `ChannelInputResult.PlaybackOperation` for the built operation so `PttAudioSessionManager` schedules playback through the existing pipeline
- [ ] 4.4 Return `ChannelInputResult.None` with pipeline-recorded failure when the `AudioOperation` capability is unavailable or the retained audio is empty or absent
- [ ] 4.5 Keep `Lua` free of any scheduling, admission, or playback capability; the adapter returns the operation and the host pipeline owns the rest
- [ ] 4.6 Add construction tests for the success path, the capability-unavailable path, and the empty/absent-audio path

## 5. Bundled Fixed-Mode Echo Channel

- [ ] 5.1 Author the Lua echo program: an entry module returning a validated callback table with `startup` and a `handle_input` that returns `{ ok = true, play = <session> }` using the session token from its event argument
- [ ] 5.2 Add an optional `subspace.log` entry in the echo program recording the capture event for on-device observability
- [ ] 5.3 Bundle the echo program as an immutable program image from an application asset with correct `luaVersion` and `apiVersion` requirement metadata
- [ ] 5.4 Define the bundled channel's `ChannelImplementationDescriptor` with `requiredCapabilities = setOf(ChannelCapability.AudioOperation)`, no configuration fields, and fixed-mode presentation metadata
- [ ] 5.5 Keep program-image identity and source out of persisted `ChannelDefinition` configuration

## 6. Startup Registration

- [ ] 6.1 Register a `LuaChannelImplementationProvider` for the bundled echo channel at startup alongside the four Kotlin providers in the service bootstrap
- [ ] 6.2 Ensure registration creates no Lua state or actor; a runtime is constructed only when a channel definition referencing the provider is constructed
- [ ] 6.3 Add registration tests proving the provider is present after startup and that no actor exists before a referencing definition is constructed

## 7. Verification

- [ ] 7.1 Run the full JVM unit-test suite for the Lua adapter, outcome parsing, validation, and construction paths
- [ ] 7.2 Build the debug APK and install it on the target device (`B02PTT-FF01`)
- [ ] 7.3 On device, select the bundled echo channel, construct its runtime, and complete a PTT capture; confirm audible echo through the active audio route
- [ ] 7.4 Confirm on device that a malformed or stale playback request logs a rejection and produces no playback
- [ ] 7.5 Capture device evidence (run manifest + evidence record) for the echo round trip
