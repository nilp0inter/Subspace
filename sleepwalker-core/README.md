# sleepwalker-core

A Kotlin Android library that encodes symbolic HID commands into the sleepwalker
BLE protocol. The integrator owns the BLE connection; the library owns command
encoding, text/keymap resolution, mouse chunking, and status parsing.

For project-level context see the root [`README.md`](../../README.md). For the
formal contract see [`openspec/specs/sleepwalker-core/spec.md`](../../openspec/specs/sleepwalker-core/spec.md).

## Add the dependency

The library is an Android library module (`com.android.library`), not published
to Maven. Include it as a Gradle module:

1. Copy `android/sleepwalker-core/` into your project (or add it as a submodule).
2. Add `include(":sleepwalker-core")` to your `settings.gradle.kts`.
3. Add `implementation(project(":sleepwalker-core"))` to your app module's
   `build.gradle.kts`.

Requirements: `minSdk = 26`, Java/Kotlin target 17, no extra runtime
dependencies beyond the Android BLE APIs. Source: `android/sleepwalker-core/build.gradle.kts`.

## The two API tiers

**Low-level** (`LowLevelHid` / `MouseOps`) produces inspectable `LowLevelOp`
instances — one operation per key tap, mouse report, or safety transition.
**High-level** (`TextPlanner`) composes low-level ops from text plus a host
profile. Both tiers produce `LowLevelOp`; the integrator frames and sends them.

## Own the BLE connection

The integrator owns scan, connect, write, MTU, and notification handling. The
library provides transport helpers but no connection management.

GATT UUIDs to discover:

```kotlin
import io.sleepwalker.core.ble.BleUuids

BleUuids.SERVICE         // 0f1e2d3c-4b5a-6987-8765-4321fedcba98
BleUuids.RX_CHARACTERISTIC // 0f1e2d3c-4b5a-6987-8765-4321fedcba99  (write target)
BleUuids.TX_CHARACTERISTIC // 0f1e2d3c-4b5a-6987-8765-4321fedcba9a  (notify source)
```

MTU-aware write chunking:

```kotlin
import io.sleepwalker.core.ble.BleWriter

// Effective payload per write, given the negotiated ATT MTU.
val maxWrite: Int = BleWriter.maxWriteSize(mtu)

// Split a frame into chunks that fit the negotiated MTU.
val chunks: List<ByteArray> = BleWriter.chunkFrame(frame, mtu)
```

Status notification parsing:

```kotlin
import io.sleepwalker.core.hid.SessionStatusParser

// Parse a TX characteristic notification into a session status, or null.
val status: SessionStatus? = SessionStatusParser.parse(data)
// status.seqId, status.status, status.statusName, status.context
```

The canonical integration pattern lives in the reference app's
`SleepwalkerBleService.kt`: scan by device name `"sleepwalker"`, discover
`BleUuids.SERVICE`, enable TX notifications, request MTU 247, and write the RX
characteristic with `WRITE_TYPE_NO_RESPONSE`. The reference app holds BLE state
in static fields because it is driven by ADB broadcasts; integrators should
choose their own connection lifecycle.

## Low-level keyboard

```kotlin
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.toFrameBytes
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.protocol.Usages

val hid = LowLevelHidImpl()

val arm   = hid.arm()                              // safety: arm before HID
val tap   = hid.keyTap(Usages.USB_KEY_SPACE)       // symbolic USB HID usage
val disarm = hid.disarm()                          // safety: disarm when idle

// Frame, chunk, and send each op over your BLE connection.
listOf(arm, tap, disarm).forEach { op ->
    val frame = op.toFrameBytes()
    val chunks = BleWriter.chunkFrame(frame, negotiatedMtu)
    chunks.forEach { chunk ->
        rxChar.value = chunk
        rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(rxChar)
    }
}
```

`LowLevelHid` methods: `arm`, `disarm`, `kill`, `releaseAll`, `keyTap`,
`keyDown`, `keyUp`, `keyboardTapScript`, `mouseRelReport`, `nextSeqId`. Every
method takes an optional `seqId` (defaults to `nextSeqId()`, which wraps
1..0xFFFF skipping 0). `Usages` holds the symbolic USB HID usage registry
(`USB_KEY_A`..`USB_KEY_Z`, digits, punctuation, `USB_KEY_LEFTSHIFT`, etc.).

## High-level text

```kotlin
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.core.text.TextRenderingFailure
import io.sleepwalker.core.keymap.HostProfile

val hid = LowLevelHidImpl()
val planner = TextPlanner(hid = hid)

val result = planner.plan("Hello!", HostProfile.LINUX_US)
if (result.ok) {
    val ops = result.plan!!                      // List<LowLevelOp>
    // Optionally compile to compact keyboard tap scripts (fewer frames).
    val compiled = TapScriptCompiler.compile(ops, hid)
    compiled.forEach { op -> sendOp(op) }        // your BLE send path
} else {
    when (val failure = result.failure!!) {
        is TextRenderingFailure.MissingLayout ->
            error("no keymap for ${failure.profile}")
        is TextRenderingFailure.UnrepresentableGlyph ->
            error("cannot type '${failure.ch}' on ${failure.profile}")
    }
}
```

`TextPlanner.plan(text, profile)` returns a `TextPlan` with an inspectable
list of low-level keyboard operations, or a structured `TextRenderingFailure`.
On failure, no HID operations are emitted for that request. `TapScriptCompiler.compile(ops, hid)`
folds consecutive key-down/up/tap ops into batched `keyboardTapScript` frames
(default batch size 32).

The seed keymap database covers US QWERTY printable ASCII only.
`KeymapDatabase` is the extension point for a larger corpus; `HostProfile`
(`hostOs`, `layout`, `variant`) is the lookup key.

## Mouse

```kotlin
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.MouseOps
import io.sleepwalker.core.protocol.MouseRel

val hid = LowLevelHidImpl()
val mouse = MouseOps(hid)

val clickOps: List<LowLevelOp> = mouse.leftClick()        // down + up
val moveOps:  List<LowLevelOp> = mouse.move(dx = 300, dy = -150)
val scrollOps: List<LowLevelOp> = mouse.scroll(amount = 5)
val panOps:   List<LowLevelOp> = mouse.pan(amount = 3)
val release:  LowLevelOp       = mouse.releaseButtons()

// Button constants for buttonDown/buttonUp/click:
MouseRel.BUTTON_LEFT    // 0x01
MouseRel.BUTTON_RIGHT   // 0x02
MouseRel.BUTTON_MIDDLE  // 0x04
```

`move`, `scroll`, and `pan` auto-chunk large deltas into signed 8-bit reports
(`MouseChunker.MAX_DELTA = 127`, `MIN_DELTA = -128`). Each chunk is a separate
`LowLevelOp` with its own `seqId`; send them in order.

## Status correlation

Every `LowLevelOp` carries a `seqId` (1..0xFFFF, wrapping, skipping 0). The
firmware ACKs each frame with a status notification carrying the same `seqId`.
Parse TX notifications with `SessionStatusParser.parse(data)` and correlate by
`seqId`.

Status names: `received`, `queued`, `sent_to_usb`, `disarmed`, `killed`, and
error statuses `malformed`, `bad_crc`, `queue_full`, `usb_not_mounted`,
`unsupported_opcode`.

## Safety model

The firmware starts DISARMED and rejects HID opcodes until `arm()` is sent.
`disarm()` releases all keys/buttons and returns to DISARMED. `kill()` forces
release-all plus DISARMED. `releaseAll()` releases held keys/buttons without
disarming. Arm before HID sequences; disarm when idle or on error.