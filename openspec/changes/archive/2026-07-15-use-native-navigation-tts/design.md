## Context

Subspace's RSM hardware navigation produces spoken feedback over the target-RSM SCO headset route. Today the path is:

1. **Bootstrap** (`BootstrapCoordinator.runAttempt`) constructs the Supertonic JNI synthesizer (`SupertonicJniSynthesizer`), polls `TtsSynthesizer.modelStatus` until `Ready`, then calls `SystemAnnouncer.precompute` with the full announcement vocabulary.
2. **Precompute** synthesizes all seven phrases (one menu-entrance phrase plus per-channel name and selected phrases) via the blocking `TtsSynthesizer.synthesize`, converts 44.1 kHz f32 to 16 kHz PCM16 via `TtsAudio.toScoPlayback`, stores them in a `ConcurrentHashMap` and optionally in `AnnouncementPcmCache` (WAV files under `noBackupFilesDir/announcement-cache`, manifest, fingerprints, reconciliation).
3. **Playback** (`PttForegroundService.enqueueRsmAnnouncement`) looks up the cached `RecordedPcm` by key, falls back to `HostAudioFeedback.readyBeep()` on cache miss, and passes it to `HostAudioCoordinator.play` with `ModePlaybackRouteResolver.strategyFor(InputMode.Work)`.
4. **Route + playback** (`HostAudioCoordinator.play` → `ModePlaybackRouteResolver.acquireWork` → `StreamPlaybackRoute.start` → `ActiveStreamPcmPlayback`) acquires the SCO route, creates a MODE_STREAM `AudioTrack` with `setPreferredDevice` to the target-RSM Bluetooth SCO device, pumps PCM through bounded reusable buffers, and awaits `PlaybackCompletion`.

The cost: Supertonic takes 2–4 s per phrase, adding 14–28 s to first-boot after install/cache invalidation, plus the persistent cache machinery (manifests, fingerprints, WAV integrity, mark-and-sweep reconciliation, atomic commits).

The target: replace this with on-demand Android offline `TextToSpeech` that renders each phrase to transient PCM on request — no cache, no eager rendering — while keeping the exact route-ownership and playback path unchanged.

### Stakeholders

- **Bootstrap**: loses the announcement rendering stage and phrase-cache gate; gains a proven-offline-voice prerequisite that runs before core STT/Supertonic initialization.
- **Navigation (RSM control mode)**: changes from cached-PCM lookup to per-request synthesis + playback.
- **Host audio coordination**: gains cancellation-safe teardown ordering for navigation playback, requiring source fixes to existing cancellation/completion contracts.
- **Initial setup**: gains a user-resolvable offline-voice step.
- **Supertonic**: remains a hard bootstrap gate; its general synthesis and channel-capability role is unchanged.

## Goals / Non-Goals

**Goals:**

- Replace cached Supertonic announcement rendering with on-demand Android offline TTS that returns transient normalized 16 kHz mono PCM16.
- Retain exact app-owned device routing: transient PCM flows through the existing `HostAudioCoordinator` → `ModePlaybackRouteResolver` → `StreamPlaybackRoute` → `ActiveStreamPcmPlayback` path with `setPreferredDevice` to the target-RSM SCO device.
- Add a proven installed offline English voice as a hard bootstrap gate that runs as a prerequisite before core STT/Supertonic initialization. The probed Android TTS instance is retained through core preparation.
- Keep Supertonic and Parakeet as hard bootstrap gates.
- Enforce strict latest-wins across synthesis, recovery, and active navigation playback.
- Make host playback cancellation terminal and teardown-ordered: canceling an owning caller stops its exact playback, waits for `AudioTrack` cleanup, releases the route, and clears only the matching coordinator owner. This requires fixing existing cancellation/completion ordering bugs.
- Permit one bounded TTS reinitialization, voice probe, and retry of only the newest pending announcement after a runtime engine failure; if recovery cannot restore the hard gate, transition to `NeedsSetup`.
- Classify failures so missing/unusable voice maps to `NeedsSetup`, runtime engine/voice failures trigger bounded recovery then `NeedsSetup`, and renderer/transient-file/format-infrastructure failures map to retryable `Failed`.
- Remove the persistent and in-memory announcement cache, manifest/fingerprint/WAV reconciliation, cache diagnostics, `AnnouncementResult` precomputation state, ready-beep cache fallback, and announcement rendering progress stage.

**Non-Goals:**

- Changing Supertonic's general synthesis or channel-capability role.
- Changing the half-duplex coordinator's capture arbitration, PTT rejection, SOS-skip, or queue-pause semantics.
- Introducing a third-party TTS runtime dependency.
- Adding persistent storage for synthesized announcement PCM.
- Changing `RecordedPcm`, `PlaybackRouteContracts`, `StreamPlaybackRoute`, `ModePlaybackRouteResolver`, or `ActiveStreamPcmPlayback` beyond the teardown-ordering and completion-contract fixes described below.
- Synthesizing non-navigation phrases (channel content, TTS controller path) with Android TTS.
- Network-based synthesis. Android TTS SHALL be configured for offline-only operation.
- Any audible fallback (beep or otherwise) for a failed navigation announcement. A failed announcement is dropped.

## Decisions

### D1: Navigation voice prerequisite runs before core STT/Supertonic initialization

The Android `TextToSpeech` instance is constructed, initialized, voice-selected, and probed as a **prerequisite** in `BootstrapCoordinator.runAttempt` — before the `coroutineScope` block that launches `prepareStt` and `prepareTts`. This makes the proven offline English voice a co-equal hard gate that is checked independently of (and prior to) the Parakeet/Supertonic native engine initialization. The probed instance is retained for runtime use and survives through core preparation, controller construction, and into the dashboard.

The ordering in `runAttempt` becomes:

1. Check permissions and model assets (existing).
2. **Construct + init + voice-select + probe the Android navigation TTS renderer** (new prerequisite stage). Failure → `NeedsSetup`.
3. Construct + initialize STT and TTS (existing `prepareStt` / `prepareTts`, now in parallel `async` children of the attempt `Job`).
4. Construct controllers (existing).
5. Verify core readiness (existing, with the voice gate replacing the announcement-cache gate).

If the voice gate fails at step 2, bootstrap enters `NeedsSetup` and steps 3–5 are not reached. The probed instance from step 2 is available to the runtime navigation renderer once step 5 completes and the dashboard is shown.

Cold boot verification SHALL perform exactly one mandatory synthesis probe (D3) to prove the offline voice works. Zero eager navigation announcement syntheses occur during bootstrap — no vocabulary is pre-rendered, no channel names or selected phrases are synthesized until a runtime navigation request triggers on-demand synthesis. The probe is the only synthesis that occurs during bootstrap.

**Why over alternatives:**

