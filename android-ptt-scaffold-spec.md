# Android PTT Bluetooth Scaffold Specification (Subspace)

## 1. Purpose

Build a minimal native Android application that proves the Bluetooth behavior needed for a future push-to-talk controller.

The scaffold has two screens:

| Screen | Purpose |
| --- | --- |
| Connection screen | Find, pair, connect, and validate the target Bluetooth remote speaker microphone. |
| Monitor screen | Continuously display button state from the serial interface and optionally run a headset echo test. |

The application intentionally does not implement speech-to-text, text-to-speech, command execution, cloud services, menus, persistence, or automation.

The application must prove these capabilities:

| Capability | Required behavior |
| --- | --- |
| Button control | Read raw button events from the device over Bluetooth Classic SPP/RFCOMM. |
| Headset output | Play a short ready beep through the Bluetooth headset speaker over HFP/SCO. |
| Headset input | Record microphone audio from the Bluetooth headset over HFP/SCO. |
| Echo test | On PTT press, beep and record. On PTT release, play the captured audio back through the headset. |

## 2. Platform Baseline

| Item | Requirement |
| --- | --- |
| Language | Kotlin |
| Android support | Modern Android only |
| Minimum SDK | API 31, Android 12 |
| Target SDK | Current stable Android SDK at implementation time |
| UI toolkit | Kotlin-native UI; Jetpack Compose is recommended but not required |
| Bluetooth APIs | Public Android Bluetooth and AudioManager APIs only |
| Hidden APIs | Not allowed |

API 31 is the baseline because Android 12 introduced the modern `AudioManager.setCommunicationDevice(...)` model. Older SCO APIs such as `startBluetoothSco()` are not part of this scaffold.

## 3. Non-goals

| Non-goal | Rationale |
| --- | --- |
| No STT | The scaffold only validates Bluetooth control and audio plumbing. |
| No TTS | The ready signal is a generated beep, not speech. |
| No A2DP | The target use case is HFP/headset audio. |
| No persistent recordings | Echo audio is memory-only. |
| No command execution | The app only displays input and echoes audio. |
| No support for arbitrary device models | The target is the known `B02PTT-FF01` device. |
| No forced HFP profile connection via hidden APIs | Android public APIs expose route selection, not unrestricted profile control. |
| No background automation beyond service safety | The scaffold may use a foreground service for connection ownership, but its UI remains the primary control surface. |

## 4. Target Device

| Property | Value |
| --- | --- |
| Device name | `B02PTT-FF01` |
| Bluetooth type | Bluetooth Classic |
| Control service | Serial Port Profile, SPP |
| SPP UUID | `00001101-0000-1000-8000-00805f9b34fb` |
| Audio profile | HFP/HSP headset profile exposed to Android as Bluetooth SCO communication audio |
| A2DP requirement | None |

The same physical device exposes two independent Bluetooth capabilities:

| Capability | Android surface | Purpose |
| --- | --- | --- |
| SPP/RFCOMM serial socket | `BluetoothSocket` | Button events. |
| HFP/SCO headset audio | `AudioManager`, `AudioRecord`, `AudioTrack` | Microphone input and speaker output. |

These channels are independent. Connecting the serial socket does not make audio available. Selecting the headset audio route does not provide button events.

## 5. Runtime Permissions

The app requests permissions inline from the connection screen.

Required manifest entries:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

`POST_NOTIFICATIONS` is required on Android 13+ if the app uses a foreground service notification.

If the first version keeps all work strictly in the foreground activity, foreground service permissions can be deferred. The recommended architecture still uses a bound foreground service so the Bluetooth socket and audio route are not destroyed by configuration changes.

## 6. High-level Architecture

Recommended components:

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Hosts the two views and binds to the service. |
| `PttForegroundService` | Owns Bluetooth connection, serial read loop, audio controller, and echo state. |
| `ConnectionViewModel` | Exposes connection state and user actions to the connection screen. |
| `MonitorViewModel` | Exposes button states, hardware mode, echo toggles, and audio state. |
| `DeviceScanner` | Finds the target Bluetooth device by name. |
| `SppClient` | Connects to SPP/RFCOMM and continuously reads bytes. |
| `ButtonParser` | Converts serial bytes into raw button events. |
| `HardwareModeTracker` | Maintains Active/Control mode and display states. |
| `ScoAudioController` | Selects/releases Bluetooth SCO communication audio. |
| `EchoController` | Implements PTT beep, recording, playback, and SCO keep-warm timer. |

