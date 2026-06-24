## 1. Foundation: SystemAnnouncer

- [x] 1.1 Create `SystemAnnouncer.kt` in `dev.nilp0inter.subspace.audio`.
- [x] 1.2 Implement the internal state: a `ConcurrentHashMap<String, RecordedPcm>` for caching and a `Job?` reference for the active playback job to support aggressive cancellation.
- [x] 1.3 Implement `precompute(vocabulary: Map<String, String>, voiceStylePath, ...)` that suspends until `synthesizer.modelStatus == Ready`, loops through the vocabulary, synthesizes text off the main thread, processes the float arrays through `TtsAudio.toScoPlayback(..., scoRate)`, and populates the cache.
- [x] 1.4 Implement `announce(key: String, sco: ScoRoute, output: PcmOutput)` that cancels any existing playback job, acquires `ScoRoute`, plays the cached `RecordedPcm` (or falls back to `output.playReadyBeep` if cache misses), and finally releases `ScoRoute`.

## 2. Service Integration

- [x] 2.1 Instantiate the `SystemAnnouncer` in `PttForegroundService.kt`.
- [x] 2.2 Construct the vocabulary map (e.g. `"sys.menu.channels"` -> `"Channels"`, `"chan.journal.name"` -> `"Journal Channel"`, `"chan.journal.selected"` -> `"Journal Channel Selected"`, etc., for both Journal and Debug channels).
- [x] 2.3 Immediately after `ttsSynthesizer` initialization in `PttForegroundService`, invoke `SystemAnnouncer.precompute()` with the vocabulary map.

## 3. Navigation State Mutations

- [x] 3.1 Implement a `cycleActiveChannel(next: Boolean)` private function in `PttForegroundService`. It should resolve the current `activeChannelId` against a fixed list `[JournalChannel.ID, DebugChannel.ID]`, compute the new index (with wraparound), and call `setActiveChannelId(newId)`.
- [x] 3.2 Add the audio trigger to `cycleActiveChannel` so it immediately invokes `announcer.announce("chan.$newId.name")` after updating the state.

## 4. Hardware Event Mapping

- [x] 4.1 In `PttForegroundService.handleRawButtonEvent`, intercept `RawButtonEvent.GroupPressed`. If it triggers entry to `HardwareMode.Control`, invoke `announcer.announce("sys.menu.channels")`.
- [x] 4.2 In `handleRawButtonEvent`, intercept `RawButtonEvent.VolumeUpClicked`. If the hardware is in `HardwareMode.Control`, execute `cycleActiveChannel(next = true)`.
- [x] 4.3 In `handleRawButtonEvent`, intercept `RawButtonEvent.VolumeDownClicked`. If the hardware is in `HardwareMode.Control`, execute `cycleActiveChannel(next = false)`.
- [x] 4.4 In `handleRawButtonEvent`, intercept `RawButtonEvent.PttPressed`. If the hardware is in `HardwareMode.Control`, invoke `announcer.announce("chan.$activeChannelId.selected")` to confirm the selection (note: the `ButtonStateMachine` will automatically revert to `ActiveMode` on PTT).

## 5. Verification

- [x] 5.1 Flash build to `B02PTT-FF01` device and test full navigation matrix. Ensure audio is instantaneous, rapid Vol Up clicks truncate preceding audio clips cleanly, and the Main Dashboard Compose UI perfectly syncs with the headset channel name announcements.
