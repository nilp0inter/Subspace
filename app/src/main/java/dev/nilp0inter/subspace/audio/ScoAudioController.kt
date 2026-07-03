package dev.nilp0inter.subspace.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
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
    private val rsmHfpConnected: () -> Boolean = { false },
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
    override fun hasAvailableScoDevice(): Boolean {
        val device = findScoDevice()
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_AVAILABLE result=${device != null} current=${audioManager.communicationDevice.routeDebugString()} " +
                "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",
        )
        return device != null
    }

    @SuppressLint("MissingPermission")
    override suspend fun acquire(): Boolean = mutex.withLock {
        keepWarmJob?.cancel()
        keepWarmJob = null

        Log.d(
            ROUTE_LOG_TAG,
            "SCO_ACQUIRE_BEGIN activeClients=$activeClients state=${_state.value} selectedId=$selectedDeviceId " +
                "current=${audioManager.communicationDevice.routeDebugString()}",
        )

        if (isActive()) {
            activeClients++
            _state.value = ScoState.Active
            _coldStart = false
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_ACQUIRE_END reused=true active=true activeClients=$activeClients selectedId=$selectedDeviceId " +
                    "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
            )
            return true
        }

        _coldStart = true
        _state.value = ScoState.Starting

        val device = findScoDevice()
        Log.d(ROUTE_LOG_TAG, "SCO_ACQUIRE_CANDIDATE device=${device.routeDebugString()}")
        if (device == null) {
            _coldStart = false
            _state.value = ScoState.Failed("Bluetooth SCO headset not available")
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_ACQUIRE_FAIL reason=no-device current=${audioManager.communicationDevice.routeDebugString()} " +
                    "state=${_state.value}",
            )
            return false
        }

        activeClients++
        selectedDeviceId = device.id
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val accepted = audioManager.setCommunicationDevice(device)
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_SET_COMM accepted=$accepted requested=${device.routeDebugString()} " +
                "current=${audioManager.communicationDevice.routeDebugString()} selectedId=$selectedDeviceId",
        )
        if (!accepted) {
            failAcquisition()
            _state.value = ScoState.Failed("Android rejected Bluetooth SCO route")
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_ACQUIRE_FAIL reason=android-rejected requested=${device.routeDebugString()} " +
                    "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
            )
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
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_ACQUIRE_END reused=false active=$active activeClients=$activeClients selectedId=$selectedDeviceId " +
                "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
        )
        if (!active) {
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_ACQUIRE_FAIL reason=timeout requested=${device.routeDebugString()} " +
                    "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
            )
        }
        return active
    }

    @SuppressLint("MissingPermission")
    fun selectedCommunicationDevice(): AudioDeviceInfo? {
        val selectedId = selectedDeviceId ?: return null
        val device = audioManager.communicationDevice ?: return null
        return device.takeIf { it.id == selectedId && acceptsAsRsmEndpoint(it) }
    }

    override fun isActive(): Boolean = selectedCommunicationDevice() != null

    override fun release() {
        scope.launch {
            mutex.withLock {
                Log.d(
                    ROUTE_LOG_TAG,
                    "SCO_RELEASE_REQUEST activeClients=$activeClients selectedId=$selectedDeviceId " +
                        "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
                )
                if (activeClients > 0) {
                    activeClients--
                }

                if (activeClients == 0 && keepWarmJob == null) {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "SCO_RELEASE_WARM_START warmMs=$SCO_WARMUP_MS selectedId=$selectedDeviceId " +
                            "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
                    )
                    keepWarmJob = scope.launch {
                        delay(SCO_WARMUP_MS)
                        mutex.withLock {
                            if (activeClients == 0) {
                                Log.d(
                                    ROUTE_LOG_TAG,
                                    "SCO_RELEASE_WARM_CLEAR selectedId=$selectedDeviceId " +
                                        "currentBefore=${audioManager.communicationDevice.routeDebugString()}",
                                )
                                _state.value = ScoState.Closing
                                selectedDeviceId = null
                                audioManager.clearCommunicationDevice()
                                audioManager.mode = AudioManager.MODE_NORMAL
                                _state.value = ScoState.Inactive
                                keepWarmJob = null
                                Log.d(
                                    ROUTE_LOG_TAG,
                                    "SCO_RELEASE_WARM_DONE currentAfter=${audioManager.communicationDevice.routeDebugString()} " +
                                        "state=${_state.value}",
                                )
                            } else {
                                Log.d(
                                    ROUTE_LOG_TAG,
                                    "SCO_RELEASE_WARM_SKIP activeClients=$activeClients selectedId=$selectedDeviceId " +
                                        "current=${audioManager.communicationDevice.routeDebugString()}",
                                )
                            }
                        }
                    }
                } else {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "SCO_RELEASE_RETAINED activeClients=$activeClients keepWarm=${keepWarmJob != null} " +
                            "selectedId=$selectedDeviceId current=${audioManager.communicationDevice.routeDebugString()}",
                    )
                }
            }
        }
    }

    suspend fun releaseImmediately() {
        mutex.withLock {
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_RELEASE_IMMEDIATE_BEGIN activeClients=$activeClients selectedId=$selectedDeviceId " +
                    "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
            )
            activeClients = 0
            keepWarmJob?.cancel()
            keepWarmJob = null
            _state.value = ScoState.Closing
            selectedDeviceId = null
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            _state.value = ScoState.Inactive
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_RELEASE_IMMEDIATE_DONE current=${audioManager.communicationDevice.routeDebugString()} " +
                    "state=${_state.value}",
            )
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
        val rsmConnected = rsmHfpConnected()
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_SCAN rsmHfpConnected=$rsmConnected current=${audioManager.communicationDevice.routeDebugString()} " +
                "devices=${devices.routeDebugString()}",
        )
        // Per-device-identity builds: resolve the actual RSM endpoint, never
        // a car or unrelated SCO peripheral.
        devices.firstOrNull { it.isTargetRsmScoEndpoint() }?.let {
            Log.d(ROUTE_LOG_TAG, "SCO_SELECT reason=target-product-name device=${it.routeDebugString()}")
            return it
        }
        // Synthetic-slot builds (e.g. OPPO CPH2653) expose one anonymous SCO
        // route slot. Accept it only when the RSM is the active HFP peer, so
        // a car or other phone can never satisfy Work readiness.
        if (rsmConnected) {
            devices.firstOrNull { it.isBluetoothScoEndpoint() }?.let {
                Log.d(ROUTE_LOG_TAG, "SCO_SELECT reason=anonymous-hfp-fallback device=${it.routeDebugString()}")
                return it
            }
        }
        Log.d(ROUTE_LOG_TAG, "SCO_SELECT reason=none rsmHfpConnected=$rsmConnected")
        return null
    }

    private fun acceptsAsRsmEndpoint(device: AudioDeviceInfo): Boolean =
        device.isBluetoothScoEndpoint() && (
            device.isTargetRsmScoEndpoint() || rsmHfpConnected()
        )

    companion object {
        private const val SCO_WARMUP_MS = 30_000L
    }
}
