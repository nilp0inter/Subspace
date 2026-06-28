package dev.nilp0inter.subspace.model

import java.util.UUID

const val TARGET_DEVICE_NAME = "B02PTT-FF01"
val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

data class AppState(
    val connection: ConnectionState = ConnectionState(),
    val monitor: MonitorState = MonitorState(),
    val journal: JournalChannel = JournalChannel(),
    val debugChannel: DebugChannel = DebugChannel(),
    val activeChannelId: String = JournalChannel.ID,
    val inputMode: InputMode = InputMode.OnAPinch,
    val inputModeAvailability: InputModeAvailability = InputModeAvailability(),
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
    val scoState: ScoState = ScoState.Inactive,
    val echoStatus: EchoStatus = EchoStatus.Idle,
    val sttEnabled: Boolean = false,
    val sttModelStatus: SttModelStatus = SttModelStatus.Idle,
    val sttStatus: SttStatus = SttStatus.Idle,
    val sttTranscript: String = "",
    val ttsEnabled: Boolean = false,
    val ttsModelStatus: TtsModelStatus = TtsModelStatus.Idle,
    val ttsStatus: TtsStatus = TtsStatus.Idle,
    val ttsText: String = DEFAULT_TTS_TEXT,
    val ttsVoiceStyle: String = DEFAULT_TTS_VOICE_STYLE,
    val ttsLang: String = DEFAULT_TTS_LANG,
    val ttsTotalSteps: Int = DEFAULT_TTS_TOTAL_STEPS,
    val ttsSpeed: Float = DEFAULT_TTS_SPEED,
    val sttTtsEnabled: Boolean = false,
    val sttTtsStatus: SttTtsStatus = SttTtsStatus.Idle,
    val sttTtsTranscript: String = "",
    val sttTtsVoiceStyle: String = DEFAULT_TTS_VOICE_STYLE,
    val sttTtsLang: String = DEFAULT_TTS_LANG,
    val sttTtsTotalSteps: Int = DEFAULT_TTS_TOTAL_STEPS,
    val sttTtsSpeed: Float = DEFAULT_TTS_SPEED,
)

data class ButtonStates(
    val ptt: TwoStateButton = TwoStateButton.Released,
    val sos: SosButtonState = SosButtonState.Released,
    val group: TwoStateButton = TwoStateButton.Released,
    val volumeUp: ClickButtonState = ClickButtonState.Idle,
    val volumeDown: ClickButtonState = ClickButtonState.Idle,
)

enum class HardwareMode { Active, Control }
enum class PttSource { Rsm, Phone, CarTelecom }
enum class InputMode { Work, OnTheRoad, OnAPinch }

data class InputModeAvailability(
    val work: Boolean = false,
    val onTheRoad: Boolean = false,
    val onAPinch: Boolean = true,
) {
    fun isAvailable(mode: InputMode): Boolean = when (mode) {
        InputMode.Work -> work
        InputMode.OnTheRoad -> onTheRoad
        InputMode.OnAPinch -> onAPinch
    }
}
enum class TwoStateButton { Released, Pressed }
enum class SosButtonState { Released, Pressed, LongPressed }
enum class ClickButtonState { Idle, Clicked }
enum class EchoTimingMode { RecordAfterBeep }

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

enum class SttModelStatus { Idle, Loading, Ready, Failed }

sealed interface SttStatus {
    data object Idle : SttStatus
    data object WaitingForAudio : SttStatus
    data object Beeping : SttStatus
    data object Recording : SttStatus
    data object MaxDurationReached : SttStatus
    data object Transcribing : SttStatus
    data class Transcribed(val text: String) : SttStatus
    data object EmptyAudio : SttStatus
    data object Cancelled : SttStatus
    data class Error(val reason: String) : SttStatus
}

fun SttModelStatus.displayText(): String = when (this) {
    SttModelStatus.Idle -> "STT model idle"
    SttModelStatus.Loading -> "STT model loading"
    SttModelStatus.Ready -> "STT model ready"
    SttModelStatus.Failed -> "STT model failed"
}

