package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME
import dev.nilp0inter.subspace.telecom.SubspacePhoneAccountRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Encapsulates the car-telecom PTT lifecycle that was previously inlined in
 * [PttForegroundService].
 *
 * Owns [telecomDisconnected] and [primedCarHfpDevice] state formerly on
 * the service.
 */
internal class CarTelecomStarter(
    private val context: Context,
    private val serviceScope: CoroutineScope,

    private val sco: ScoAudioController,
    private val audioManager: AudioManager,
    private val headsetProxyProvider: () -> BluetoothHeadset?,
    private val targetRsm: () -> BluetoothDevice?,
    private val inputModeController: InputModeController,
    private val telecomRegistrar: SubspacePhoneAccountRegistrar,
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val publishInputMode: () -> Unit,
    private val cancelIdleTimer: () -> Unit,
    private val startIdleTimer: () -> Unit,
    private val isActivePttSession: () -> Boolean,
    private val decidePttDispatch: () -> PttDispatchDecision?,
    private val reservePendingCarPtt: (String) -> Boolean,
    private val cancelPendingCarPtt: (String) -> Unit,
    private val logAudioRouteSnapshot: (String) -> Unit,
    private val updateCarMediaState: () -> Unit,
) {
    /** Tracks whether a telecom disconnect is pending / has completed. */
    var telecomDisconnected = CompletableDeferred<Unit>().apply { complete(Unit) }
        private set

    private var primedCarHfpDevice: BluetoothDevice? = null
    private var lastDisconnectTime = 0L

    /** Launches the car-PTT coroutine. */
    fun startTelecomCarPtt() {
        serviceScope.launch {
            startTelecomCarPttAfterRouteRelease()
        }
    }

    /** Notify that the telecom connection ended (called from service listener). */
    fun notifyTelecomDisconnected() {
        lastDisconnectTime = android.os.SystemClock.elapsedRealtime()
        if (!telecomDisconnected.isCompleted) telecomDisconnected.complete(Unit)
    }

    /** Reset telecom-disconnected state for a new call. */
    private fun resetTelecomDisconnected() {
        telecomDisconnected = CompletableDeferred()
    }
    @SuppressLint("MissingPermission")
    private suspend fun startTelecomCarPttAfterRouteRelease() {
        val now = android.os.SystemClock.elapsedRealtime()
        val timeSinceLastDisconnect = now - lastDisconnectTime
        if (timeSinceLastDisconnect < 500) {
            val waitMs = 500 - timeSinceLastDisconnect
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_DELAY waitMs=$waitMs")
            delay(waitMs)
        }
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_PTT_START activeSession=${isActivePttSession()} modeBefore=${inputModeController.mode} " +
                "availability=${inputModeController.availability}",
        )
        logAudioRouteSnapshot("car-ptt-start")
        if (!telecomDisconnected.isCompleted) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=telecom-disconnect-pending")
            return
        }
        if (isActivePttSession()) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=active-session")
            return
        }
        val transitioned = inputModeController.autoTransitionFor(PttSource.CarTelecom)
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_AUTO_TRANSITION source=${PttSource.CarTelecom} ok=$transitioned modeAfter=${inputModeController.mode}",
        )
        logAudioRouteSnapshot("car-ptt-after-transition")
        if (!transitioned) {
            Log.d(
                ROUTE_LOG_TAG,
                "CAR_PTT_TRANSITION_FAILED mode=${inputModeController.mode}",
            )
            playCarErrorBeep()
            return
        }
        publishInputMode()
        cancelIdleTimer()
        val decision = decidePttDispatch() ?: return
        if (decision is PttDispatchDecision.ErrorBeep) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_ERROR_BEEP reason=dispatch-decision mode=${inputModeController.mode}")
            playCarErrorBeep()
            return
        }

        if (!reservePendingCarPtt(decision.channelId)) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=pending-reservation-rejected")
            return
        }
        sco.releaseImmediately("car-ptt-start")
        primeCarHfpForTelecom()
        telecomRegistrar.register()
        if (!telecomRegistrar.isEnabled()) {
            cancelPendingCarPtt("telecom-account-disabled")
            context.startActivity(telecomRegistrar.setupIntent())
            return
        }

        val telecom = context.getSystemService(android.telecom.TelecomManager::class.java) ?: run {
            cancelPendingCarPtt("telecom-manager-unavailable")
            return
        }
        val extras = Bundle().apply {
            putParcelable(
                android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                telecomRegistrar.handle,
            )
        }
        runCatching {
            resetTelecomDisconnected()
            telecom.placeCall(telecomRegistrar.callAddress(), extras)
        }.onFailure {
            if (!telecomDisconnected.isCompleted) telecomDisconnected.complete(Unit)
            cancelPendingCarPtt("place-call-failed")
            playCarErrorBeep()
        }
    }

    fun playCarErrorBeep() {
        Log.d(ROUTE_LOG_TAG, "CAR_ERROR_BEEP mode=${inputModeController.mode}")
        val route = resolvePttAudioRoute(inputModeController.mode)
        serviceScope.launch {
            playRouteErrorBeepIfAcquired(route)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun primeCarHfpForTelecom(): Boolean {
        val proxy = headsetProxyProvider() ?: return true
        val rsm = targetRsm()
        val car = runCatching {
            proxy.connectedDevices.firstOrNull { device ->
                device != rsm &&
                    device.name?.contains(TARGET_DEVICE_NAME, ignoreCase = true) != true &&
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
                "current=${audioManager.communicationDevice.routeDebugString()}",
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
                "current=${audioManager.communicationDevice.routeDebugString()}",
        )
        if (connected) primedCarHfpDevice = car
        return connected
    }

    @SuppressLint("MissingPermission")
    fun stopPrimedCarHfp(reason: String) {
        val proxy = headsetProxyProvider() ?: return
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

    companion object {
        private const val CAR_HFP_PRIME_TIMEOUT_MS = 1_500L
        private const val CAR_HFP_PRIME_POLL_MS = 50L
    }
}