Recommended package layout:

```text
app/src/main/java/.../pttscaffold/
  MainActivity.kt
  service/PttForegroundService.kt
  bluetooth/DeviceScanner.kt
  bluetooth/SppClient.kt
  protocol/ButtonParser.kt
  protocol/HardwareModeTracker.kt
  audio/ScoAudioController.kt
  audio/EchoController.kt
  audio/InMemoryRecorder.kt
  ui/ConnectionScreen.kt
  ui/MonitorScreen.kt
  ui/ConnectionViewModel.kt
  ui/MonitorViewModel.kt
  model/Models.kt
```

## 7. Application Navigation

The app has exactly two primary views.

| View | Entry condition | Exit condition |
| --- | --- | --- |
| Connection screen | App start or connection/audio readiness lost. | Device is bonded, SPP is connected, and Bluetooth SCO communication device is available. |
| Monitor screen | All readiness checks pass. | SPP disconnects, Bluetooth turns off, permissions are revoked, or headset route is no longer available. |

The monitor screen does not require SCO to be currently active. It requires that the paired headset appears as an available Bluetooth SCO communication device. SCO is activated lazily by the echo feature.

Readiness definition:

```text
readyForMonitor =
  bluetoothEnabled &&
  requiredPermissionsGranted &&
  targetDeviceBonded &&
  sppSocketConnected &&
  bluetoothScoCommunicationDeviceAvailable
```

## 8. Connection Screen Requirements

The connection screen helps the user reach the monitor screen.

It shows one state summary table:

| Status row | Possible values |
| --- | --- |
| Permissions | Missing, granted |
| Android Bluetooth | Off, on |
| Target device | Not found, found, bonded |
| Serial control channel | Disconnected, connecting, connected, failed |
| Headset audio capability | Unavailable, available |
| Overall readiness | Not ready, ready |

It provides these actions when applicable:

| Action | Behavior |
| --- | --- |
| Grant permissions | Request missing runtime permissions inline. |
| Open Bluetooth settings | Launch Android Bluetooth settings when Bluetooth is off or profile connection cannot be completed in app. |
| Scan for device | Scan for `B02PTT-FF01`. |
| Pair device | Call `BluetoothDevice.createBond()` when the device is found but unpaired. |
| Connect serial | Open SPP/RFCOMM socket to the device. |
| Retry | Re-run discovery/readiness checks. |

Connection screen state handling:

| State | Required UI behavior |
| --- | --- |
| Missing permissions | Show missing permissions and a button to request them. |
| Bluetooth off | Show status and a button to open Bluetooth settings. |
| Device not paired and not found | Show `B02PTT-FF01 not found`; allow scan retry; show instruction to put the device in pairing mode. |
| Device found but not paired | Show Pair button; call `createBond()`. |
| Pairing failed | Show `Pair your device in Android Bluetooth settings`; provide Bluetooth settings button. |
| Device bonded but SPP disconnected | Show Connect button; attempt RFCOMM connection. |
| SPP failed | Show error and Retry button. |
| SPP connected but headset audio unavailable | Show `Headset audio not available`; provide Bluetooth settings button. |
| Ready | Navigate to monitor screen automatically. |

The app should first inspect bonded devices before scanning. If a bonded device with name `B02PTT-FF01` exists, skip scanning and attempt SPP connection.

## 9. Monitor Screen Requirements

The monitor screen displays live device input and echo controls.

Required UI elements:

| Element | Behavior |
| --- | --- |
| Hardware mode label | Shows `Active` or `Control`. |
| Button state table | Shows current state for PTT, SOS, Group, Volume Up, and Volume Down. |
| Echo enabled toggle | Enables/disables echo behavior for future PTT presses. Default off. |
| Echo timing toggle | Chooses when recording starts relative to the ready beep. Default `Record after ready beep`. |
| Audio route status | Shows `SCO inactive`, `SCO starting`, `SCO active`, `SCO failed`, or `SCO closing`. |
| Echo status | Shows `Idle`, `Waiting for audio`, `Beeping`, `Recording`, `Playback`, `Warm`, `Cancelled`, or `Error`. |