- *Probe after Supertonic `Ready`*: wastes 60+ s of Supertonic model loading time before discovering that the navigation voice is missing. The voice gate is fast (typically 1–3 s) and has no dependency on the native engines. Running it first fails fast when the user lacks an offline voice.
- *Run the voice gate in parallel with STT/Supertonic*: the voice gate is a prerequisite, not a sibling. If it fails, there is no point initializing the native engines — the user must resolve the voice setup first. Running it before the `coroutineScope` avoids wasting CPU and battery on doomed engine initialization.
- *Construct lazily on first navigation request*: delays the first announcement by the full TTS init + voice-probe latency (typically 1–3 s), violating headless feedback responsiveness, and violates the hard-gate contract.
- *Construct a new instance per announcement*: each `TextToSpeech` construction allocates a service connection; repeated construction/destruction is wasteful and risks engine-internal state thrash. Retaining one probed instance is the standard Android pattern.
- *Share the Supertonic `TtsSynthesizer` port*: the existing port is blocking, pollable, takes `voiceStylePath`/`totalSteps`/`speed`, and returns 44.1 kHz f32 `FloatArray`. Android TTS is callback-based, not pollable, takes no voice-style path, and produces WAV files. Forcing Android TTS through the Supertonic port would require a leaky adapter that falsifies half the interface. A separate navigation-only renderer is cleaner.

### D2: Deterministic offline-English voice selection

After `onInit` reports `SUCCESS`, the renderer enumerates `TextToSpeech.getVoices()`, filters to voices whose locale language is `en` and whose `LANG_AVAILABLE` result is at least `LANG_AVAILABLE` (not `LANG_MISSING_DATA` or `LANG_NOT_SUPPORTED`), and whose `isNetworkConnectionRequired() == false` (or the equivalent embedded-offline signal, depending on platform API level). If multiple embedded English voices exist, the selection is deterministic by a stable composite sort key, in priority order:

1. **Lowest latency** — voices reporting lower synthesis latency (via `Voice.getFeatures()` containing a latency hint, or a platform quality/latency metadata field if available) sort first.
2. **Highest quality** — voices reporting higher quality (via features containing a quality hint) sort before lower-quality voices.
3. **Locale tag** — BCP 47 locale tag in lexicographic order.
4. **Voice name** — engine-reported voice name in lexicographic order.

The first voice after this sort is selected via `TextToSpeech.setVoice`. If `setVoice` returns `TextToSpeech.ERROR`, the renderer shuts down the `TextToSpeech` instance and maps the failure to `NeedsSetup` — no probe is attempted, since the voice could not be set. A missing engine, no English voice, all English voices network-only, `getVoices()` returning null, `setVoice` returning `ERROR`, or no voice with non-network availability all map to a failed voice gate → `NeedsSetup`.

**Public-API enforcement limits.** The voice selection enforces what the public API declares: it selects only voices that report `isNetworkConnectionRequired() == false` and avoids passing any network-synthesis parameters. However, the public API cannot inspect opaque engine internals — an engine may report a voice as embedded/offline but still attempt network access at synthesis time, or report `LANG_AVAILABLE` for partially installed voice data. The real synthesis probe (D3) mitigates this by proving actual synthesis works, but even a successful probe cannot guarantee that the engine never uses the network for future utterances. The hard guarantee is therefore: the renderer selects a voice declared as not network-required, passes no network parameters, and proves the probe works. Truly proving offline operation requires device acceptance with Wi-Fi and mobile data unavailable — the design does not claim stronger programmatic enforcement than the public API allows.

**Why over alternatives:**

- *Use the engine default voice*: the default may be a network-dependent voice or a non-English voice on a device with multiple engines. Navigation announcements must work offline with no SIM/Wi-Fi, so the default is insufficient.
- *Let the user pick a voice*: adds setup friction for a system that needs exactly one deterministic embedded English voice. The user-facing setup step is "install an offline English voice" (D12), not "pick among installed voices."
- *Prefer a specific engine package*: the system should not hard-depend on a specific Google/Samsung engine; any installed engine with an embedded English voice satisfies the gate. Engine-specific assumptions create device-fragmentation risk.
- *Sort by locale/voice name only without latency/quality preference*: would pick a high-latency or low-quality voice when a better embedded voice is available, degrading responsiveness and clarity for no reason.

### D3: Real synthesis probe during bootstrap

Voice selection alone does not prove synthesis works. After setting the selected voice, the renderer performs a real synthesis probe: it calls `synthesizeToFile` (see D4) with a short probe utterance (e.g. `"Subspace"`) to a transient file. If `synthesizeToFile` returns `TextToSpeech.ERROR`, the renderer does NOT wait for callbacks — it immediately unregisters the probe operation, deletes the transient file, and classifies the failure as a probe failure → `NeedsSetup` (same as `onError`). If it returns `SUCCESS`, the renderer waits for the `UtteranceProgressListener.onDone` callback (or `onError`) within a bounded timeout. `SUCCESS` means only that the engine accepted the request; final success still requires a matching `onDone` callback with a valid non-empty WAV file. The probe's `utteranceId` is tagged with the bootstrap attempt token (D4 callback ownership); its callback is owned by the bootstrap attempt, independent of any navigation generation. The probe file is deleted immediately after. A timeout, `onError`, `ERROR` return, or empty file maps to `NeedsSetup`.

**Why over alternatives:**

- *Skip the probe, trust `setVoice` return code*: `setVoice` returning `SUCCESS` does not guarantee that the engine can actually synthesize with that voice offline. Some engines report availability but fail at synthesis time if the voice data is partially downloaded. A real probe is the only reliable proof.
- *Probe via `speak()` (audible)*: would produce an audible utterance during bootstrap, which is unacceptable. `synthesizeToFile` is silent.
- *Probe at first navigation request instead of bootstrap*: delays failure discovery to runtime and violates the hard-gate contract. A user who has a broken voice would discover it only when pressing Volume Down, with no setup guidance.

### D4: `synthesizeToFile` with a unique transient regular file and callback PCM — no direct `speak()`

Each synthesis call — whether navigation, bootstrap probe, or recovery probe — calls `TextToSpeech.synthesizeToFile(text, Bundle.EMPTY, file, utteranceId)` where `file` is a unique transient regular file in `Context.cacheDir` (not `noBackupFilesDir` — no persistence is desired). A non-null `Bundle` (or `Bundle.EMPTY`) is passed because some engine implementations reject a null params argument. The renderer registers a single `UtteranceProgressListener` that routes callbacks by ownership dimension, not by navigation generation alone:

**Callback ownership model.** Two independent ownership dimensions govern callback routing:

1. **Engine-instance epoch + attempt/utterance registry**: each `synthesizeToFile` call carries a unique `utteranceId`. The renderer strongly owns the live `TextToSpeech` instance and maintains a monotonically increasing engine-instance epoch — incremented each time a new `TextToSpeech` instance is constructed (bootstrap, recovery). A callback is valid only if its `utteranceId` matches a registered pending call in the attempt/utterance registry AND the issuing instance's epoch matches the current live instance epoch. Callbacks from a shut-down (old-epoch) instance or an unregistered `utteranceId` are rejected (ignored). The renderer does not use a weak reference to the `TextToSpeech` instance; it strongly owns the live instance and invalidates old epochs on `shutdown()`.
2. **Navigation generation**: when a callback is valid by dimension 1, its result is delivered to the owning operation. For navigation synthesis, the result is delivered only if the owning navigation generation is still the current `navigationGeneration`. For bootstrap/recovery probes, the result is delivered to the owning bootstrap/recovery attempt regardless of `navigationGeneration` — the probe's success or failure is independent of which navigation phrase is pending.