fun SttStatus.displayText(): String = when (this) {
    SttStatus.Idle -> "Idle"
    SttStatus.WaitingForAudio -> "Waiting for audio"
    SttStatus.Beeping -> "Beeping"
    SttStatus.Recording -> "Recording"
    SttStatus.MaxDurationReached -> "Max duration reached"
    SttStatus.Transcribing -> "Transcribing"
    is SttStatus.Transcribed -> text
    SttStatus.EmptyAudio -> "Empty audio"
    SttStatus.Cancelled -> "Cancelled"
    is SttStatus.Error -> "Error: $reason"
}

// ---------------------------------------------------------------------------
// TTS state
// ---------------------------------------------------------------------------

const val DEFAULT_TTS_TEXT = "This is a Subspace TTS test."
const val DEFAULT_TTS_VOICE_STYLE = "M1"
const val DEFAULT_TTS_LANG = "en"
const val DEFAULT_TTS_TOTAL_STEPS = 8
const val DEFAULT_TTS_SPEED = 1.05f
val TTS_VOICE_STYLES = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
val TTS_LANGS = listOf(
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
    "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
)

enum class TtsModelStatus { Idle, Loading, Ready, Failed }

sealed interface TtsStatus {
    data object Idle : TtsStatus
    data object WaitingForModel : TtsStatus
    data object Synthesizing : TtsStatus
    data object Playing : TtsStatus
    data object EmptyText : TtsStatus
    data object Cancelled : TtsStatus
    data class Error(val reason: String) : TtsStatus
}

sealed interface SttTtsStatus {
    data object Idle : SttTtsStatus
    data object WaitingForAudio : SttTtsStatus
    data object Beeping : SttTtsStatus
    data object Recording : SttTtsStatus
    data object MaxDurationReached : SttTtsStatus
    data object Transcribing : SttTtsStatus
    data object WaitingForSttModel : SttTtsStatus
    data object WaitingForTtsModel : SttTtsStatus
    data object Synthesizing : SttTtsStatus
    data object Playing : SttTtsStatus
    data class Transcript(val text: String) : SttTtsStatus
    data object EmptyAudio : SttTtsStatus
    data object EmptyTranscript : SttTtsStatus
    data object Cancelled : SttTtsStatus
    data class Error(val reason: String) : SttTtsStatus
}

fun TtsModelStatus.displayText(): String = when (this) {
    TtsModelStatus.Idle -> "TTS model idle"
    TtsModelStatus.Loading -> "TTS model loading"
    TtsModelStatus.Ready -> "TTS model ready"
    TtsModelStatus.Failed -> "TTS model failed"
}

fun TtsStatus.displayText(): String = when (this) {
    TtsStatus.Idle -> "Idle"
    TtsStatus.WaitingForModel -> "Waiting for model"
    TtsStatus.Synthesizing -> "Synthesizing"
    TtsStatus.Playing -> "Playing"
    TtsStatus.EmptyText -> "Empty text"
    TtsStatus.Cancelled -> "Cancelled"
    is TtsStatus.Error -> "Error: $reason"
}

fun SttTtsStatus.displayText(): String = when (this) {
    SttTtsStatus.Idle -> "Idle"
    SttTtsStatus.WaitingForAudio -> "Waiting for audio"
    SttTtsStatus.Beeping -> "Beeping"
    SttTtsStatus.Recording -> "Recording"
    SttTtsStatus.MaxDurationReached -> "Max duration reached"
    SttTtsStatus.Transcribing -> "Transcribing"
    SttTtsStatus.WaitingForSttModel -> "Waiting for STT model"
    SttTtsStatus.WaitingForTtsModel -> "Waiting for TTS model"
    SttTtsStatus.Synthesizing -> "Synthesizing"
    SttTtsStatus.Playing -> "Playing"
    is SttTtsStatus.Transcript -> text
    SttTtsStatus.EmptyAudio -> "Empty audio"
    SttTtsStatus.EmptyTranscript -> "Empty transcript"
    SttTtsStatus.Cancelled -> "Cancelled"
    is SttTtsStatus.Error -> "Error: $reason"
}