Button table rows:

| Row | States |
| --- | --- |
| PTT | `released`, `pressed` |
| SOS | `released`, `pressed`, `long-pressed` |
| Group | `released`, `pressed` |
| Volume Up | `idle`, `clicked` |
| Volume Down | `idle`, `clicked` |

Volume button click states expire automatically after 300 ms and return to `idle`.

Echo timing modes:

| Mode label | Behavior |
| --- | --- |
| `Record after ready beep` | Wait for SCO route, play 150 ms beep, then start recording if PTT is still pressed. |
| `Record while ready beep plays` | Wait for SCO route, start recording, play 150 ms beep while recording continues. |

The echo toggle applies only to future PTT presses. If the user disables echo while a recording is active, the active recording continues until PTT release or max duration.

## 10. Device Serial Protocol

The serial channel emits ASCII tokens. Tokens may arrive concatenated, split across reads, followed by `NUL` bytes, or preceded by noise. The parser must scan for known tokens rather than rely on line delimiters.

Known tokens:

| Token | Button | Raw state | Physical button number |
| --- | --- | --- | ---: |
| `+PTT=P` | PTT | Pressed | 1 |
| `+PTT=R` | PTT | Released | 1 |
| `C:SP*` | SOS | Pressed | 4 |
| `C:SR*` | SOS | Released | 4 |
| `C:SOS*` | SOS | Long-pressed | 4 |
| `C:GP*` | Group | Pressed | 6 |
| `C:GR*` | Group | Released | 6 |
| `C:VP*` | Volume Up | Clicked | 2 |
| `C:VM*` | Volume Down | Clicked | 3 |

Parser requirements:

| Requirement | Behavior |
| --- | --- |
| Concatenated tokens | `+PTT=P+PTT=R` emits two events. |
| Split tokens | `+PT` then `T=P` emits one event after the second read. |
| Optional NUL | `C:SP*\0` emits one SOS pressed event and ignores the NUL. |
| Noise | Unknown bytes before a known token are discarded. |
| Partial suffix | Bytes that may be the prefix of a future token are retained. |

Pseudocode:

```kotlin
class ButtonParser {
    private val buffer = ByteArrayDeque()

    fun push(bytes: ByteArray): List<RawButtonEvent> {
        buffer.addAll(bytes)
        val events = mutableListOf<RawButtonEvent>()

        while (buffer.isNotEmpty()) {
            val match = matchKnownTokenAtStart(buffer)
            if (match != null) {
                buffer.removeFirst(match.token.length)
                if (buffer.firstOrNull() == 0.toByte()) buffer.removeFirst()
                events += match.event
                continue
            }

            if (bufferIsPrefixOfKnownToken(buffer)) break

            val nextKnownTokenIndex = findNextKnownTokenStart(buffer)
            if (nextKnownTokenIndex != null) {
                buffer.removeFirst(nextKnownTokenIndex)
                continue
            }

            val keep = longestSuffixThatIsKnownTokenPrefix(buffer)
            buffer.dropAllExceptLast(keep)
            break
        }

        return events
    }
}
```

## 11. Hardware Mode Model

The physical device has two operating modes. The application must model them for display and future behavior.

| Mode | LED behavior | Serial behavior |
| --- | --- | --- |
| Active | Blinking green | PTT, Group, and SOS emit serial tokens. Volume buttons control device-local hardware volume and do not emit serial tokens. |
| Control | Solid green | Group, PTT, SOS, Volume Up, and Volume Down emit serial tokens. Volume buttons no longer control hardware volume. |

Mode transitions:

| Current mode | Event | New mode | UI/event behavior |
| --- | --- | --- | --- |
| Active | Group pressed, `C:GP*` | Control | Update hardware mode to `Control`. |
| Control | Group pressed, `C:GP*` | Control | Remain in `Control`. |
| Control | PTT pressed, `+PTT=P` | Active | Update hardware mode to `Active`. |
| Active | PTT pressed/released | Active | Remain in `Active`. |
| Any | SOS event | Same mode | Display raw SOS state. |

The scaffold does not need to implement menu selection actions. It only displays the current hardware mode and raw/current button states.

Pseudocode:

