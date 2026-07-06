package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import dev.nilp0inter.subspace.audio.AndroidMicCaptureSource
import dev.nilp0inter.subspace.audio.AndroidPcmOutput
import dev.nilp0inter.subspace.audio.AndroidVoiceCommunicationCaptureSource
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.LocalPcmOutput
import dev.nilp0inter.subspace.audio.MediaResponsePlayer
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.TelecomCallScoRoute
import dev.nilp0inter.subspace.audio.TelecomCapturePcmOutput
import dev.nilp0inter.subspace.audio.audioModeDebugString
import dev.nilp0inter.subspace.audio.resolveLocalAudioRoute
import dev.nilp0inter.subspace.audio.resolveScoAudioRoute
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.InputModeSelection
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal interface ChannelPttHandler {
    fun onPttPressed(channelId: String, route: ResolvedAudioRoute)
    fun onPttReleased(channelId: String, route: ResolvedAudioRoute)
    fun cancelAndRelease(channelId: String)
    fun cancelAll()
}

internal data class PttSession(
    val source: PttSource,
    val channelId: String,
    val route: ResolvedAudioRoute,
)

@SuppressLint("MissingPermission")
internal class AudioSessionCoordinator(
    private val service: PttForegroundService,
    private val scope: CoroutineScope,
    private val appState: () -> AppState,
    private val connectionManager: SubspaceConnectionManager
) {
    lateinit var sco: ScoAudioController
    lateinit var pcmOutput: AndroidPcmOutput
    lateinit var telecomCaptureOutput: AndroidPcmOutput
    lateinit var localOutput: LocalPcmOutput
    lateinit var micSource: AndroidMicCaptureSource
    lateinit var captureService: CaptureService
    lateinit var voiceCommunicationSource: AndroidVoiceCommunicationCaptureSource
    lateinit var mediaResponsePlayer: MediaResponsePlayer
    lateinit var echo: EchoController
    
    lateinit var audioManager: AudioManager
    val inputModeController = InputModeController()
    var activePttSession: PttSession? = null
    var telecomDisconnected = CompletableDeferred<Unit>().apply { complete(Unit) }
    var idleTimerJob: Job? = null
    
    var pttHandler: ChannelPttHandler? = null

    val isCapturing: StateFlow<Boolean> get() = captureService.isCapturing
    val level: StateFlow<Float> get() = captureService.level

    fun onCreate() {
        audioManager = service.getSystemService(AudioManager::class.java)
        sco = ScoAudioController(
            scope = scope,
            audioManager = audioManager,
            rsmHfpConnected = { connectionManager.isRsmHfpConnected() },
            targetRsmName = { connectionManager.targetRsmName() },
            startTargetRsmHfpAudio = { connectionManager.startTargetRsmHfpAudio() },
            stopTargetRsmHfpAudio = { connectionManager.stopTargetRsmHfpAudio() },
            isTargetRsmHfpAudioConnected = { connectionManager.isTargetRsmHfpAudioConnected() },
        )
        pcmOutput = AndroidPcmOutput(audioManager, sco::selectedCommunicationDevice)
        telecomCaptureOutput = AndroidPcmOutput(audioManager)
        localOutput = LocalPcmOutput()
        micSource = AndroidMicCaptureSource()
        captureService = CaptureService(scope)
        voiceCommunicationSource = AndroidVoiceCommunicationCaptureSource()
        mediaResponsePlayer = MediaResponsePlayer(audioManager, localOutput)
        echo = EchoController(
            scope = scope,
            sco = sco,
            captureService = captureService,
            source = voiceCommunicationSource,
            output = pcmOutput,
        )

        scope.launch {
            sco.state.collect { state ->
                service.updateMonitor { it.copy(scoState = state) }
            }
        }
        scope.launch {
            echo.status.collect { status ->
                if (isTerminalCarStatus(status)) forceReleaseActivePtt()
                service.updateMonitor { it.copy(echoStatus = status) }
            }
        }
    }

    fun logAudioRouteSnapshot(event: String) {
        Log.d(
            ROUTE_LOG_TAG,
            "SNAPSHOT event=$event mode=${inputModeController.mode} selectedBy=${inputModeController.selectedBy} " +
                "availability=${inputModeController.availability} audioMode=${audioManager.mode.audioModeDebugString()} " +
                "current=${audioManager.communicationDevice.routeDebugString()} " +
                "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",
        )
    }

    private fun ResolvedAudioRoute.routeDebugString(): String =
        "endpoint=$endpoint sco=${sco.javaClass.simpleName} output=${output.javaClass.simpleName} " +
            "source=${source.sourceId}"

    fun forceReleaseActivePtt() {
        val session = activePttSession ?: return
        activePttSession = null
        cancelIdleTimer()
        pttHandler?.cancelAndRelease(session.channelId)
        TelecomCarPttCoordinator.forceAbort()
        updateCarMediaState()
    }

    fun startIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            cleanupOnTheRoadSession()
        }
    }

    fun cancelIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }

    private fun cleanupOnTheRoadSession() {
        updateCarMediaState()
    }

    fun dispatchPttPressed() {
        dispatchPttPressed(PttSource.Rsm)
    }

    fun dispatchPttPressed(source: PttSource): Boolean {
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_PRESS_BEGIN source=$source activeSession=${activePttSession != null} " +
                "modeBefore=${inputModeController.mode} availability=${inputModeController.availability}",
        )
        logAudioRouteSnapshot("ptt-press-begin-$source")
        if (activePttSession != null) {
            Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=active-session")
            return false
        }
        val transitioned = inputModeController.autoTransitionFor(source)
        Log.d(ROUTE_LOG_TAG, "PTT_AUTO_TRANSITION source=$source ok=$transitioned modeAfter=${inputModeController.mode}")
        logAudioRouteSnapshot("ptt-after-transition-$source")
        if (!transitioned) {
            Log.d(
                ROUTE_LOG_TAG,
                "PTT_ERROR_BEEP_SKIP source=$source reason=transition-failed mode=${inputModeController.mode}",
            )
            return false
        }
        service.publishInputMode()
        if (inputModeController.mode != InputMode.Work) {
            sco.requestImmediateRelease("mode-switch-${inputModeController.mode}")
        }
        cancelIdleTimer()

        val state = appState()
        val decision = decidePttDispatch(state) ?: return false
        val activeChannelId = decision.channelId

        val route = resolvePttAudioRoute(inputModeController.mode)

        if (decision is PttDispatchDecision.ErrorBeep) {
            Log.d(ROUTE_LOG_TAG, "PTT_ERROR_BEEP source=$source reason=dispatch-decision route=${route.routeDebugString()}")
            scope.launch {
                playRouteErrorBeepIfAcquired(route)
            }
            return false
        }

        activePttSession = PttSession(
            source = source,
            channelId = activeChannelId,
            route = route,
        )

        pttHandler?.onPttPressed(activeChannelId, route)
        updateCarMediaState()
        return true
    }

    fun resolvePttAudioRoute(mode: InputMode): ResolvedAudioRoute {
        val route = when (mode) {
            InputMode.OnTheRoad -> ResolvedAudioRoute(
                sco = TelecomCallScoRoute(TelecomCarPttCoordinator::isCaptureRouteReady),
                output = TelecomCapturePcmOutput(
                    captureOutput = telecomCaptureOutput,
                    mediaResponsePlayer = mediaResponsePlayer,
                    releaseCaptureRoute = { releaseTelecomCaptureRoute() },
                    awaitTelecomDisconnected = { withTimeoutOrNull(POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS) { telecomDisconnected.await() } },
                ),
                source = voiceCommunicationSource,
                endpoint = AudioRouteEndpoint.Car,
            )
            InputMode.Work -> resolveScoAudioRoute(
                scoRoute = sco,
                scoOutput = pcmOutput,
                scoSource = voiceCommunicationSource,
                endpoint = AudioRouteEndpoint.Rsm,
            )
            InputMode.OnAPinch -> {
                logAudioRouteSnapshot("route-resolve-local-before")
                resolveLocalAudioRoute(localOutput, micSource)
            }
        }
        Log.d(ROUTE_LOG_TAG, "ROUTE_RESOLVE mode=$mode ${route.routeDebugString()}")
        return route
    }

    private fun releaseTelecomCaptureRoute() {
        connectionManager.stopPrimedCarHfp("telecom-release")
        logAudioRouteSnapshot("telecom-release-before")
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        logAudioRouteSnapshot("telecom-release-after")
    }

    fun dispatchPttReleased() {
        dispatchPttReleased(PttSource.Rsm)
    }

    fun dispatchPttReleased(source: PttSource) {
        val session = activePttSession?.takeIf { ownsPttRelease(it.source, source) } ?: return
        activePttSession = null
        val route = session.route

        pttHandler?.onPttReleased(session.channelId, route)
        if (inputModeController.mode == InputMode.OnTheRoad) {
            startIdleTimer()
        }
        updateCarMediaState()
    }

    fun updateCarMediaState() {
        val activeReady = decidePttDispatch(appState()) is PttDispatchDecision.Dispatch
        val onTheRoadAvailable = inputModeController.availability.onTheRoad
        val state = when {
            activePttSession != null -> CarMediaPttState.Recording
            activeReady && onTheRoadAvailable -> CarMediaPttState.Ready
            else -> CarMediaPttState.NotReady
        }
        CarMediaStateBus.update(state)
    }

    fun isTerminalCarStatus(status: dev.nilp0inter.subspace.model.EchoStatus): Boolean = isTerminalCarSource() &&
        (status is dev.nilp0inter.subspace.model.EchoStatus.Error || status == dev.nilp0inter.subspace.model.EchoStatus.MaxDurationReached)

    fun isTerminalCarStatus(status: dev.nilp0inter.subspace.model.SttStatus): Boolean = isTerminalCarSource() &&
        (status is dev.nilp0inter.subspace.model.SttStatus.Error || status == dev.nilp0inter.subspace.model.SttStatus.MaxDurationReached)

    fun isTerminalCarStatus(status: dev.nilp0inter.subspace.model.SttTtsStatus): Boolean = isTerminalCarSource() &&
        (status is dev.nilp0inter.subspace.model.SttTtsStatus.Error || status == dev.nilp0inter.subspace.model.SttTtsStatus.MaxDurationReached)

    fun isTerminalCarStatus(status: dev.nilp0inter.subspace.model.KeyboardStatus): Boolean = isTerminalCarSource() &&
        (status is dev.nilp0inter.subspace.model.KeyboardStatus.Error || status == dev.nilp0inter.subspace.model.KeyboardStatus.MaxDurationReached)

    private fun isTerminalCarSource(): Boolean = activePttSession?.source == PttSource.CarTelecom

    companion object {
        private const val POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS = 3_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}
