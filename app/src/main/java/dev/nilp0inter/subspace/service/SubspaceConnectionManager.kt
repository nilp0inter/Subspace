package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.bluetooth.DeviceScanner
import dev.nilp0inter.subspace.bluetooth.SppClient
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DevicePresence
import dev.nilp0inter.subspace.model.HeadsetAudioState
import dev.nilp0inter.subspace.model.PermissionState
import dev.nilp0inter.subspace.model.SppState
import dev.nilp0inter.subspace.protocol.ButtonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
internal class SubspaceConnectionManager(
    private val service: PttForegroundService,
    private val scope: CoroutineScope,
    private val appState: () -> AppState
) {
    var bluetoothAdapter: BluetoothAdapter? = null
    var headsetProxy: BluetoothHeadset? = null
    lateinit var scanner: DeviceScanner
    val reconnectPolicy = ReconnectPolicy()
    
    var targetDevice: BluetoothDevice? = null
    var primedCarHfpDevice: BluetoothDevice? = null
    var sppClient: SppClient? = null
    
    var serialJob: Job? = null
    var sppStateJob: Job? = null
    var reconnectJob: Job? = null
    var readinessRefreshJob: Job? = null

    private val headsetServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as? BluetoothHeadset
                scope.launch { refreshReadiness() }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                scope.launch { refreshReadiness() }
            }
        }
    }

    fun onCreate() {
        bluetoothAdapter = service.getSystemService(BluetoothManager::class.java)?.adapter
        scanner = DeviceScanner(service.applicationContext, bluetoothAdapter)
        bluetoothAdapter?.getProfileProxy(service, headsetServiceListener, BluetoothProfile.HEADSET)
    }

    fun onDestroy() {
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy)
        headsetProxy = null
        cancelJobs()
    }

    fun cancelJobs() {
        reconnectJob?.cancel()
        reconnectJob = null
        readinessRefreshJob?.cancel()
        readinessRefreshJob = null
        serialJob?.cancel()
        serialJob = null
        sppStateJob?.cancel()
        sppStateJob = null
        sppClient?.disconnect()
        sppClient = null
    }

    fun refreshReadiness() {
        val missing = RequiredPermissions.missing(service)
        val permissions = if (missing.isEmpty()) PermissionState.Granted else PermissionState.Missing
        val canInspectBluetooth = RequiredPermissions.hasBluetoothConnect(service)
        val bluetoothEnabled = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

        val bonded = if (canInspectBluetooth && bluetoothEnabled) {
            runCatching { scanner.bondedTarget() }.getOrNull()
        } else {
            null
        }
        if (bonded != null) targetDevice = bonded

        val previousConnection = appState().connection
        val devicePresence = when {
            bonded != null -> DevicePresence.Bonded
            targetDevice?.bondState == BluetoothDevice.BOND_BONDED -> DevicePresence.Bonded
            previousConnection.devicePresence in setOf(
                DevicePresence.Scanning,
                DevicePresence.Found,
                DevicePresence.Pairing,
                DevicePresence.PairingFailed,
            ) -> previousConnection.devicePresence
            else -> DevicePresence.NotFound
        }

        val headsetAudio = if (permissions == PermissionState.Granted && bluetoothEnabled) {
            if (isRsmHfpConnected()) HeadsetAudioState.Available else HeadsetAudioState.Unavailable
        } else {
            HeadsetAudioState.Unavailable
        }

        service.updateConnection {
            it.copy(
                permissions = permissions,
                missingPermissions = missing,
                bluetoothEnabled = bluetoothEnabled,
                devicePresence = devicePresence,
                headsetAudio = headsetAudio,
            )
        }
    }

    fun scanForDevice() {
        scope.launch {
            refreshReadiness()
            if (appState().connection.permissions != PermissionState.Granted) return@launch
            if (!appState().connection.bluetoothEnabled) return@launch

            service.updateConnection { it.copy(devicePresence = DevicePresence.Scanning) }
            val found = runCatching { scanner.scanForTarget() }.getOrNull()
            targetDevice = found
            service.updateConnection {
                it.copy(
                    devicePresence = when {
                        found == null -> DevicePresence.NotFound
                        found.bondState == BluetoothDevice.BOND_BONDED -> DevicePresence.Bonded
                        else -> DevicePresence.Found
                    },
                )
            }
            refreshReadiness()
        }
    }

    fun pairTarget() {
        scope.launch {
            val device = targetDevice ?: runCatching { scanner.scanForTarget() }.getOrNull()
            if (device == null) {
                service.updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                return@launch
            }

            targetDevice = device
            service.updateConnection { it.copy(devicePresence = DevicePresence.Pairing) }
            val bonded = runCatching { scanner.createBondAndWait(device) }.getOrDefault(false)
            service.updateConnection {
                it.copy(devicePresence = if (bonded) DevicePresence.Bonded else DevicePresence.PairingFailed)
            }
            refreshReadiness()
        }
    }

    fun connectSerial() {
        val adapter = bluetoothAdapter ?: return
        if (serialJob?.isActive == true) return
        reconnectPolicy.startMonitoring()
        reconnectJob?.cancel()

        scope.launch {
            refreshReadiness()
            if (appState().connection.permissions != PermissionState.Granted) return@launch
            if (!appState().connection.bluetoothEnabled) return@launch

            val device = resolveManualSerialTarget()
            if (device == null) {
                service.updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                return@launch
            }
            startSerialSession(adapter, device, automatic = false)
        }
    }

    fun disconnectSerial() {
        reconnectPolicy.clearMonitoring()
        reconnectJob?.cancel()
        reconnectJob = null
        stopReadinessRefreshLoop()
        serialJob?.cancel()
        serialJob = null
        sppStateJob?.cancel()
        sppStateJob = null
        sppClient?.disconnect()
        sppClient = null
        service.updateConnection { it.copy(spp = SppState.Disconnected) }
        service.stopForegroundIfNeeded()
        service.stopSelf()
        refreshReadiness()
    }

    fun targetRsm(): BluetoothDevice? =
        runCatching { scanner.bondedTarget() }.getOrNull()

    fun targetRsmName(): String? = targetRsm()?.name

    fun isRsmHfpConnected(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching {
            proxy.getConnectionState(rsm) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    fun startTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        val connectionState = runCatching { proxy.getConnectionState(rsm) }.getOrDefault(-1)
        Log.d(
            ROUTE_LOG_TAG,
            "RSM_HFP_START_REQUEST target='${rsm.name}' connectionState=$connectionState " +
                "audioBefore=${runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)} " +
            "current=${service.audioCoordinator.audioManager.communicationDevice.routeDebugString()} " +
            "devices=${service.audioCoordinator.audioManager.availableCommunicationDevices.routeDebugString()}",
        )
        return runCatching { proxy.startVoiceRecognition(rsm) }.getOrDefault(false)
    }

    fun stopTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching { proxy.stopVoiceRecognition(rsm) }.getOrDefault(false)
    }

    fun isTargetRsmHfpAudioConnected(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)
    }

    suspend fun primeCarHfpForTelecom(): Boolean {
        val proxy = headsetProxy ?: return true
        val rsm = targetRsm()
        val car = runCatching {
            proxy.connectedDevices.firstOrNull { device ->
                device != rsm &&
                    device.name?.contains(dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME, ignoreCase = true) != true &&
                    proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
            }
        }.getOrNull()
        if (car == null) {
            Log.d(ROUTE_LOG_TAG, "CAR_HFP_PRIME_SKIP reason=no-non-rsm-hfp-device")
            return true
        }
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_HFP_PRIME_BEGIN target='${car.name}' audioBefore=${runCatching { proxy.isAudioConnected(car) }.getOrDefault(false)} " +
            "current=${service.audioCoordinator.audioManager.communicationDevice.routeDebugString()}",
        )
        val started = runCatching { proxy.startVoiceRecognition(car) }
            .onFailure {
                Log.d(ROUTE_LOG_TAG, "CAR_HFP_PRIME_START_THROW target='${car.name}' error=${it.javaClass.simpleName}")
            }
            .getOrDefault(false)
        Log.d(ROUTE_LOG_TAG, "CAR_HFP_PRIME_START target='${car.name}' returned=$started")
        if (!started) return false
        val connected = withTimeoutOrNull(CAR_HFP_PRIME_TIMEOUT_MS) {
            while (!runCatching { proxy.isAudioConnected(car) }.getOrDefault(false)) {
                delay(CAR_HFP_PRIME_POLL_MS)
            }
            true
        } == true
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_HFP_PRIME_END target='${car.name}' connected=$connected " +
            "current=${service.audioCoordinator.audioManager.communicationDevice.routeDebugString()}",
        )
        if (connected) primedCarHfpDevice = car
        return connected
    }

    fun stopPrimedCarHfp(reason: String) {
        val proxy = headsetProxy ?: return
        val car = primedCarHfpDevice ?: return
        val stopped = runCatching { proxy.stopVoiceRecognition(car) }
            .onFailure {
                Log.d(ROUTE_LOG_TAG, "CAR_HFP_PRIME_STOP_THROW target='${car.name}' error=${it.javaClass.simpleName}")
            }
            .getOrDefault(false)
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_HFP_PRIME_STOP target='${car.name}' returned=$stopped reason=$reason " +
                "audioAfter=${runCatching { proxy.isAudioConnected(car) }.getOrDefault(false)}",
        )
        primedCarHfpDevice = null
    }

    private fun resolveManualSerialTarget(): BluetoothDevice? {
        val device = findBondedTarget() ?: targetDevice
        if (device?.bondState != BluetoothDevice.BOND_BONDED) return null
        targetDevice = device
        return device
    }

    private fun findBondedTarget(): BluetoothDevice? {
        if (!RequiredPermissions.hasBluetoothConnect(service)) return null
        if (bluetoothAdapter?.isEnabled != true) return null
        return runCatching { scanner.bondedTarget() }.getOrNull()
    }

    private fun reconnectPrerequisites(
        bondedTarget: BluetoothDevice? = findBondedTarget(),
    ): ReconnectPrerequisites = ReconnectPrerequisites(
        permissionsGranted = appState().connection.permissions == PermissionState.Granted,
        bluetoothEnabled = appState().connection.bluetoothEnabled,
        bondedTargetAvailable = bondedTarget?.bondState == BluetoothDevice.BOND_BONDED,
    )

    private fun startSerialSession(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        automatic: Boolean,
    ): Boolean {
        if (serialJob?.isActive == true) return false

        service.ensureForeground()
        sppStateJob?.cancel()
        sppClient?.disconnect()

        var connected = false
        val client = SppClient(adapter, ButtonParser())
        sppClient = client

        sppStateJob = scope.launch {
            client.state.collect { state ->
                if (state == SppState.Connected) {
                    connected = true
                    if (automatic) {
                        reconnectPolicy.finishAttempt(
                            success = true,
                            nowMillis = SystemClock.elapsedRealtime(),
                            prerequisites = reconnectPrerequisites(device),
                        )
                    }
                }
                service.updateConnection {
                    it.copy(
                        spp = state,
                        sppError = if (state == SppState.Failed) "Serial connection failed" else null,
                    )
                }
                refreshReadiness()
            }
        }

        serialJob = scope.launch {
            client.events(device).collect { event -> service.handleRawButtonEvent(event) }
            handleSerialSessionEnded(automatic = automatic, connected = connected)
        }
        return true
    }

    private fun handleSerialSessionEnded(automatic: Boolean, connected: Boolean) {
        service.audioCoordinator.forceReleaseActivePtt()
        service.speechEngineController.cancelAll()
        refreshReadiness()

        if (!reconnectPolicy.monitoringRequested) {
            service.stopForegroundIfNeeded()
            service.stopSelf()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val prerequisites = reconnectPrerequisites()
        val decision = if (automatic && !connected) {
            reconnectPolicy.finishAttempt(success = false, nowMillis = now, prerequisites = prerequisites)
        } else {
            reconnectPolicy.cancelAttempt()
            reconnectPolicy.scheduleAfterUnexpectedLoss(nowMillis = now, prerequisites = prerequisites)
        }
        handleReconnectDecision(decision)
    }

    private fun handleReconnectDecision(decision: ReconnectDecision) {
        when (decision) {
            ReconnectDecision.AlreadyInProgress,
            ReconnectDecision.NoAction,
            ReconnectDecision.StartAttempt,
            -> Unit

            is ReconnectDecision.Schedule -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Wait -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Blocked -> {
                if (decision.reason == ReconnectBlockReason.TargetUnavailable) {
                    service.updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                }
                refreshReadiness()
                service.stopForegroundIfNeeded()
                service.stopSelf()
            }
        }
    }

    private fun scheduleReconnectAt(attemptAtMillis: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val waitMs = (attemptAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs)
            beginAutomaticReconnectAttempt()
        }
    }

    private fun beginAutomaticReconnectAttempt() {
        refreshReadiness()
        val adapter = bluetoothAdapter ?: return
        val device = findBondedTarget()
        if (device != null) targetDevice = device

        when (val decision = reconnectPolicy.beginAttempt(SystemClock.elapsedRealtime(), reconnectPrerequisites(device))) {
            ReconnectDecision.StartAttempt -> {
                if (device == null) {
                    handleReconnectDecision(ReconnectDecision.Blocked(ReconnectBlockReason.TargetUnavailable))
                    return
                }
                if (!startSerialSession(adapter, device, automatic = true)) {
                    reconnectPolicy.cancelAttempt()
                }
            }

            ReconnectDecision.AlreadyInProgress,
            ReconnectDecision.NoAction,
            -> Unit

            is ReconnectDecision.Schedule -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Wait -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Blocked -> handleReconnectDecision(decision)
        }
    }

    fun syncReadinessRefreshLoop() {
        when (
            ReadinessRefreshLoopPolicy.decide(
                refreshAllowed = service.foreground && reconnectPolicy.monitoringRequested,
                readyForMonitor = appState().readyForMonitor,
                refreshLoopActive = readinessRefreshJob?.isActive == true,
            )
        ) {
            ReadinessRefreshLoopDecision.KeepCurrentLoop -> Unit
            ReadinessRefreshLoopDecision.StartRefreshLoop -> startReadinessRefreshLoop()
            ReadinessRefreshLoopDecision.StopRefreshLoop -> stopReadinessRefreshLoop()
        }
    }

    private fun startReadinessRefreshLoop() {
        if (readinessRefreshJob?.isActive == true) return
        readinessRefreshJob = scope.launch {
            while (true) {
                delay(READINESS_REFRESH_INTERVAL_MS)
                if (!service.foreground || appState().readyForMonitor) {
                    syncReadinessRefreshLoop()
                    return@launch
                }
                refreshReadiness()
            }
        }
    }

    private fun stopReadinessRefreshLoop() {
        readinessRefreshJob?.cancel()
        readinessRefreshJob = null
    }

    companion object {
        private const val CAR_HFP_PRIME_TIMEOUT_MS = 1_500L
        private const val CAR_HFP_PRIME_POLL_MS = 50L
        private const val READINESS_REFRESH_INTERVAL_MS = 5_000L
    }
}