```kotlin
enum class HardwareMode { Active, Control }

class HardwareModeTracker {
    var mode: HardwareMode = HardwareMode.Active
        private set

    fun apply(event: RawButtonEvent): DisplayUpdate {
        when (event) {
            RawButtonEvent.GroupPressed -> mode = HardwareMode.Control
            RawButtonEvent.PttPressed -> if (mode == HardwareMode.Control) mode = HardwareMode.Active
            else -> Unit
        }
        return updateButtonState(event, mode)
    }
}
```

## 12. Bluetooth SPP Connection

The serial control channel uses Bluetooth Classic RFCOMM with the standard SPP UUID.

Connection flow:

| Step | Behavior |
| --- | --- |
| 1 | Ensure `BLUETOOTH_CONNECT` permission is granted. |
| 2 | Get `BluetoothManager.adapter`. |
| 3 | Check that Bluetooth is enabled. |
| 4 | Search bonded devices for name `B02PTT-FF01`. |
| 5 | If not bonded, scan for `B02PTT-FF01`. |
| 6 | If found unbonded, call `createBond()` and wait for bond state. |
| 7 | Cancel discovery before connecting. |
| 8 | Call `createRfcommSocketToServiceRecord(SPP_UUID)`. |
| 9 | Call `socket.connect()` on an IO dispatcher/thread. |
| 10 | Once connected, continuously read `socket.inputStream`. |
| 11 | Feed bytes into `ButtonParser`. |
| 12 | Publish parsed events to UI and echo state machine. |

Kotlin-oriented pseudocode:

```kotlin
class SppClient(
    private val adapter: BluetoothAdapter,
    private val parser: ButtonParser,
    private val scope: CoroutineScope,
) {
    private var socket: BluetoothSocket? = null

    suspend fun connect(device: BluetoothDevice): Flow<RawButtonEvent> = channelFlow {
        withContext(Dispatchers.IO) {
            adapter.cancelDiscovery()
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
            val s = device.createRfcommSocketToServiceRecord(uuid)
            s.connect()
            socket = s

            val input = s.inputStream
            val buffer = ByteArray(256)
            while (isActive) {
                val n = input.read(buffer)
                if (n < 0) break
                val chunk = buffer.copyOf(n)
                for (event in parser.push(chunk)) {
                    send(event)
                }
            }
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }
}
```

SPP disconnect handling:

| Condition | Required behavior |
| --- | --- |
| `read()` returns EOF | Close socket and return to connection screen. |
| `IOException` during read | Close socket, show disconnected/failed state, and return to connection screen. |
| Bluetooth disabled | Close socket and return to connection screen. |
| App shutdown | Close socket. |

## 13. Headset Audio Route

The audio path uses Android communication audio routing. The target device must appear as a Bluetooth SCO communication device.

Required API model:

| API | Purpose |
| --- | --- |
| `AudioManager.mode = MODE_IN_COMMUNICATION` | Puts audio stack into communication mode. |
| `AudioManager.availableCommunicationDevices` | Lists selectable communication devices. |
| `AudioManager.setCommunicationDevice(device)` | Selects the Bluetooth SCO headset route. |
| `AudioManager.communicationDevice` | Confirms current selected communication route. |
| `AudioManager.clearCommunicationDevice()` | Releases forced communication route. |
| `AudioDeviceCallback` or communication-device listener | Tracks route availability/loss. |

Route availability check:

```kotlin
fun findScoDevice(audioManager: AudioManager): AudioDeviceInfo? {
    return audioManager.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
        it.productName?.contains("B02PTT-FF01", ignoreCase = true) == true
    } ?: audioManager.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
}
```

Route acquisition pseudocode:

```kotlin
class ScoAudioController(private val audioManager: AudioManager) {
    var state: ScoState = ScoState.Inactive
        private set

    suspend fun acquire(): Boolean {
        if (state == ScoState.Active) return true

        state = ScoState.Starting
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val device = findScoDevice(audioManager) ?: run {
            state = ScoState.Failed("Bluetooth SCO headset not available")
            return false
        }

        val accepted = audioManager.setCommunicationDevice(device)
        if (!accepted) {
            state = ScoState.Failed("Android rejected Bluetooth SCO route")
            return false
        }

        val active = waitUntil(timeoutMs = 5_000) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        state = if (active) ScoState.Active else ScoState.Failed("Timed out waiting for SCO route")
        return active
    }

    fun release() {
        state = ScoState.Closing
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        state = ScoState.Inactive
    }
}
```

