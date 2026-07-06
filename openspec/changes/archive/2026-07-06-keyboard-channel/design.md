## Context

Subspace already has a working capture → STT pipeline: `CaptureService` records
PCM from the active audio route (RSM SCO, car Telecom, or local mic),
`PcmTranscriber` (backed by the Parakeet JNI bridge) turns the PCM into text,
and `SttController` orchestrates the lifecycle against a `ResolvedAudioRoute`.
Today that text is terminal — it surfaces in `SttStatus.Transcribed` and either
displays on the phone (debug STT mode) or lands in the journal markdown.

`sleepwalker` (github.com/nilp0inter/sleepwalker) is a separate project: an
ESP32-S3 firmware exposing a BLE control surface plus a Kotlin library
(`sleepwalker-core`) that encodes symbolic HID commands into framed BLE
payloads. The library is transport-agnostic — the integrator owns scan,
connect, write, and notify handling. Its two-tier API mirrors raw HID
(`LowLevelHidImpl`: `arm`, `keyTap`, `disarm`, `kill`, `releaseAll`) and
resolves text to ops (`TextPlanner.plan(text, HostProfile)` →
`List<LowLevelOp>`, optionally folded by `TapScriptCompiler`).

The `Channel` sealed interface (`Channel.kt`) currently has two subtypes:
`JournalChannel` (index 0) and `DebugChannel` (index 1). `ChannelRepository`
persists both and is the single source of truth for `loadChannels()` ordering.
`PttForegroundService` dispatches PTT press/release/cancel through a
`when (activeChannelId)` block, with one arm per channel ID; `DebugChannel`
further fans out on `DebugMode` (ECHO/STT/TTS/STT_TTS). Each channel has a
controller (`EchoController`, `SttController`, `TtsController`, `SttTtsController`,
`JournalPttController`) with a uniform `onPttPressed(route)` /
`onPttReleased(route)` / `cancelAndRelease()` shape.

Constraints:
- The repo is a Nix flake evaluated from Git source; new files consumed by
  `flake.nix` or `settings.gradle.kts` must be `git add`-ed before evaluation.
