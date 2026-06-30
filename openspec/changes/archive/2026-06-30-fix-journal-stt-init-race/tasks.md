## 1. Move Journal controller construction behind `sttReady.await()`

- [x] 1.1 In `PttForegroundService.initializeJournal` (`PttForegroundService.kt:408`), wrap the controller construction in `serviceScope.launch { val transcriber = sttReady.await(); … }`. The `sttReady` `CompletableDeferred<SttTranscriber?>` already exists at `PttForegroundService.kt:137` and is completed on both success and failure paths of the STT init coroutine.
- [x] 1.2 Inside the awaiting coroutine, on the success path (`transcriber != null`), construct `TranscriptionService(transcriber)` and pass it to `JournalController` as the `transcriber` parameter, replacing the current synchronous `transcriptionService ?: <no-op>` read at line 409.
- [x] 1.3 Inside the awaiting coroutine, on the failure path (`transcriber == null`), use the existing no-op `PcmTranscriber` (the `object : PcmTranscriber { override suspend fun transcribe(...) = throw IllegalStateException("STT transcriber unavailable") }` at lines 409-413) so the failure semantics are preserved: captures persist as WAV files with failed transcription state.
- [x] 1.4 Construct `JournalPttController` (currently at lines 419-427) inside the awaiting coroutine, after `JournalController`, and assign it to the `journalPttController` service field. The `JournalPttController` constructor parameters (`scope`, `sco`, `output`, `captureService`, `source`, `journal`, `channelProvider`) are unchanged; only the timing of construction changes.

## 2. Move recovery and base-directory collector into the awaiting coroutine

- [x] 2.1 Move the initial `runRecovery(baseDir)` call (currently at lines 429-433, synchronous after controller construction) inside the awaiting coroutine, after `JournalPttController` is constructed and assigned, so recovery runs with the real transcriber wired.
- [x] 2.2 Move the `_appState.collect { … runRecovery(currentDir) }` collector (currently at lines 435-444, launched from `initializeJournal`) inside the awaiting coroutine, after the initial recovery call, so the collector triggers recovery against the real transcriber on subsequent base-directory changes.
- [x] 2.3 Verify by inspection that the base-directory collector replays the current `_appState` value on collection start (StateFlow semantics), so a base directory selected during the await window is not lost.

## 3. Verify null-safety during the construction window

- [x] 3.1 Verify by inspection that all `journalPttController?.` call sites (`onPttPressed` at line 1008, `onPttReleased` at line 1077, `cancelAndRelease` at lines 479, 604, 717, 1205) are null-safe and no-op when `journalPttController` is `null` during the construction window.
- [x] 3.2 Verify by inspection that `onDestroy`'s `journalPttController?.cancelAndRelease()` (line 402) is safe when the field is still `null` (the await has not resolved) — the `?.` operator makes it a no-op, and `serviceScope.cancel()` cancels the awaiting launch so no controller is posted to a destroyed service.

## 4. Verification

- [x] 4.1 Build: `nix develop -c gradle compileDebugKotlin` compiles clean.
- [x] 4.2 Run `nix develop -c gradle test` and confirm the full unit test suite passes unchanged (`JournalControllerTest` tests the controller with a fake transcriber directly and is unaffected by the service-internal timing change).
- [x] 4.3 On `B02PTT-FF01`, clear app data (`adb shell pm clear dev.nilp0inter.subspace`) to force first-run extraction, launch the app, select a base directory for the Journal channel, enable "Save in journal file", press PTT, speak, release, and confirm the generated daily markdown contains the transcribed text instead of `[Transcription failed: STT transcriber unavailable]` — **without** force-stopping or relaunching the app.
- [x] 4.4 Confirm the debug-channel STT test mode remains independently available and unaffected by this change.