Important constraints:

| Constraint | Required behavior |
| --- | --- |
| HFP profile is system-managed | If the route is unavailable, show Bluetooth settings instead of using hidden APIs. |
| SCO startup has latency | Do not start user-facing recording until the route is selected and the ready beep policy is satisfied. |
| SCO is telephony-grade | Expect mono audio and lower fidelity than media/A2DP. |
| Route can disappear | Stop echo, clear recording, and return to connection screen or show audio unavailable. |
| No fallback to phone speaker | Echo playback must use headset SCO; if unavailable, report an error. |

## 14. Beep Requirements

The ready beep confirms that the Bluetooth headset audio path is ready.

| Property | Requirement |
| --- | --- |
| Waveform | Sine wave |
| Frequency | 880 Hz |
| Duration | 150 ms |
| Volume | Moderate; do not clip |
| Route | Bluetooth SCO communication route |
| API | `AudioTrack` preferred; `ToneGenerator` acceptable for prototype |
| Usage | `AudioAttributes.USAGE_VOICE_COMMUNICATION` |
| Content type | `AudioAttributes.CONTENT_TYPE_SONIFICATION` |

The beep must be played after SCO route activation succeeds.

Pseudocode:

```kotlin
suspend fun playReadyBeep() {
    val sampleRate = 16_000
    val samples = generateSinePcm16(
        frequencyHz = 880.0,
        durationMs = 150,
        sampleRate = sampleRate,
        amplitude = 0.35,
    )

    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        )
        .setBufferSizeInBytes(samples.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    track.write(samples, 0, samples.size)
    track.play()
    delay(150)
    track.release()
}
```

## 15. Recording Requirements

Recording is in-memory only.

| Property | Requirement |
| --- | --- |
| API | `AudioRecord` |
| Audio source | `MediaRecorder.AudioSource.VOICE_COMMUNICATION` |
| Encoding | PCM 16-bit |
| Channels | Mono |
| Preferred sample rate | 16 kHz |
| Fallback sample rate | 8 kHz |
| Max duration | 60 seconds |
| Storage | Memory buffer only |
| File writes | None |

Recording start must fail if SCO is not active. The app must not silently record from the phone microphone.

Pseudocode:

```kotlin
class InMemoryRecorder {
    private var record: AudioRecord? = null
    private val pcm = mutableListOf<Short>()

    suspend fun start(): Boolean {
        val format = AudioFormat.Builder()
            .setSampleRate(16_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()

        pcm.clear()
        audioRecord.startRecording()
        record = audioRecord

        launchReadLoop(audioRecord, pcm, maxDurationMs = 60_000)
        return true
    }

    fun stop(): ShortArray {
        record?.stop()
        record?.release()
        record = null
        return pcm.toShortArray()
    }
}
```

## 16. Echo State Machine

Echo is triggered only by raw PTT events from the serial channel.

Echo states:

| State | Meaning |
| --- | --- |
| `Idle` | Echo is off or waiting for PTT. SCO may be inactive. |
| `WaitingForAudio` | PTT is down and the app is acquiring the SCO route. |
| `Beeping` | SCO is active and the ready beep is playing. |
| `Recording` | Microphone audio is being captured. |
| `MaxDurationReached` | Recording stopped at 60 seconds; playback waits for PTT release. |
| `Playback` | Captured audio is playing back through SCO. |
| `Warm` | SCO remains active for quick reuse. |
| `Cancelled` | PTT was released before recording could start. |
| `Error` | SCO, recording, or playback failed. |

SCO lifecycle:

| Event | Behavior |
| --- | --- |
| Echo-enabled PTT press while SCO inactive | Acquire SCO route. |
| Echo-enabled PTT press while SCO warm | Reuse current SCO route. |
| PTT release after recording | Stop recording and play back captured audio. |
| Playback finish | Start 30 second SCO keep-warm timer. |
| New PTT during keep-warm timer | Cancel close timer and reuse SCO. |
| Keep-warm timer expires | Release SCO route. |
| Route lost | Cancel echo and report audio unavailable. |

