## 1. Atomic Source-Scoped Cancellation

- [x] 1.1 Add typed source-scoped cancellation eligibility and disposition models carrying only semantic session ID, source, phase, reason, and acceptance outcome.
- [x] 1.2 Implement atomic source match, optional pending-only validation, and terminal cancellation claim inside `PttAudioSessionManager` without check-then-act snapshots.
- [x] 1.3 Expose dispatcher operations for source-scoped cancellation and one explicitly teardown-only all-source cancellation path while preserving exactly-once terminal effects.

## 2. RSM Serial Ownership Isolation

- [x] 2.1 Change automatic/manual SPP session termination to request cancellation only for `PttSource.Rsm` while retaining connection-state publication and reconnect scheduling.
- [x] 2.2 Change explicit serial disconnect to cancel only RSM-owned input, close RSM resources, and preserve pending or active Phone and CarTelecom sessions.
- [x] 2.3 Gate foreground-service shutdown after explicit serial disconnect so non-RSM input ownership survives until its own terminal completion and idle shutdown reevaluation.

## 3. CarTelecom Ownership Isolation

- [x] 3.1 Replace CarTelecom setup-failure broad cancellation with pending-only `PttSource.CarTelecom` cancellation and operation-owned admission/reservation cleanup.
- [x] 3.2 Make Telecom route timeout cancel only CarTelecom-owned input while always retiring stale coordinator connection, stability, retry, and expected-device state.
- [x] 3.3 Suppress stale car error feedback and car media-state mutation when a Telecom callback does not own the current audio session.
- [x] 3.4 Preserve source-checked normal Telecom capture release and ensure abort callback recursion remains an observable exactly-once no-op.

## 4. Global Teardown Cutover

- [x] 4.1 Classify every existing `forceReleaseActivePtt` caller as RSM, CarTelecom, Phone, or true global teardown and migrate it to the corresponding ownership API.
- [x] 4.2 Keep runtime/service shutdown on the explicit all-source operation and remove the ordinary broad force-release entry point after all source-specific callers are migrated.

## 5. Persistent Cancellation Diagnostics

- [x] 5.1 Persist source-scoped and global cancellation request events with semantic caller, requested source, current session ID/source/phase, disposition, and normalized reason.
- [x] 5.2 Persist audio-session terminal claim and completion events with claim category and cleanup failure categories while omitting captured and channel content.
- [x] 5.3 Persist RSM SPP attempt/session termination with automatic/manual mode, ever-connected state, monitoring state, and semantic reconnect disposition.
- [x] 5.4 Audit the new diagnostics so they contain no Bluetooth address, device identifier, PCM or encoded audio, transcript, credential, or channel message content.