- `minSdk = 31`; `sleepwalker-core` requires `minSdk = 26`, satisfied.
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` are already declared and runtime-
  requested for the RSM; the sleepwalker scan reuses the same grants.
- No new Maven dependencies; `sleepwalker-core` depends only on Android BLE.
- The STT model loads asynchronously (`sttReady` `CompletableDeferred`); the
  keyboard controller must wait for it like `SttController` does.

## Goals / Non-Goals

**Goals:**
- A `KeyboardChannel` that, on a PTT press→release cycle, captures audio,
  transcribes it with the existing STT pipeline, and types the transcript into
  a paired sleepwalker host as physical keyboard input.
- Reuse the existing `CaptureService` + `PcmTranscriber` + `ResolvedAudioRoute`
  plumbing without duplication.
- Reuse `sleepwalker-core`'s `TextPlanner` / `TapScriptCompiler` / `LowLevelHid`
  for HID encoding; do not reimplement the protocol.
- A BLE connection manager owned by `PttForegroundService` that scans, connects,
  and frames writes per the library contract, with a `StateFlow` connection
  state surfaced to the UI.
- Channel readiness that reflects both the bridge connection and the host
  profile, so the dashboard and Android Auto browse tree show Standby vs Ready
  correctly.
- Persist host profile selection across restarts.

**Non-Goals:**
- Mouse injection. The keyboard channel types text only; `MouseOps` is out of
  scope for this change.
- A keymap database beyond the seed US QWERTY. `HostProfile.LINUX_US` is the
  default; extending `KeymapDatabase` is the library's concern, not this
  change's.
- Streaming/continuous transcription. The channel transcribes one capture per
  PTT cycle, matching `SttController`'s batch semantics.
- Replacing the reference app's connection pattern. Subspace owns its own
  connection lifecycle; it does not copy `SleepwalkerBleService.kt`'s
  static-field ADB-broadcast design.
- Audio playback of the transcript. There is no TTS leg; the output is HID
  only. (A future STT→TTS→Keyboard composition is not precluded but is out of
  scope.)
- Modifying the STT model or transcriber. The keyboard channel consumes
  `PcmTranscriber` as-is.

## Decisions

### D1: Vendor `sleepwalker-core` as a repo-local Gradle module

Copy `android/sleepwalker-core/` from the sleepwalker repo into
`sleepwalker-core/` at the Subspace repo root. Add `include(":sleepwalker-core")`
to `settings.gradle.kts` and `implementation(project(":sleepwalker-core"))` to
`app/build.gradle.kts`.

**Rationale**: The library is not published to Maven (per its README). A git
submodule would complicate Nix flake evaluation (Nix evaluates from Git source
and submodules require explicit init in the flake). A vendored copy is the
simplest path that keeps the build hermetic. The library is small and
auditable; updates are a deliberate copy.

**Alternatives considered**:
- *Git submodule*: rejected — flake evaluation friction and extra init steps.
- *Maven publication*: rejected — out of scope for this change and the library
  is not currently published.

### D2: `KeyboardPttController` mirrors `SttController`, then types

The controller reuses the capture→transcribe half of `SttController`'s state
machine (`WaitingForAudio` → `Recording` → `Transcribing`), then on a
successful transcript enters a `Typing` state, plans the text via
`TextPlanner`, compiles via `TapScriptCompiler`, and sends each `LowLevelOp`
frame over the BLE connection. On completion it transitions to `Done`; on
failure to `Error`. `output.releaseRoute()` is called in a `finally` block
after typing completes or fails, matching `SttController`'s route-release
discipline.

**Rationale**: `SttController` already solves capture lifecycle, max-duration
retention, empty-audio handling, and route release. Duplicating that logic
would be a bug farm. The keyboard channel is STT + a sink; the only new
behavior is the sink.

**Alternatives considered**:
- *Generalize `SttController` with a pluggable sink*: tempting, but the STT
  controller's status type (`SttStatus`) is tightly coupled to its states.
  A new controller with a `KeyboardStatus` type is clearer and avoids touching
  the proven STT path. The capture half is small enough to duplicate
  intentionally.
- *Compose `SttController` + a listener*: rejected — `SttController` owns its
  own route release and has no "transcript ready" callback hook.

### D3: BLE connection manager as a long-lived `SleepwalkerBleConnection`

A new `SleepwalkerBleConnection` class holds the `BluetoothGatt`, the RX/TX
characteristics, the negotiated MTU, and a `StateFlow<KeyboardConnectionState>`
(Disconnected → Scanning → Connecting → Connected → Disconnected). It exposes
`suspend fun sendOp(op: LowLevelOp)` that frames (`op.toFrameBytes()`), chunks
(`BleWriter.chunkFrame(frame, mtu)`), and writes each chunk to RX with
`WRITE_TYPE_NO_RESPONSE`. To prevent BLE queue saturation under HFP/SCO load,
consecutive characteristic writes are spaced out by a 15ms delay.
TX notifications are parsed by `SessionStatusParser` and exposed via a `statusFlow`
so that callers can correlate command completion. The GATT connection handshake
is fully serialized: `requestMtu(247)` is sent first, followed by the notification
descriptor write once the MTU updates, and transitioning to `Connected` only after
the descriptor write completes.

The connection is owned by `PttForegroundService` (created in `onCreate`,
torn down in `onDestroy`), not by the controller. The controller receives the
connection as a constructor dependency and only sends ops while connected.

**Rationale**: The library contract puts connection ownership on the
integrator. A single long-lived connection survives channel switches and PTT
cycles; reconnecting per PTT press would add seconds of latency. Putting it on
the service (not the controller) keeps the controller testable with a fake
connection.

**Alternatives considered**:
- *Per-press connect*: rejected — latency and BLE stack churn.
- *Connection inside the controller*: rejected — couples controller to Android
  BLE APIs and breaks testability.

### D4: Safety discipline — arm before HID, disarm after

Every typing sequence is wrapped: `hid.arm()` → typed ops → `hid.disarm()`.
Before sending the `disarm` command, the controller suspends on the `awaitAck`
facility to wait for the `SENT_TO_USB` status notification (correlation ACK)
of the last keystroke operation in the compiled tap script. This prevents the safety arm from
deactivating while the firmware is still typing the last segment over USB.
On any error (BLE disconnect, plan failure, exception), the controller calls
`hid.kill()` (force release-all + disarm) if the connection is still up. The
firmware starts DISARMED and rejects HID opcodes until armed, so a crash or
lost frame cannot leave the host with held keys.

**Rationale**: The sleepwalker safety model is explicit; the channel must honor
it. `kill()` on error is the conservative choice — it guarantees no stuck
modifiers even if a typing sequence is interrupted mid-stream.

### D5: Readiness = bridge connected AND host profile configured

`KeyboardChannel.isReady` evaluates to `true` when a `HostProfile` is selected
(persisted, non-null) AND the bridge reports `Connected`. STT model readiness
is *not* part of `isReady` — it is a runtime monitor state
(`sttModelStatus`), exactly as `DebugChannel`'s STT mode treats it. A channel
that is Ready but whose STT model is still loading will, on PTT press, surface
`KeyboardStatus.Error("STT model not ready")` rather than silently failing.

**Rationale**: `isReady` drives the dashboard/Android Auto browse tree
(`ChannelStatusKind.Active/Ready/Standby`). STT model load is a one-time
startup cost that resolves to Ready within seconds; surfacing it as Standby
would be misleading. Keeping the rule identical to `DebugChannel` STT mode is
consistent.

### D6: `orderIndex` = 2 for `KeyboardChannel`

`Channel.orderIndex` gains a `KeyboardChannel -> KEYBOARD_ORDER_INDEX = 2`
branch, placing it after Journal (0) and Debug (1). `ChannelRepository.loadChannels()`
includes `loadKeyboard()` in its list.

### D7: Host profile selection persisted in `ChannelRepository`

A new `KEY_KEYBOARD_HOST_PROFILE` pref stores the `HostProfile` name
(`HostProfile.LINUX_US.name`). `loadKeyboard()` reads it; `saveKeyboard(channel)`
writes it. The config screen offers the set of profiles the library supports
(today: `LINUX_US`); the list is sourced from `HostProfile.values()` so it
tracks the library.

## Risks / Trade-offs

- **BLE coexistence with RSM SCO**: The phone maintains an HFP/SCO connection
  to the RSM while simultaneously running a BLE GATT connection to the
  sleepwalker. Both share the Bluetooth controller. In practice BLE and
  classic Bluetooth coexist, but aggressive SCO scheduling on some chipsets can
  starve BLE throughput. *Mitigation*: HID frames are tiny and
  `WRITE_TYPE_NO_RESPONSE` is non-blocking; typing latency is dominated by the
  STT pass, not BLE. If throughput collapses on real hardware, the fallback is
  to queue ops and drain after SCO release — deferred until measured.
- **`WRITE_TYPE_NO_RESPONSE` reliability**: No-response writes can be dropped
  by the controller under load. The firmware ACKs each frame via TX
  notifications (`SessionStatusParser`), but the controller does not await
  ACKs. A dropped frame means a missing keystroke. *Mitigation*: For the MVP,
  accept best-effort typing and log `bad_crc`/`queue_full` statuses. A
  retry-on-ACK-status path is a future enhancement if dropouts are observed.
- **Text rendering failure**: `TextPlanner.plan` can return
  `TextRenderingFailure.UnrepresentableGlyph` for characters outside the seed
  keymap (e.g., accented letters, emoji). *Mitigation*: On failure, the
  controller transitions to `KeyboardStatus.Error` with the failure reason and
  skips typing; the transcript is not partially typed. A future "skip
  unrepresentable chars" mode is possible but not in scope.
- **Vendored library drift**: A copy of `sleepwalker-core` will drift from
  upstream. *Mitigation*: Record the upstream commit in a
  `sleepwalker-core/UPSTREAM` file. Updates are a deliberate re-copy.
- **Connection lifecycle vs. service lifecycle**: The BLE connection outlives
  a single PTT cycle but is tied to `PttForegroundService`. If the service is
  stopped (e.g., SPP disconnect to RSM), the bridge disconnects too. This is
  acceptable — the keyboard channel is meaningless without the RSM capture
  path anyway.
- **Testability**: The controller depends on `SleepwalkerBleConnection` (Android
  BLE). Pure-JVM tests use a fake connection that records sent ops; the
  `TextPlanner`/`TapScriptCompiler` path is JVM-testable directly. Instrumented
  tests against real hardware are out of scope for this change.