PTT release before readiness:

| Timing | Behavior |
| --- | --- |
| Released while acquiring SCO | Cancel echo attempt and record/play nothing. |
| Released while waiting to start beep | Cancel echo attempt and record/play nothing. |
| Released during beep in `Record after ready beep` mode | Cancel echo attempt and record/play nothing. |
| Released during beep in `Record while ready beep plays` mode | Stop recording and play captured audio if non-empty. |

Echo timing mode details:

| Mode | Sequence |
| --- | --- |
| `Record after ready beep` | PTT press, acquire SCO, beep, if PTT still down start recording, PTT release, stop recording, play back. |
| `Record while ready beep plays` | PTT press, acquire SCO, start recording, beep while recording, PTT release, stop recording, play back. |

Main echo pseudocode:

```kotlin
class EchoController(
    private val sco: ScoAudioController,
    private val recorder: InMemoryRecorder,
    private val player: PcmPlayer,
) {
    var enabled: Boolean = false
    var timingMode: EchoTimingMode = EchoTimingMode.RecordAfterBeep
    private var pttDown: Boolean = false
    private var activeSession: EchoSession? = null
    private var closeScoJob: Job? = null

    suspend fun onPttPressed() {
        pttDown = true
        if (!enabled) return

        closeScoJob?.cancel()
        activeSession = EchoSession()

        setStatus(EchoStatus.WaitingForAudio)
        if (!sco.acquire()) {
            setStatus(EchoStatus.Error("SCO unavailable"))
            return
        }

        if (!pttDown) {
            cancelBeforeRecording()
            return
        }

        when (timingMode) {
            EchoTimingMode.RecordAfterBeep -> {
                setStatus(EchoStatus.Beeping)
                playReadyBeep()
                if (!pttDown) {
                    cancelBeforeRecording()
                    return
                }
                setStatus(EchoStatus.Recording)
                recorder.start()
            }

            EchoTimingMode.RecordWhileBeepPlays -> {
                setStatus(EchoStatus.Recording)
                recorder.start()
                setStatus(EchoStatus.Beeping)
                playReadyBeep()
                if (pttDown) setStatus(EchoStatus.Recording)
            }
        }

        scheduleMaxDurationStop(60_000)
    }

    suspend fun onPttReleased() {
        pttDown = false
        if (!enabled && activeSession == null) return

        val pcm = recorder.stopIfActiveOrEmpty()
        if (pcm.isEmpty()) {
            cancelBeforeRecording()
            return
        }

        setStatus(EchoStatus.Playback)
        player.playPcm16OverSco(pcm)
        setStatus(EchoStatus.Warm)
        closeScoJob = launchAfter(30_000) {
            sco.release()
            setStatus(EchoStatus.Idle)
        }
        activeSession = null
    }
}
```

Max duration behavior:

| Condition | Behavior |
| --- | --- |
| Recording reaches 60 seconds while PTT remains pressed | Stop recording, keep captured audio in memory, show `MaxDurationReached`, and wait for PTT release. |
| PTT releases after max duration | Play the captured audio. |
| PTT is still held after max duration | Do not resume recording until a new PTT press after release. |

## 17. Playback Requirements

Playback must use the Bluetooth SCO route. There is no fallback to phone speaker.

| Requirement | Behavior |
| --- | --- |
| SCO active check | Before playback, confirm `communicationDevice.type == TYPE_BLUETOOTH_SCO`. |
| Missing SCO route | Report echo error and do not play. |
| Playback API | `AudioTrack`. |
| Usage | `AudioAttributes.USAGE_VOICE_COMMUNICATION`. |
| Content type | `AudioAttributes.CONTENT_TYPE_SPEECH`. |
| Data | PCM 16-bit mono from in-memory recording. |

Pseudocode:

```kotlin
suspend fun playPcm16OverSco(pcm: ShortArray, sampleRate: Int) {
    require(audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        )
        .setBufferSizeInBytes(pcm.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    track.write(pcm, 0, pcm.size)
    track.play()
    waitForPlaybackCompletion(track)
    track.release()
}
```

## 18. UI State Model

Core model types:

