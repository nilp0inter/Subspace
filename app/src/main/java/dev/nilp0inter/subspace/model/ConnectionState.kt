package dev.nilp0inter.subspace.model

data class ConnectionState(
    val permissions: PermissionState = PermissionState.Missing,
    val missingPermissions: List<String> = emptyList(),
    val bluetoothEnabled: Boolean = false,
    val devicePresence: DevicePresence = DevicePresence.NotFound,
    val spp: SppState = SppState.Disconnected,
    val sppError: String? = null,
    val headsetAudio: HeadsetAudioState = HeadsetAudioState.Unavailable,
    val keyboardConnectionState: KeyboardConnectionState = KeyboardConnectionState.Disconnected,
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
enum class KeyboardConnectionState { Disconnected, Scanning, Connecting, Connected }
enum class HeadsetAudioState { Unavailable, Available }
