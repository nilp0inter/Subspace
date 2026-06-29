## Why

The `SttTtsController` (the round-trip STT↔TTS test mode) depends on **both** the Parakeet transcriber and the Supertonic synthesizer. After `fix-jni-startup-ui-blocking` made the two engine initializations run in parallel on `Dispatchers.IO`, the controller is constructed inside the TTS post-init block with a one-shot peek at the `sttTranscriber` field. If the TTS init coroutine completes before the STT init coroutine — which is the **first-run norm**, because Parakeet's int8 extraction (~670 MB, dominated by the 622 MB `encoder-model.int8.onnx`) is larger than Supertonic's (~398 MB) — that peek sees `null` and `SttTtsController` is **never** constructed. STT↔TTS test mode is then unavailable until the user force-stops and relaunches the app. The "accepted limitation" recorded in the prior change was mis-priced: it assumed STT finishes first, but the current asset versions invert that, so the race loses on the common first-install path, not just a rare warm-run fluke.

## What Changes

- Introduce a single `CompletableDeferred<SttTranscriber?>` rendezvous field on `PttForegroundService`. The STT off-main init coroutine completes it exactly once — with the transcriber on success, with `null` on failure — in addition to setting `sttTranscriber` and launching the STT controller/poller/collector.
- Replace the TTS post-init block's one-shot `if (sttTranscriber != null)` peek with an awaiting launch: `serviceScope.launch { val transcriber = sttReady.await(); if (transcriber != null) { construct SttTtsController + start its status collector } }`. The announcer, TTS controller, TTS model-status poller, and TTS status collector remain unconditional on TTS success (unchanged).
- `SttTtsController` is therefore constructed once **both** engines are ready, in **either** completion order, with no relaunch required. The two engine inits stay fully parallel; only the cross-engine controller waits for the slower one.
- Failure semantics are preserved and made explicit: if STT init fails, the await resolves to `null` and `SttTtsController` is not constructed (it needs the transcriber); if TTS init fails, the TTS post-init block never runs. No controller is constructed against a failed engine.

## Capabilities

### New Capabilities
<!-- None. This change fixes a startup-availability defect in an existing capability; it does not introduce one. -->

### Modified Capabilities
- `ptt-stt-tts-test`: Add a requirement that the STT↔TTS controller becomes available once both the Parakeet and Supertonic engines have finished initializing, regardless of which completes first, so STT↔TTS test mode is usable on first run without a relaunch. Add scenarios for either-completion-order availability and for each engine's failure leaving the controller unconstructed.

## Impact

- **Affected code**: `PttForegroundService.initializeStt` (complete the new deferred), `PttForegroundService.initializeTts` (await the deferred before constructing `SttTtsController`), and one new `CompletableDeferred` service field. The Rust crates, the JNI bridge `init` blocks, the asset extractors, and `voiceStyleFile` are unchanged.
- **No API changes**: The Kotlin `SttTranscriber` / `TtsSynthesizer` ports and the `SttTtsController` constructor are unchanged. Only the **timing** of `SttTtsController` construction changes (built after the slower engine instead of opportunistically), and only when the slower engine is STT.
- **Behavioral change**: On first run with STT-init-wins-reversed ordering (the current norm), STT↔TTS test mode becomes available a few seconds after app start (once Parakeet finishes) instead of being permanently unavailable until relaunch. On warm run and on STT-finishes-first orderings, behavior is unchanged.
- **Lifecycle**: The awaiting launch runs on `serviceScope`, so the existing `serviceScope.cancel()` in `onDestroy` cancels it if the service dies mid-await — no dangling coroutine.
- **Testability**: The rendezvous lives inside the service, which the fakes-based unit tests do not exercise. The change adds a focused unit test only if the join logic is extracted into a testable seam; otherwise it is covered by the manual acceptance check.
- **Out of scope**: The `transcriptionService` / `initializeJournal` "startup reads an async-produced field synchronously" cousin (journal STT falls back to the no-op transcriber and is never re-bound) is a separate defect in the same family and is deferred to its own change.
- **Dependencies**: None added or removed.