```kotlin
data class AppState(
    val connection: ConnectionState,
    val monitor: MonitorState,
)

data class ConnectionState(
    val permissions: PermissionState,
    val bluetoothEnabled: Boolean,
    val devicePresence: DevicePresence,
    val spp: SppState,
    val headsetAudio: HeadsetAudioState,
)

data class MonitorState(
    val hardwareMode: HardwareMode,
    val buttons: ButtonStates,
    val echoEnabled: Boolean,
    val echoTimingMode: EchoTimingMode,
    val scoState: ScoState,
    val echoStatus: EchoStatus,
)

data class ButtonStates(
    val ptt: TwoStateButton,
    val sos: SosButtonState,
    val group: TwoStateButton,
    val volumeUp: ClickButtonState,
    val volumeDown: ClickButtonState,
)
```

Suggested enums:

```kotlin
enum class HardwareMode { Active, Control }
enum class TwoStateButton { Released, Pressed }
enum class SosButtonState { Released, Pressed, LongPressed }
enum class ClickButtonState { Idle, Clicked }
enum class EchoTimingMode { RecordAfterBeep, RecordWhileBeepPlays }
enum class SppState { Disconnected, Connecting, Connected, Failed }
enum class ScoState { Inactive, Starting, Active, Closing, Failed }
```

Raw serial event model:

```kotlin
sealed interface RawButtonEvent {
    data object PttPressed : RawButtonEvent
    data object PttReleased : RawButtonEvent
    data object SosPressed : RawButtonEvent
    data object SosReleased : RawButtonEvent
    data object SosLongPressed : RawButtonEvent
    data object GroupPressed : RawButtonEvent
    data object GroupReleased : RawButtonEvent
    data object VolumeUpClicked : RawButtonEvent
    data object VolumeDownClicked : RawButtonEvent
}
```

## 19. Event Processing Pipeline

Every serial byte chunk follows the same path:

```text
BluetoothSocket.inputStream
  -> SppClient read loop
  -> ButtonParser.push(bytes)
  -> RawButtonEvent list
  -> HardwareModeTracker.apply(event)
  -> MonitorState update
  -> EchoController event hook for PTT only
```

Raw button events always update the table, regardless of echo toggle state.

PTT events additionally drive echo when enabled:

| Event | Echo hook |
| --- | --- |
| `PttPressed` | `EchoController.onPttPressed()` |
| `PttReleased` | `EchoController.onPttReleased()` |
| Other events | No echo action |

## 20. Failure Handling

| Failure | Required behavior |
| --- | --- |
| Permission denied | Stay on connection screen and show missing permission. |
| Bluetooth disabled | Stay on connection screen and show Bluetooth off. |
| Device scan does not find target | Show not found and retry action. |
| Pairing fails | Show pairing failure and Bluetooth settings action. |
| SPP connect fails | Show failure and retry action. |
| SPP disconnects on monitor screen | Stop echo, release SCO, return to connection screen. |
| Headset audio unavailable | Stay or return to connection screen; show Bluetooth settings action. |
| SCO acquisition times out | Keep monitor screen if SPP is connected; show echo error and audio status failed. |
| SCO route lost during recording | Stop recording, discard buffer, show error. |
| SCO route lost during playback | Stop playback, show error. |
| PTT released before recording starts | Cancel echo attempt; play nothing. |
| Recording max duration reached | Stop recording and wait for PTT release. |

## 21. Service and Lifecycle Behavior

Recommended lifecycle ownership:

| Resource | Owner | Lifetime |
| --- | --- | --- |
| Bluetooth socket | `PttForegroundService` | While monitor readiness is desired. |
| Serial read coroutine | `PttForegroundService` | While SPP socket is connected. |
| Audio route | `ScoAudioController` | From echo PTT acquisition until 30 seconds after playback. |
| Recorder | `EchoController` | Only during active echo recording. |
| Playback track | `EchoController` | Only during beep/playback. |

On app process shutdown:

| Resource | Cleanup |
| --- | --- |
| SPP socket | Close. |
| AudioRecord | Stop and release. |
| AudioTrack | Stop and release. |
| SCO route | `clearCommunicationDevice()`, then set `mode = MODE_NORMAL`. |
| Foreground service | Stop foreground notification. |

If the app does not implement a foreground service in the first prototype, the same cleanup rules apply to the activity/view-model lifecycle.

## 22. Testing Requirements

Unit tests:

