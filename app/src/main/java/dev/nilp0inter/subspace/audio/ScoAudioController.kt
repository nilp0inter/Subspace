package dev.nilp0inter.subspace.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ScoAudioController(
    private val scope: CoroutineScope,
    private val audioManager: AudioManager,
) : ScoRoute {
    private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
    override val state: StateFlow<ScoState> = _state.asStateFlow()
    override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Rsm
    private var _coldStart: Boolean = false
    override val coldStart: Boolean get() = _coldStart

    private val mutex = Mutex()
    private var activeClients = 0
    private var keepWarmJob: Job? = null
    private var selectedDeviceId: Int? = null

    @SuppressLint("MissingPermission")
    override fun hasAvailableScoDevice(): Boolean = findScoDevice() != null

    @SuppressLint("MissingPermission")
    override suspend fun acquire(): Boolean = mutex.withLock {
        keepWarmJob?.cancel()
        keepWarmJob = null

        if (isActive()) {
            activeClients++
            _state.value = ScoState.Active
            _coldStart = false
            return true
        }

        _coldStart = true
        _state.value = ScoState.Starting

        val device = findScoDevice()
        if (device == null) {
            _coldStart = false
            _state.value = ScoState.Failed("Bluetooth SCO headset not available")
            return false
        }

        activeClients++
        selectedDeviceId = device.id
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val accepted = audioManager.setCommunicationDevice(device)
        if (!accepted) {
            failAcquisition()
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
            failAcquisition()
            ScoState.Failed("Timed out waiting for SCO route")
        }
        return active
    }

    @SuppressLint("MissingPermission")
    fun selectedCommunicationDevice(): AudioDeviceInfo? {
        val selectedId = selectedDeviceId ?: return null
        val device = audioManager.communicationDevice ?: return null
        return device.takeIf { it.id == selectedId && it.isTargetRsmScoEndpoint() }
    }

    override fun isActive(): Boolean = selectedCommunicationDevice() != null

    override fun release() {
        scope.launch {
            mutex.withLock {
                if (activeClients > 0) {
                    activeClients--
                }
                
                if (activeClients == 0 && keepWarmJob == null) {
                    keepWarmJob = scope.launch {
                        delay(SCO_WARMUP_MS)
                        mutex.withLock {
                            if (activeClients == 0) {
                                _state.value = ScoState.Closing
                                selectedDeviceId = null
                                audioManager.clearCommunicationDevice()
                                audioManager.mode = AudioManager.MODE_NORMAL
                                _state.value = ScoState.Inactive
                                keepWarmJob = null
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun releaseImmediately() {
        mutex.withLock {
            activeClients = 0
            keepWarmJob?.cancel()
            keepWarmJob = null
            _state.value = ScoState.Closing
            selectedDeviceId = null
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            _state.value = ScoState.Inactive
        }
    }

    private fun failAcquisition() {
        if (activeClients > 0) activeClients--
        _coldStart = false
        selectedDeviceId = null
        if (activeClients == 0) {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    @SuppressLint("MissingPermission")
    private fun findScoDevice(): AudioDeviceInfo? {
        val devices = audioManager.availableCommunicationDevices
        return devices.firstOrNull { it.isTargetRsmScoEndpoint() }
    }

    companion object {
        private const val SCO_WARMUP_MS = 30_000L
    }
}
