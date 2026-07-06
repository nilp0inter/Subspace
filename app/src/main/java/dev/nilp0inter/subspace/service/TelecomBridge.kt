package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.telecom.SubspacePhoneAccountRegistrar
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
internal class TelecomBridge(
    private val service: PttForegroundService,
    private val scope: CoroutineScope,
    private val appState: () -> AppState,
    private val audioCoordinator: AudioSessionCoordinator,
    private val connectionManager: SubspaceConnectionManager
) {
    val telecomRegistrar = SubspacePhoneAccountRegistrar(service)

    fun onCreate() {
        telecomRegistrar.register()
    }

    fun onDestroy() {
        // Nothing specific to teardown for registrar
    }

    fun onCarPttStart() {
        startTelecomCarPtt()
    }

    fun onCarPttRelease() {
        TelecomCarPttCoordinator.forceAbort()
    }

    fun onTelecomCaptureStart() {
        audioCoordinator.dispatchPttPressed(PttSource.CarTelecom)
        audioCoordinator.updateCarMediaState()
    }

    fun onTelecomCaptureStop() {
        audioCoordinator.dispatchPttReleased(PttSource.CarTelecom)
        audioCoordinator.updateCarMediaState()
    }

    fun onTelecomRouteTimeout() {
        playCarErrorBeep()
        audioCoordinator.updateCarMediaState()
    }

    fun onTelecomConnectionEnded() {
        if (!audioCoordinator.telecomDisconnected.isCompleted) {
            audioCoordinator.telecomDisconnected.complete(Unit)
        }
        audioCoordinator.startIdleTimer()
        audioCoordinator.updateCarMediaState()
    }

    private fun startTelecomCarPtt() {
        scope.launch {
            startTelecomCarPttAfterRouteRelease()
        }
    }

    private suspend fun startTelecomCarPttAfterRouteRelease() {
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_PTT_START activeSession=${audioCoordinator.activePttSession != null} " +
                "modeBefore=${audioCoordinator.inputModeController.mode} " +
                "availability=${audioCoordinator.inputModeController.availability}",
        )
        audioCoordinator.logAudioRouteSnapshot("car-ptt-start")
        if (!audioCoordinator.telecomDisconnected.isCompleted) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=telecom-disconnect-pending")
            return
        }
        if (audioCoordinator.activePttSession != null) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=active-session")
            return
        }
        val transitioned = audioCoordinator.inputModeController.autoTransitionFor(PttSource.CarTelecom)
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_AUTO_TRANSITION source=${PttSource.CarTelecom} ok=$transitioned modeAfter=${audioCoordinator.inputModeController.mode}",
        )
        audioCoordinator.logAudioRouteSnapshot("car-ptt-after-transition")
        if (!transitioned) {
            playCarErrorBeep()
            return
        }
        service.publishInputMode()
        audioCoordinator.cancelIdleTimer()
        
        val decision = decidePttDispatch(appState()) ?: return
        if (decision is PttDispatchDecision.ErrorBeep) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_ERROR_BEEP reason=dispatch-decision mode=${audioCoordinator.inputModeController.mode}")
            playCarErrorBeep()
            return
        }
        audioCoordinator.sco.releaseImmediately("car-ptt-start")
        connectionManager.primeCarHfpForTelecom()
        telecomRegistrar.register()
        if (!telecomRegistrar.isEnabled()) {
            val intent = telecomRegistrar.setupIntent()
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            return
        }

        val telecom = service.getSystemService(android.telecom.TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, telecomRegistrar.handle)
        }
        runCatching {
            audioCoordinator.telecomDisconnected = CompletableDeferred()
            telecom.placeCall(telecomRegistrar.callAddress(), extras)
        }.onFailure {
            if (!audioCoordinator.telecomDisconnected.isCompleted) {
                audioCoordinator.telecomDisconnected.complete(Unit)
            }
            playCarErrorBeep()
        }
    }

    private fun playCarErrorBeep() {
        Log.d(ROUTE_LOG_TAG, "CAR_ERROR_BEEP mode=${audioCoordinator.inputModeController.mode}")
        val route = audioCoordinator.resolvePttAudioRoute(audioCoordinator.inputModeController.mode)
        scope.launch {
            playRouteErrorBeepIfAcquired(route)
        }
    }
}