On success, the renderer reads the WAV file, normalizes it (see D5), deletes the transient file, and returns a `RecordedPcm`.

**Immediate `ERROR` return handling.** If `synthesizeToFile` returns `TextToSpeech.ERROR`, the renderer does NOT wait for callbacks. It immediately unregisters the operation from the attempt/utterance registry, deletes the transient file, and classifies the failure exactly like an `onError` callback: for a bootstrap/recovery probe, the failure maps to `NeedsSetup`; for a runtime navigation synthesis, the failure triggers D11 bounded recovery. `SUCCESS` means only that the engine accepted the request — final success still requires a matching `onDone` callback with a valid non-empty WAV file.

**Bounded synthesis callback timeout.** Both bootstrap/recovery probes and runtime navigation synthesis have their own bounded timeouts. If no callback (`onDone` or `onError`) arrives within the timeout, the renderer calls `TextToSpeech.stop()`, unregisters the operation from the attempt/utterance registry, deletes the transient file, and classifies the timeout exactly like an `onError` callback: for a bootstrap/recovery probe, the timeout maps to `NeedsSetup`; for a runtime navigation synthesis, the timeout triggers D11 bounded recovery. The probe timeout and the runtime synthesis timeout MAY differ (the probe utterance is shorter).

The renderer never calls `TextToSpeech.speak()`. Android TTS never owns audible playback. All audio is routed through the existing app-owned `AudioTrack` path.

`synthesizeToFile` does not accept a `QUEUE_FLUSH` parameter. To prevent a stale synthesis from a prior navigation generation from delivering its callback into the current generation, the renderer calls `TextToSpeech.stop()` before issuing each new navigation `synthesizeToFile` and rejects navigation callbacks whose generation no longer matches `navigationGeneration` (dimension 2). However, `TextToSpeech.stop()` and navigation-generation advances do NOT invalidate bootstrap or recovery probe callbacks — those are owned by their attempt token (dimension 1), not by `navigationGeneration`.

Service teardown/discard (`discardControllers` during bootstrap retry, or service destruction) invalidates the current attempt token, causing all pending callbacks for that token to be rejected.

**Why over alternatives:**

- *Use `speak()` and capture via `AudioRecord` loopback*: requires `MediaProjection` or `AudioPlaybackCaptureConfiguration`, which is fragile, permission-gated, and introduces a second audio path. The app already has a proven `AudioTrack` playback path; feeding it `RecordedPcm` is the established pattern.
- *Use `speak()` to an unused `AudioTrack` and capture from `Visualizer`*: `Visualizer` captures only the mix, not a specific stream, and requires `RECORD_AUDIO` adjacent permissions. Overly complex.
- *Use `TextToSpeech.getAudio()` (deprecated/removed)*: unavailable on modern API levels.
- *Write to a `FileDescriptor` / pipe instead of a file*: `synthesizeToFile` on most engines writes a full WAV and does not support streaming to a pipe reliably (some engines seek). A regular file is the universally supported path. The file is transient and deleted immediately after reading.
- *Rely on `QUEUE_FLUSH` with `synthesizeToFile`*: `synthesizeToFile`'s second parameter is a `Bundle` of params, not a queue mode. There is no flush parameter. `TextToSpeech.stop()` plus operation-token + generation rejection is the correct mechanism.
- *Route all callbacks by navigation generation alone*: a recovery probe issued during `Recovering` state would be invalidated when a newer navigation request advances `navigationGeneration`, even though the probe has nothing to do with navigation delivery. The two-dimension model keeps probe callbacks alive across navigation generation advances.

### D5: Native-format normalization to 16 kHz mono PCM16

Android TTS engines produce WAV files at their native sample rate (commonly 22050 Hz, 16000 Hz, or 24000 Hz depending on engine/voice) with one of several officially reported PCM encodings and either mono or stereo channels. The renderer reads the WAV file, inspects its declared format (sample rate, channel count, bit depth/encoding), and normalizes to the target:

- **Encoding**: accept `WAVE_FORMAT_PCM` (1), `WAVE_FORMAT_IEEE_FLOAT` (3), and 8-bit unsigned PCM as declared in the WAV header. The existing `WavPcmReader` SHALL be extended or replaced to expose the format tag, sample width, and channel count, and to decode PCM8, PCM16, and IEEE-float directly to a mono normalized `FloatArray` (downmixing stereo to mono while decoding by averaging left/right). If more than 2 channels, reject as unsupported. Reject any other encoding (e.g. μ-law, A-law, ADPCM) — the renderer SHALL NOT attempt to decode unsupported formats.
- **Sample rate**: resample the decoded mono `FloatArray` from the WAV's native rate to 16 000 Hz using the existing `TtsAudio.resample(FloatArray, inputRate, outputRate)` linear-interpolation path (already used for Supertonic's 44.1 kHz → 16 kHz conversion).
- **PCM16 conversion**: convert the resampled `FloatArray` to PCM16 via the existing `TtsAudio.f32ToPcm16(FloatArray)`.
- **Output**: `RecordedPcm(samples = ShortArray, sampleRate = 16_000)` — identical to the existing `TtsAudio.toScoPlayback` output shape.

**Implementation plan:** extend/replace `WavPcmReader` to expose the format tag and sample width and to decode directly to `FloatArray` with mono downmix, then reuse `TtsAudio.resample` + `TtsAudio.f32ToPcm16`. This reuses the existing f32 resample + PCM16 conversion pipeline rather than introducing a new PCM16-to-PCM16 resampler.

A WAV whose format cannot be read, whose encoding is unsupported, or whose channel count exceeds 2 is classified as a format-infrastructure failure (D12).

**Why over alternatives:**

- *Assume PCM16 only*: some engines produce 8-bit or float WAV files. Silently misinterpreting the encoding would produce garbage audio. Reading and converting the declared encoding is correct.
- *Let the TTS engine produce 16 kHz directly via `setAudioRate`*: there is no reliable public API to force a specific output sample rate from Android TTS. The engine chooses its own rate.
- *Play the WAV at its native rate via a second `AudioTrack`*: the existing `StreamPlaybackRoute` and `ActiveStreamPcmPlayback` are parameterized for 16 kHz mono (the SCO native rate). Supporting multiple rates would complicate the bounded-buffer pump and the route strategy. Normalizing once to 16 kHz keeps the playback path untouched.
- *Resample PCM16 directly without a FloatArray intermediate*: `TtsAudio.resample` operates on `FloatArray`, not `ShortArray`. Introducing a new `ShortArray` resampler would duplicate the resampling logic. Decoding to `FloatArray` and reusing the existing `resample` + `f32ToPcm16` pipeline is simpler and proven.

