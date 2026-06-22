package dev.nilp0inter.subspace.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class ScoAudioController(
    private val audioManager: AudioManager,
) : ScoRoute {
    private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
    override val state: StateFlow<ScoState> = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    override fun hasAvailableScoDevice(): Boolean = findScoDevice() != null

    @SuppressLint("MissingPermission")
    override suspend fun acquire(): Boolean {
        if (isActive()) {
            _state.value = ScoState.Active
            return true
        }

        _state.value = ScoState.Starting
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val device = findScoDevice()
        if (device == null) {
            _state.value = ScoState.Failed("Bluetooth SCO headset not available")
            return false
        }

        val accepted = audioManager.setCommunicationDevice(device)
        if (!accepted) {
            _state.value = ScoState.Failed("Android rejected Bluetooth SCO route")
            return false
        }

        val active = withTimeoutOrNull(5_000) {
            while (!isActive()) delay(50)
            true
        } == true

        _state.value = if (active) {
            ScoState.Active
        } else {
            ScoState.Failed("Timed out waiting for SCO route")
        }
        return active
    }

    override fun isActive(): Boolean =
        audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    override fun release() {
        _state.value = ScoState.Closing
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        _state.value = ScoState.Inactive
    }

    @SuppressLint("MissingPermission")
    private fun findScoDevice(): AudioDeviceInfo? {
        val devices = audioManager.availableCommunicationDevices
        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                it.productName?.contains(TARGET_DEVICE_NAME, ignoreCase = true) == true
        } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }
}