| Area | Cases |
| --- | --- |
| Parser | Single token, concatenated tokens, split tokens, NUL suffix, noise prefix, partial suffix retention. |
| Hardware mode | Active to Control on Group pressed, Control to Active on PTT pressed, volume click display timeout. |
| Button state | PTT press/release, Group press/release, SOS pressed/long/released, volume click expiry. |
| Echo state machine | PTT release before SCO ready, default mode recording after beep, alternate mode recording during beep, 30 second SCO keep-warm, max duration. |
| Connection readiness | Ready only when permissions, Bluetooth, bond, SPP, and SCO availability are all valid. |

Manual device tests:

| Test | Expected result |
| --- | --- |
| Fresh install with Bluetooth off | Connection screen shows Bluetooth off and settings action. |
| Device unpaired | App can scan, find `B02PTT-FF01`, and initiate pairing or direct user to settings. |
| Device paired | App connects SPP and enters monitor screen when headset audio is available. |
| PTT press/release | Table shows PTT pressed/released. |
| SOS short | Table shows SOS pressed then released. |
| SOS long | Table shows SOS pressed, long-pressed, then released. |
| Group press in Active | Hardware mode changes to Control. |
| PTT press in Control | Hardware mode changes to Active. |
| Volume in Control | Volume row shows clicked for 300 ms then idle. |
| Echo default mode | PTT press activates SCO, beep plays, recording starts after beep, release plays audio through headset. |
| Echo alternate mode | PTT press activates SCO, recording starts before beep, release plays audio through headset. |
| Echo reuse | Second PTT within 30 seconds reuses SCO without full setup delay. |
| Echo timeout | SCO releases 30 seconds after playback finishes. |

## 23. Acceptance Criteria

The scaffold is complete when all criteria below are satisfied:

| Criterion | Required result |
| --- | --- |
| Two-view app | App has a connection screen and a monitor screen only. |
| Device detection | App identifies `B02PTT-FF01` by name and handles unpaired/paired states. |
| Pairing assistance | App attempts pairing with `createBond()` when possible and provides Bluetooth settings fallback. |
| SPP connection | App opens RFCOMM socket using UUID `00001101-0000-1000-8000-00805f9b34fb`. |
| Continuous read | App continuously reads serial bytes while connected. |
| Parser | App handles split, concatenated, NUL-terminated, and noisy serial tokens. |
| Button table | Monitor screen displays current state for PTT, SOS, Group, Volume Up, and Volume Down. |
| Hardware mode | Monitor screen displays Active/Control and updates according to Group/PTT behavior. |
| Headset audio readiness | App verifies Bluetooth SCO communication route availability before entering monitor screen. |
| Echo toggle | Echo is disabled by default and only runs when enabled. |
| Ready beep | Echo plays a 150 ms 880 Hz beep over SCO when audio is ready. |
| Recording | Echo records headset mic audio in memory over SCO. |
| Playback | Echo plays recorded audio back over the headset speaker over SCO. |
| Echo modes | User can choose recording after beep or recording while beep plays. |
| SCO keep-warm | SCO remains active until 30 seconds after playback finishes, then releases. |
| No phone-speaker fallback | If SCO is unavailable, echo fails visibly instead of playing through the phone. |
| No persistence | Recorded echo audio is not written to disk. |
| Cleanup | SPP, recorder, playback, and SCO route are released on disconnect or shutdown. |

## 24. Implementation Notes for Android Developers

Android cannot be treated as a Linux Bluetooth host.

The serial channel and audio channel are separate. Use `BluetoothSocket` for button tokens and `AudioManager` communication routing for headset audio.

Do not expect the SPP connection to imply HFP readiness. Do not expect the HFP route to imply SPP readiness. The connection screen must validate both.

Do not use hidden Bluetooth profile APIs to force headset profile connection. If Android does not expose the headset as an available communication device, show instructions and open Bluetooth settings.

Do not use media/A2DP APIs for this scaffold. The expected audio path is HFP/SCO because microphone input and speaker output are both required.

Do not start recording immediately on PTT before SCO is ready. The ready beep exists to tell the user when the headset audio route can carry speech.

Do not fall back to the phone microphone or phone speaker. Such fallback would make the prototype appear functional while failing to prove the required Bluetooth behavior.