### D6: Current-catalogue phrase resolution at request time

Navigation announcements are triggered by RSM control-mode button events (`PttForegroundService`). Instead of looking up a precomputed cache key, the service resolves the phrase text from the current channel catalogue at request time:

- **Menu entry** (`sys.menu.channels`): fixed text `"Channels"`.
- **Channel name** (`chan.<id>.name`): `channelRepository.catalogueState.value.definitions.find { it.id == activeChannelId }?.name`.
- **Channel selected** (`chan.<id>.selected`): `"<name> Selected"` from the same catalogue snapshot.

The renderer receives the resolved text string, not a vocabulary key. This eliminates the need for a vocabulary map, precomputation, and the `AnnouncementResult` state machine.

**Why over alternatives:**

- *Pre-resolve all phrases at bootstrap and pass a text map*: reintroduces a precomputed map (just text instead of PCM). Channel names can change between bootstrap and navigation (user renames a channel), so the map would be stale. Resolving at request time from the live `catalogueState` guarantees the announcement matches the current name.
- *Keep vocabulary keys and resolve text in the renderer*: adds an unnecessary indirection layer. The service already has `channelRepository` in scope; resolving text there and passing a string to the renderer is simpler.

### D7: One navigation job with generation IDs and `cancelAndJoin`

The navigation renderer owns a single `Job` reference (`activeJob`) and a `Recovering` state flag. Normal in-flight synthesis/playback requests use `cancelAndJoin` latest-wins:

Any `UtteranceProgressListener` callback is routed by the two-dimension ownership model (D4): operation/attempt token + utterance ID (dimension 1) validates the callback against the live instance and owning attempt; navigation generation (dimension 2) gates only navigation PCM delivery. A stale navigation callback whose generation no longer matches `navigationGeneration` is rejected, but this does NOT invalidate bootstrap or recovery probe callbacks owned by their attempt token.

1. Publishes the new navigation generation ID (advances `navigationGeneration`).
2. Calls `TextToSpeech.stop()` to flush any in-flight engine synthesis from the prior generation.
3. Cancels the prior `activeJob` via `cancelAndJoin`. The `cancelAndJoin` waits for the prior job to fully terminate — including any in-flight `HostAudioCoordinator.play` cleanup (skip → await post-cleanup completion → route release → owner clear per D8) and transient file deletion — before the new generation proceeds.
4. Launches a new coroutine that performs: text resolution → `synthesizeToFile` with the new generation's `utteranceId` → normalization → `HostAudioCoordinator.play`.

**Recovery-aware exception:** when the renderer is in `Recovering` state (D11 recovery is active), new navigation requests do NOT cancel or restart the recovery. Instead, they update only the authoritative generation ID and latest-pending text — the single active recovery continues uninterrupted. When recovery completes successfully, it synthesizes the current newest generation (which may have superseded the generation that triggered the failure). For example: Alpha triggers failure → recovery begins → Bravo arrives (updates authoritative newest) → Journal arrives (updates authoritative newest) → recovery completes → synthesizes Journal only. No second recovery job is spawned; the single active operation suffices.

This guarantees:

- At most one active navigation generation at any time during normal operation.
- During recovery, at most one recovery in flight; newer requests update authoritative newest without spawning additional recoveries.
- A stale `synthesizeToFile` callback cannot feed PCM into the current generation's playback path, because the prior job is already cancelled and joined (normal path) or the callback's generation ID is rejected (recovery path), and the engine was stopped.
- The prior `HostAudioCoordinator.play` has released its route and cleared its owner before the new generation acquires its own route.

**Why over alternatives:**

- *Fire-and-forget with no cancellation*: multiple rapid Volume Down presses would queue synthesis requests and produce overlapping or stale announcements. The current `SystemAnnouncer.playSerialized` already implements latest-wins via `cancelAndJoin`; this design preserves that contract.
- *Cancel without join (fire `cancel()` only)*: the prior job's `HostAudioCoordinator.play` might still hold the route when the new job tries to acquire it, causing `HostPlaybackResult.Busy` and a silent announcement. `cancelAndJoin` ensures the route is free.
- *Cancel/restart recovery on newer request*: would abort an in-flight TTS reinitialization that may be seconds from completing, then start a new one — potentially never completing if requests arrive faster than recovery. Letting the single recovery continue and synthesizing the current newest on success is simpler and avoids a second recovery job.
- *Queue announcements (FIFO)*: for navigation feedback, the newest selection is what the user wants to hear. Queuing stale announcements is user-hostile.

### D8: Required source fix — `HostAudioCoordinator.play` cancellation-safe teardown ordering

The current `HostAudioCoordinator.play` has cancellation-escape bugs across every post-reservation suspension window. The coordinator reserves `owner` (line 80) **before** calling `strategy().acquire()` (line 82). Cancellation can arrive during route acquisition, during `route.start()`, or during `awaitCompletion()`, and the current code does not guarantee owner/route cleanup in all cases:

- **Cancellation before route acquired** (during `strategy().acquire()`): `CancellationException` propagates out of `acquire()` before a route is returned. `releasePlaybackReservation` is never reached — `owner` stays stale.
- **Cancellation during `route.start()`**: the route was acquired but no `ActivePcmPlayback` was created. `CancellationException` propagates. The acquired route is not released and `owner` is not cleared.
- **Cancellation during `awaitCompletion()`**: the `finally` block runs `route.release()` but `CancellationException` propagates before `releasePlaybackReservation` (line 126, outside the `try-finally`) — `owner` stays stale and `activePlayback` dangles.

**Required fix:** wrap the entire post-reservation body of `play()` — from `strategy().acquire()` through `awaitCompletion()`, `route.release()`, and `releasePlaybackReservation` — in a single `try-finally` where all terminal cleanup runs under `NonCancellable` in the `finally` block. The cleanup depends on which phase was reached when cancellation arrived:

1. **Owner reserved, route not yet acquired** (cancellation during `strategy().acquire()`): no route to release. `releasePlaybackReservation(operation, Interrupted)` clears `owner` only if `owner == operation`, clears `activePlayback` and `rejectedPressPendingRelease`.
2. **Route acquired, no playback created** (cancellation during `route.start()`): release the acquired route via `route.release()`, then `releasePlaybackReservation(operation, Interrupted)`.
3. **Playback active** (cancellation during `awaitCompletion()`): `playback.skip()` signals the independent pump to stop writing. `awaitCompletion()` suspends until the pump's `finally` block has called `cleanup()` (physical `AudioTrack` stop + release — see D9) and returns `PlaybackCompletion.ExplicitlySkipped` (since `skip()`-driven termination is `ExplicitlySkipped`, not `Interrupted`). The coordinator maps this to `HostPlaybackResult.ExplicitlySkipped`. `route.release()` releases the SCO route (the `AudioTrack` is already physically destroyed, so no dangling writer). `releasePlaybackReservation(operation, result)` clears the matching owner with the actual result. The caller's own `CancellationException` rethrows only after the full skip → `awaitCompletion()` → `route.release()` → `releasePlaybackReservation` sequence completes under `NonCancellable` — it never publishes `Interrupted` for a `skip()`-driven path. `PlaybackCompletion.Interrupted` and `HostPlaybackResult.Interrupted` are reserved for actual pump-coroutine cancellation (the `catch(CancellationException)` path inside the pump itself), not for caller-driven `skip()`.

