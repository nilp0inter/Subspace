## 1. Rendezvous field

- [x] 1.1 Add a `private val sttReady = CompletableDeferred<SttTranscriber?>()` field to `PttForegroundService` near the existing `sttTranscriber` field. `CompletableDeferred` is already imported (`PttForegroundService.kt:86`); `SttTranscriber` is the existing type of `sttTranscriber`.

## 2. Complete the deferred from the STT init coroutine

- [x] 2.1 In `initializeStt` (`PttForegroundService.kt:240`), call `sttReady.complete(transcriber)` after the `try { ... } catch { null }` block that yields the nullable `transcriber`, so it resolves on both the success path (the transcriber) and the failure path (`null`). Place it before the existing `if (transcriber != null) { sttTranscriber = transcriber; serviceScope.launch { ... } }` success block.
- [x] 2.2 Verify by inspection that `sttReady.complete(...)` is reached on every exit of the STT coroutine's init (the existing `try/catch` already produces `null` on failure), so `sttReady.await()` can never hang.

## 3. Await the deferred before constructing SttTtsController

- [x] 3.1 In `initializeTts`'s post-init `serviceScope.launch` block (`PttForegroundService.kt:309`), replace the one-shot peek `val sttTransc = sttTranscriber; if (sttTransc != null) { ... }` (currently at `PttForegroundService.kt:346-365`) with `serviceScope.launch { val transcriber = sttReady.await(); if (transcriber != null) { ... } }`. Pass the awaited `transcriber` to the `SttTtsController` constructor (it already takes a `transcriber` parameter) and keep the `sttTtsController` status-collector launch inside the awaiting block.
- [x] 3.2 Verify by inspection that the announcer, `ttsController`, `ttsModelStatusJob` poller, and the `ttsController` status collector remain **unconditional** on TTS success and unchanged — only `SttTtsController` and its own collector are now gated by the `sttReady.await()`.

## 4. Verification

- [x] 4.1 Build: `nix develop -c gradle compileDebugKotlin` compiles clean.
- [x] 4.2 Run `nix develop -c gradle test` and confirm the full unit test suite passes unchanged (the join is service-internal; the fakes-based controller tests are unaffected).
- [x] 4.3 On `B02PTT-FF01`, clear app data (`adb shell pm clear dev.nilp0inter.subspace`) to force first-run extraction, launch the app, and confirm STT↔TTS test mode is usable (a PTT round-trip transcribes then speaks back) **without** force-stopping or relaunching. This reproduces the TTS-finishes-first ordering (Parakeet's ~670 MB extraction is the larger one) that previously lost the race and left `SttTtsController` permanently unconstructed.
- [x] 4.4 Confirm STT and TTS test modes remain independently available after first run, and that echo / STT / TTS / STT↔TTS mutual exclusion is unchanged.