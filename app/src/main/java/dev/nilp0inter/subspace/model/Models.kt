package dev.nilp0inter.subspace.model

import java.util.UUID

const val TARGET_DEVICE_NAME = "B02PTT-FF01"
val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

data class AppState(
    val connection: ConnectionState = ConnectionState(),
    val monitor: MonitorState = MonitorState(),
) {
    val readyForMonitor: Boolean
        get() = connection.readyForMonitor
}

data class ConnectionState(
    val permissions: PermissionState = PermissionState.Missing,
    val missingPermissions: List<String> = emptyList(),
    val bluetoothEnabled: Boolean = false,
    val devicePresence: DevicePresence = DevicePresence.NotFound,
    val spp: SppState = SppState.Disconnected,
    val sppError: String? = null,
    val headsetAudio: HeadsetAudioState = HeadsetAudioState.Unavailable,
) {
    val readyForMonitor: Boolean
        get() = permissions == PermissionState.Granted &&
            bluetoothEnabled &&
            devicePresence == DevicePresence.Bonded &&
            spp == SppState.Connected &&
            headsetAudio == HeadsetAudioState.Available
}

enum class PermissionState { Missing, Granted }
enum class DevicePresence { NotFound, Scanning, Found, Pairing, Bonded, PairingFailed }
enum class SppState { Disconnected, Connecting, Connected, Failed }
enum class HeadsetAudioState { Unavailable, Available }

data class MonitorState(
    val hardwareMode: HardwareMode = HardwareMode.Active,
    val buttons: ButtonStates = ButtonStates(),
    val echoEnabled: Boolean = false,
    val echoTimingMode: EchoTimingMode = EchoTimingMode.RecordAfterBeep,
    val scoState: ScoState = ScoState.Inactive,
    val echoStatus: EchoStatus = EchoStatus.Idle,
)

data class ButtonStates(
    val ptt: TwoStateButton = TwoStateButton.Released,
    val sos: SosButtonState = SosButtonState.Released,
    val group: TwoStateButton = TwoStateButton.Released,
    val volumeUp: ClickButtonState = ClickButtonState.Idle,
    val volumeDown: ClickButtonState = ClickButtonState.Idle,
)

enum class HardwareMode { Active, Control }
enum class TwoStateButton { Released, Pressed }
enum class SosButtonState { Released, Pressed, LongPressed }
enum class ClickButtonState { Idle, Clicked }
enum class EchoTimingMode { RecordAfterBeep, RecordWhileBeepPlays }

sealed interface ScoState {
    data object Inactive : ScoState
    data object Starting : ScoState
    data object Active : ScoState
    data object Closing : ScoState
    data class Failed(val reason: String) : ScoState
}

sealed interface EchoStatus {
    data object Idle : EchoStatus
    data object WaitingForAudio : EchoStatus
    data object Beeping : EchoStatus
    data object Recording : EchoStatus
    data object MaxDurationReached : EchoStatus
    data object Playback : EchoStatus
    data object Warm : EchoStatus
    data object Cancelled : EchoStatus
    data class Error(val reason: String) : EchoStatus
}

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

fun ScoState.displayText(): String = when (this) {
    ScoState.Inactive -> "SCO inactive"
    ScoState.Starting -> "SCO starting"
    ScoState.Active -> "SCO active"
    ScoState.Closing -> "SCO closing"
    is ScoState.Failed -> "SCO failed: $reason"
}

fun EchoStatus.displayText(): String = when (this) {
    EchoStatus.Idle -> "Idle"
    EchoStatus.WaitingForAudio -> "Waiting for audio"
    EchoStatus.Beeping -> "Beeping"
    EchoStatus.Recording -> "Recording"
    EchoStatus.MaxDurationReached -> "Max duration reached"
    EchoStatus.Playback -> "Playback"
    EchoStatus.Warm -> "Warm"
    EchoStatus.Cancelled -> "Cancelled"
    is EchoStatus.Error -> "Error: $reason"
}