In all three cases, `releasePlaybackReservation` runs under `NonCancellable` in the `finally` block and clears `owner` only if `owner == operation` — not a blanket clear. No new playback-kind abstraction is introduced; the existing `ActivePcmPlayback` and `AcquiredPlaybackRoute` interfaces are unchanged.

The invariant for every path: acquired resources are released before the coordinator owner is cleared; the owner is always cleared on cancellation; no step is skipped. The existing `skipOnStart` check (lines 103–114) and `close()` (lines 139–149) already handle the race where a `close()` or replacement arrives between reservation and `activePlayback` assignment — those paths call `playback.skip()` and `route.release()` through the same `finally` envelope, so they are covered by this fix without requiring separate handling.

**Why over alternatives:**

- *Release the route before stopping the track*: would leave the `AudioTrack` writing to a released SCO connection, producing framework errors or garbage audio.
- *Clear the coordinator owner before playback completes*: would allow a new playback to be admitted while the old `AudioTrack` is still draining, violating half-duplex exclusivity.
- *Skip `releasePlaybackReservation` on cancellation (current behavior)*: leaves a stale owner, causing all subsequent navigation announcements to receive `Busy` and fail silently until the process restarts.

### D9: Required source fix — `AudioTrack` completion after physical cleanup

The current `ActiveStreamPcmPlayback` has a completion-ordering bug: `complete(PlaybackCompletion.Interrupted)` is called in the `catch (CancellationException)` block at line 161 **before** the `finally { cleanup() }` block at line 166 runs. Because `awaitCompletion()` resolves on `completion.complete(...)`, `HostAudioCoordinator.play` observes `Interrupted` while the `AudioTrack` is still alive — `track.stop()` and `track.release()` have not yet executed. The `pumpJob` runs on an independent dispatcher, but this is acceptable because cancellation is driven through the existing `ActivePcmPlayback` abstraction, not through coroutine cancellation of the pump itself.

**Required fix:**

Defer terminal completion until after physical cleanup. Move `complete(...)` out of the `catch`/`return` paths and into the `finally` block, after `cleanup()` has run. The `cleanup()` (physical `track.stop()` + `track.release()` under `NonCancellable`) MUST complete before `completion.complete(...)` is called. This ensures `awaitCompletion()` never resolves while the `AudioTrack` is still alive. The exact order for all terminal paths:

- **Completed**: `drainTail()` → `finally { cleanup(); complete(Completed) }` → `awaitCompletion()` resolves.
- **Interrupted** (cancellation): `catch(CancellationException) { /* no complete here */ }` → `finally { cleanup(); complete(Interrupted) }` → `awaitCompletion()` resolves.
- **ExplicitlySkipped**: `return` from `pump()` → `finally { cleanup(); complete(ExplicitlySkipped) }` → `awaitCompletion()` resolves.
- **Failed**: `catch(Throwable) { /* no complete here */ }` → `finally { cleanup(); complete(Failed(reason)) }` → `awaitCompletion()` resolves.

The independent pump scope is not a problem because `HostAudioCoordinator` drives cancellation through the `ActivePcmPlayback` abstraction, not through coroutine cancellation of the pump. When the navigation generation is cancelled (D7's `cancelAndJoin`), `HostAudioCoordinator.play` calls `playback.skip()` (D8 step 1), which sets the `skipRequested` volatile flag. The independent pump observes `skipRequested` at its loop checkpoints (lines 221, 249, 264), stops writing, exits the pump, and enters its `finally` block where `cleanup()` physically stops and releases the `AudioTrack`, then `complete(ExplicitlySkipped)` publishes the terminal result. `Interrupted` is reserved for actual pump-coroutine cancellation (the `catch(CancellationException)` path), not for `skip()`-driven termination. `HostAudioCoordinator.play`'s `awaitCompletion()` (D8 step 2) then suspends until that sequence finishes. The caller's own `CancellationException` may still rethrow after `awaitCompletion()` returns and cleanup completes — the completion classification is internal to the playback; the caller handles its cancellation separately. No structured-concurrency coupling between the caller's coroutine and the pump is required.

With this fix, `HostAudioCoordinator.play`'s `awaitCompletion()` call (D8 step 2) suspends until the `AudioTrack` is physically stopped and released. The `route.release()` that follows is therefore safe — the track is already gone, and the SCO route can be released without a dangling writer.

**Why over alternatives:**

- *Resolve `awaitCompletion()` before cleanup (current behavior)*: lets `HostAudioCoordinator` release the route while the `AudioTrack` is still active, risking a write-after-release error on the SCO connection.
- *Run cleanup outside `NonCancellable`*: if cancellation arrives during `track.release()`, the track would leak. The existing `NonCancellable` block is correct and preserved.

### D10: Exact route ownership — transient PCM through the existing path

The normalized `RecordedPcm` is passed to `HostAudioCoordinator.play(recording) { playbackRouteResolver.strategyFor(InputMode.Work) }` — the exact same call path as the current `enqueueRsmAnnouncement`. No new route, no new `AudioTrack`, no new device-selection logic. The `ModePlaybackRouteResolver.acquireWork` path:

1. Calls `workSco.acquire()` (SCO route acquisition with target-RSM HFP ownership proof).
2. Gets `workSco.selectedCommunicationDevice()` (must be `TYPE_BLUETOOTH_SCO`).
3. Constructs `StreamPlaybackRoute(preferredDevice = device)` with `setPreferredDevice` to the exact target-RSM device.

Navigation playback uses this path identically. The only difference is the `RecordedPcm` source (on-demand synthesis vs cache lookup).

**Why over alternatives:**

- *Give navigation its own route resolver*: would duplicate the exact-RSM-ownership proof logic and risk divergence. The existing resolver is the single source of truth for Work-mode playback routing.
- *Route Android TTS output directly to the SCO device*: would require `TextToSpeech.speak()` with an `AudioAttributes` routing hint, but Android TTS engine routing does not support `setPreferredDevice` and does not guarantee the SCO device. App-owned `AudioTrack` with `setPreferredDevice` is the only reliable exact-device path.

### D11: One bounded recovery — reinitialization + voice probe + retry newest generation only

If a runtime `synthesizeToFile` call fails due to an engine or voice failure (callback `onError`, timeout, or the TTS engine reports an unrecoverable state), the renderer enters a `Recovering` state and attempts one bounded recovery. The `Recovering` state is a simple flag that makes new navigation requests update only the authoritative generation ID and latest-pending text (D7 recovery-aware exception) without cancelling or restarting the recovery:

1. Set `Recovering = true`. Pending navigation requests arriving during recovery coalesce: they update only the authoritative `navigationGeneration` and latest-pending text, and do NOT cancel or restart the recovery.
2. Shut down the current `TextToSpeech` instance (`shutdown()`). This invalidates the old instance epoch (D4 dimension 1) — all pending callbacks from the old instance are rejected.
3. Construct a new `TextToSpeech` instance and wait for `onInit`. The new instance gets a fresh epoch (incremented).
4. Re-run voice selection (D2) and the synthesis probe (D3). If the probe `synthesizeToFile` returns `ERROR` or the probe callback signals failure/timeout, classify as probe failure → `NeedsSetup` (step 6). The probe's `utteranceId` is tagged with the recovery attempt token (D4 dimension 1), not with any navigation generation. Newer navigation requests arriving during recovery advance `navigationGeneration` (dimension 2) but do NOT invalidate the probe callback — the probe is owned by the recovery attempt, independent of `navigationGeneration`.
5. **Atomic handoff on probe success**: under a single orchestration mutex, the renderer atomically: (a) publishes the recovered `TextToSpeech` instance as the live instance, (b) clears `Recovering`, (c) snapshots the current newest pending `navigationGeneration` and text, and (d) hands that snapshot to one normal latest-wins synthesis job tagged as the recovery retry. Because the handoff is atomic under the mutex, a navigation request racing the handoff is either included in the snapshot (its text is what the retry synthesizes) or arrives after the handoff (it sees `Recovering == false`, advances `navigationGeneration`, calls `TextToSpeech.stop()`, `cancelAndJoin`s the retry job, and starts normally). No request is lost. If the retry synthesis fails and is not superseded (no newer request arrived), the failure is the same failure chain — no second recovery is attempted; the application enters retryable `BootstrapState.Failed` and leaves `Ready`. If the retry is superseded by a newer request, the newer request proceeds normally and the retry's failure is discarded.
6. If the probe fails (missing or unusable voice), recovery cannot restore the hard gate. The renderer transitions the bootstrap state from `Ready` to `NeedsSetup` with the offline-navigation-voice step exposed. The generation is dropped.
7. `Recovering` is cleared (in step 5 on success, or here on failure).

Only one recovery is active at a time. The orchestration mutex ensures the probe-success handoff is atomic with respect to racing navigation requests, preventing a lost-request race where a request arrives between clearing `Recovering` and starting the retry synthesis.

If recovery restores the engine/voice gate (step 5 retry synthesis succeeds and is not superseded), bootstrap remains `Ready`. If recovery fails because the re-probe cannot restore the hard gate (step 6), bootstrap leaves `Ready` and enters `NeedsSetup` — the app does not remain `Ready` with an unproven voice. If recovery's re-probe succeeds but the retried synthesis fails without supersession (step 5 exhausts), this is the same failure chain — no second recovery is attempted. The application enters retryable `BootstrapState.Failed` and leaves `Ready`.

After recovery completes (success or failure), normal `cancelAndJoin` latest-wins operation resumes. Only a later, independent announcement failure after a fully successful recovery+retry (probe succeeded AND retried synthesis succeeded AND a subsequent, separate synthesis later fails) is a new failure eligible for one recovery. The bound is one recovery per independent failure chain, not one per process lifetime.

Renderer/transient-file/format-infrastructure failures (file I/O error, WAV decode error, unsupported encoding, empty PCM after normalization) are NOT engine/voice failures and do NOT trigger D11 recovery or enter `Recovering`. They are classified as retryable `BootstrapState.Failed` (D12): the failed announcement generation is dropped, the application leaves `Ready` and enters retryable `BootstrapState.Failed`, and the user may retry bootstrap. The probed instance is still valid; the failure is transient and does not indicate a broken engine or voice.

**Why over alternatives:**

- *Unlimited recovery*: repeated `TextToSpeech` construction/destruction can destabilize the engine service and drain battery. One attempt catches transient engine-internal failures without risking a retry storm.
- *No recovery (fail immediately)*: a single transient `onError` (e.g. engine briefly unavailable during a system audio policy change) would permanently break navigation for the session. One retry handles the common case.
- *Cancel/restart recovery on newer request*: would abort an in-flight TTS reinitialization that may be seconds from completing, then start a new one — potentially never completing if requests arrive faster than recovery. Letting the single recovery continue and synthesizing the current newest on success is simpler.
- *Stay `Ready` when recovery fails*: the hard gate requires a proven offline voice. If the voice cannot be re-proven, the app is not in a ready state — `NeedsSetup` is the honest state, not `Ready`.
- *Re-enter full bootstrap on failure*: bootstrap is a launch-time gate. Re-entering it at runtime would tear down all controllers and show a loading screen, which is disproportionate. The targeted recovery (reconstruct + re-probe the TTS instance only) preserves all other controllers and dashboard state.

### D12: Failure classification

| Failure | Detected at | Result |
|---|---|---|
| Engine not installed / `onInit` error | Bootstrap (D1) | `NeedsSetup` with Android TTS settings action |
| No English voice / all network-only | Bootstrap (D2) | `NeedsSetup` with voice-install action |
| Voice selected but probe fails / times out / `ERROR` return | Bootstrap (D3) | `NeedsSetup` with "voice may be partially downloaded" |
| Runtime `synthesizeToFile` `onError` or `ERROR` return (engine/voice) | Runtime | One bounded recovery (D11); if re-probe fails: `NeedsSetup`; if re-probe succeeds but retry fails: retryable `BootstrapState.Failed` |
| TTS engine service disconnected at runtime | Runtime | One bounded recovery (D11); if re-probe fails: `NeedsSetup`; if re-probe succeeds but retry fails: retryable `BootstrapState.Failed` |
| Transient file I/O failure (renderer infrastructure) | Runtime | Retryable `BootstrapState.Failed`; generation dropped; application leaves `Ready`; user may retry bootstrap |
| WAV read / decode failure (renderer infrastructure) | Runtime | Retryable `BootstrapState.Failed`; generation dropped; application leaves `Ready`; user may retry bootstrap |
| Unsupported WAV encoding (format infrastructure) | Runtime | Retryable `BootstrapState.Failed`; generation dropped; application leaves `Ready`; user may retry bootstrap |
| Empty PCM after normalization (format infrastructure) | Runtime | Retryable `BootstrapState.Failed`; generation dropped; application leaves `Ready`; user may retry bootstrap |

The distinction: engine/voice failures (rows 4–6) attack the hard gate and trigger D11 recovery — if recovery's re-probe fails (missing/unusable voice), the application enters `NeedsSetup`; if recovery's re-probe succeeds but the retried synthesis fails, the application enters retryable `BootstrapState.Failed`. Renderer/transient-file/format-infrastructure failures (rows 7–10) do not indicate a broken engine or voice — the probed instance is still valid — and are classified as retryable `BootstrapState.Failed` with no recovery and no `NeedsSetup` transition. In all failed paths the generation is dropped; no degraded mode exists. The application leaves `Ready` on every failure classification; the user resolves via setup (`NeedsSetup`) or retry (`BootstrapState.Failed`).

**Why a table instead of prose:** the classification is load-bearing for the spec scenarios. Each row maps a detection point to a concrete consequence, leaving no ambiguity for implementation.

### D12b: Setup intent uses only public Android APIs with re-probe on return

When the offline navigation voice gate fails (`NeedsSetup`), the setup surface exposes an action that resolves and launches only documented public intents:

1. **Primary**: `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` — opens the system's TTS data installation flow, which lets the user download/install offline voice data for the active engine. The intent MUST be scoped to the default/active engine package via `intent.setPackage(enginePackage)` before resolving and launching, so it targets the correct engine's data installation flow rather than an arbitrary handler. The renderer resolves the scoped intent via `PackageManager.queryIntentActivities` before launching; if a handler exists, it launches via `startActivity`.
2. **Fallback**: if no activity handles `ACTION_INSTALL_TTS_DATA` (e.g. no engine installed, or the engine does not expose the action), the renderer falls back to `Settings.ACTION_SETTINGS` — the general Android settings entry point — with explicit user-facing instruction to install/enable an offline English voice through the system TTS or language settings.
3. **Re-probe on return**: when the user returns to Subspace from either intent, the bootstrap prerequisite recheck (existing `refreshPrerequisites` mechanism) re-runs the Android TTS voice gate (D1, D2, D3). If the voice is now available and the probe succeeds, bootstrap proceeds to core preparation.

The renderer SHALL NOT use `com.android.settings.TTS_SETTINGS` (undocumented component intent, not part of the SDK), `Settings.ACTION_VOICE_INPUT_SETTINGS` (governs speech recognizer/Voice Input, not TTS voice data), or any other hidden/non-SDK intent. This is a hard no-hidden-API constraint: undocumented intents break across OEM ROMs, Android versions, and may be blocked by non-SDK API restrictions.

**Why over alternatives:**

- *Use `com.android.settings.TTS_SETTINGS`*: undocumented, not part of the SDK, may be blocked by non-SDK API restrictions, and breaks across OEM ROMs. Rejected.
- *Use `Settings.ACTION_VOICE_INPUT_FIELDS_SETTINGS` or `ACTION_VOICE_INPUT_SETTINGS`*: these govern the speech recognizer (Voice Input / Assistant), not TTS voice data. Wrong component. Rejected.
- *Skip the fallback when `ACTION_INSTALL_TTS_DATA` has no handler*: a device with no engine or an engine that does not expose `ACTION_INSTALL_TTS_DATA` would have no actionable setup step. The `Settings.ACTION_SETTINGS` fallback ensures the user always has a path to resolve the prerequisite.

### D13: Removal of cache and eager rendering state

The following are removed entirely:

- `AnnouncementPcmCache` (persistent cache: manifest, fingerprints, WAV entries, reconciliation, atomic commit, `AnnouncementCacheIdentity`, `AnnouncementRenderSettings`, `AnnouncementCacheCommitResult`).
- `SystemAnnouncer` (in-memory cache, `precompute`, `precomputeState`, `AnnouncementResult`, `playSerialized`, `recordingFor`, ready-beep fallback).
- `AnnouncementResult` sealed interface and all its states (`Ready`, `Rendering`, `WaitingForTts`, `Failed`).
- `BootstrapStage.RenderingAnnouncements` and the corresponding bootstrap stage logic.
- The `precompute`/vocabulary/voice-style-path bootstrap gate in `BootstrapCoordinator.runAttempt`.
- `CoreInit.constructAnnouncer`, `CoreInit.buildVocabulary`, `CoreInit.voiceStylePath`.
- Cache diagnostics (`ANNOUNCEMENT_CACHE_SUMMARY` log line).
- The `PttForegroundService.constructAnnouncer` implementation (cache identity, persistent cache construction, `SystemAnnouncer` instantiation).
- The ready-beep cache fallback in `enqueueRsmAnnouncement` — `HostAudioFeedback.readyBeep()` is no longer used as a cache-miss fallback for navigation announcements.
- `HostAudioFeedback.readyBeep()` entirely — no remaining caller after navigation cache removal. The error beep (`HostAudioFeedback.errorBeep()`) remains for RSM boundary-violation feedback (top/bottom of catalogue).

**What is added:**

- A navigation TTS renderer that owns the probed `TextToSpeech` instance, performs `synthesizeToFile` + normalization, and exposes a single `suspend fun synthesize(text: String): RecordedPcm?` (or equivalent) API. The renderer introduces no reusable persistence or cache — neither an in-memory phrase map nor a persistent WAV store. The transient file produced by `synthesizeToFile` exists only for the duration of a single synthesis operation and is deleted immediately after the PCM is read and normalized; no file survives beyond its operation.
- A bootstrap prerequisite gate (before STT/Supertonic init) that constructs, probes, and retains the renderer.
- A `BootstrapStage` (e.g. `ProbingNavigationVoice`) replacing `RenderingAnnouncements`.
- A `NeedsSetup` field for the missing offline navigation voice (with an Android TTS settings intent action).

**Why not keep `AnnouncementResult` for the probe:** the probe is a single binary pass/fail, not a multi-phrase progress operation. A `Ready`/`Failed` boolean or a sealed result with two states suffices. `AnnouncementResult.Rendering` (multi-phrase progress) is meaningless for on-demand single-phrase synthesis.

## Risks / Trade-offs

- **Latency shift**: cached PCM was zero-latency at playback time. On-demand synthesis adds TTS rendering latency (typically 200–800 ms for short phrases) before each announcement. This is acceptable for navigation feedback (the user has already pressed a button and is waiting for confirmation) and is strictly better than the 14–28 s bootstrap cost it replaces. The latest-wins contract ensures rapid presses do not queue stale announcements.

- **Engine variability and offline-enforcement limits**: Android TTS engines vary by manufacturer (Google, Samsung, Huawei, etc.). Voice availability, sample rate, and synthesis quality differ. The deterministic voice selection (D2) selects only voices declared as not network-required and the real probe (D3) proves synthesis works, but the public API cannot inspect opaque engine internals — an engine may declare a voice as embedded but still attempt network access. Truly proving offline operation requires device acceptance with Wi-Fi/mobile data unavailable; the design does not claim stronger programmatic enforcement than the public API allows. Devices with no installed offline English voice will enter `NeedsSetup`, which is the correct user-facing outcome.

- **Transient file I/O**: `synthesizeToFile` writes to `cacheDir`, which is ephemeral and does not survive app uninstalls but may be on constrained storage. A failed write is classified as retryable `BootstrapState.Failed` (D12) — the generation is dropped, the application leaves `Ready`, and the user may retry bootstrap. The transient file exists only for the duration of its synthesis operation and is deleted immediately after reading; no accumulation occurs.

- **`UtteranceProgressListener` reliability**: some OEM TTS engines have unreliable callback delivery (missing `onDone`, late `onError`). The synthesis timeout bound handles missing callbacks. If an engine routinely fails callbacks, the probe will catch it at bootstrap and route the user to `NeedsSetup`.

- **Recovery window**: during the one bounded recovery attempt (D11), navigation feedback is temporarily unavailable (the prior generation failed, the recovery has not yet completed). This window is typically 1–3 s. The failed generation is dropped; subsequent requests during recovery are suppressed until the recovery completes. If recovery's re-probe fails, the state transitions to `NeedsSetup`. If recovery's re-probe succeeds but the retried synthesis fails, the application enters retryable `BootstrapState.Failed`. In both cases the application leaves `Ready`.

- **SCO route contention**: the existing half-duplex coordinator already serializes playback and capture. Navigation playback goes through the same coordinator, so it cannot preempt capture or channel-content playback. A navigation announcement triggered during active channel-content playback will receive `HostPlaybackResult.Busy` and be silently dropped — this is the correct half-duplex behavior.

- **D8/D9 source fixes are breaking**: the `HostAudioCoordinator.play` and `ActiveStreamPcmPlayback` cancellation/completion fixes change the behavior of all playback paths, not just navigation. The existing SOS-skip and close() paths also benefit from the corrected ordering. These fixes must be validated against the existing channel-content playback tests to ensure no regression in the non-cancellation completion path.

## Migration Plan

1. **Add the navigation TTS renderer** with `TextToSpeech` lifecycle, `synthesizeToFile` + callback bridging with two-dimension ownership model (D4: operation/attempt token + utterance ID for callback routing; navigation generation for PCM delivery gating), `TextToSpeech.stop()` generation flush, WAV reading, and PCM normalization (D5). No callers yet.
2. **Fix `HostAudioCoordinator.play` cancellation teardown** (D8): wrap the entire post-reservation body in a `NonCancellable` `finally` covering all three cancellation windows (pre-route-acquisition, post-acquisition-pre-playback, playback-active). Ensure matching-owner-only clear in every path.
3. **Fix `ActiveStreamPcmPlayback` completion ordering** (D9): defer `complete(...)` until after `cleanup()` in the `finally` block. Cancellation flows through the existing `ActivePcmPlayback.skip()` abstraction — no structured-concurrency coupling to the pump is required.
4. **Add the bootstrap prerequisite gate** (D1): construct + init + voice-select + probe the Android navigation TTS renderer before the `coroutineScope` that launches `prepareStt`/`prepareTts`. Add the `NeedsSetup` voice step and recheck-after-settings-return logic. Replace `BootstrapStage.RenderingAnnouncements` with the new probing stage. Remove `CoreInit.constructAnnouncer`, `buildVocabulary`, `voiceStylePath` from the interface.
5. **Wire navigation** (D6, D7): replace `PttForegroundService.enqueueRsmAnnouncement(key)` with text resolution from the live catalogue + renderer synthesis + `HostAudioCoordinator.play`. Add latest-wins logic: publish new generation → `TextToSpeech.stop()` → `cancelAndJoin` prior job/cleanup → launch new synthesis.
6. **Add bounded recovery** (D11) in the renderer: `Recovering` flag + `shutdown()` + reconstruct + re-probe + synthesize current newest generation only. Newer requests during recovery update authoritative newest without spawning a second recovery. If re-probe fails (missing/unusable voice): `NeedsSetup`. If re-probe succeeds but retried synthesis fails: retryable `BootstrapState.Failed`. In both cases the application leaves `Ready`.
7. **Remove cache and precompute** (D13): delete `AnnouncementPcmCache`, `AnnouncementCacheIdentity`, `AnnouncementRenderSettings`, `AnnouncementCacheCommitResult`, `SystemAnnouncer`, `AnnouncementResult`, `HostAudioFeedback.readyBeep()`, cache tests, cache manifest/WAV state under `noBackupFilesDir/announcement-cache`, and all `constructAnnouncer`/`buildVocabulary`/`voiceStylePath` call sites.

Each step is independently testable. Steps 1–3 can be validated without touching the navigation flow. Step 4 can be validated by confirming the bootstrap prerequisite order. Step 5 can be validated with RSM button simulation. Step 7 is pure deletion and can be validated by confirming the build compiles and no references to the removed types remain.

## Open Questions

None. All product decisions are resolved:

- The voice gate runs as a prerequisite before STT/Supertonic init; cold boot performs exactly one mandatory probe, zero eager navigation announcement syntheses (D1).
- Voice selection is deterministic with latency/quality/locale/name ordering; selects only voices declared not network-required, checks `setVoice` return (ERROR → shutdown + `NeedsSetup`, no probe); cannot inspect opaque engine internals — truly proving offline requires device acceptance with connectivity disabled (D2).
- The probe is real synthesis (D3).
- The synthesis path is `synthesizeToFile` only, never `speak`, with `Bundle.EMPTY` params, `TextToSpeech.stop()` + generation-ID rejection for stale-callback prevention, immediate `ERROR` return handling (unregister + delete + classify like `onError`), and bounded callback timeouts for both probe and runtime synthesis (D4).
- Normalization extends/replaces `WavPcmReader` to decode PCM8/PCM16/IEEE-float to mono `FloatArray` with downmix, reuses `TtsAudio.resample` + `f32ToPcm16`; rejects unsupported encodings and >2 channels (D5).
- Phrase text is resolved from the live catalogue at request time (D6).
- Latest-wins supersession order: publish new generation → `TextToSpeech.stop()` → `cancelAndJoin` prior job/cleanup → launch new; during `Recovering`, newer requests update authoritative newest without cancelling/restarting the single active recovery; no second recovery job (D7).
- `HostAudioCoordinator.play` has a `NonCancellable` `finally` envelope covering all three post-reservation cancellation windows (pre-acquisition, post-acquisition-pre-playback, playback-active): acquired resources released before matching-owner clear in every path (D8).
- `ActiveStreamPcmPlayback` completion is fixed: `cleanup()` before `complete(...)` in the `finally` block; `skip()`-driven termination publishes `ExplicitlySkipped`, never `Interrupted`; `Interrupted` reserved for actual pump-coroutine cancellation; caller `CancellationException` rethrows only after full skip/await/release/owner-clear under `NonCancellable` (D9).
- Route ownership is exact and unchanged (D10).
- Recovery uses a `Recovering` flag with one active recovery; atomic orchestration-mutex handoff on probe success prevents lost-request race; newer requests coalesce during recovery; re-probe failure → `NeedsSetup`, unsuperseded retried synthesis failure (same failure chain) → retryable `BootstrapState.Failed` with no second recovery; only a later independent failure after fully successful recovery+retry is a new failure; all failed paths drop the generation and leave `Ready`; no degraded mode (D11).
- Failure classification separates engine/voice failures (D11 recovery → `NeedsSetup` or retryable `BootstrapState.Failed`) from renderer/infrastructure failures (retryable `BootstrapState.Failed`, generation dropped); the application leaves `Ready` on every failure; no degraded mode exists (D12).
- Setup intent uses only public APIs: `ACTION_INSTALL_TTS_DATA` scoped via `setPackage(enginePackage)` primary, `Settings.ACTION_SETTINGS` fallback, re-probe on return; no hidden/non-SDK intents (D12b).
- All removed and added state is enumerated; `HostAudioFeedback.readyBeep()` is removed entirely (D13).