package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.service.SubspaceLogger as Log
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Encapsulates PTT press/release dispatch and session tracking.
 *
 * Admission is decided from the provider-neutral runtime registry projection.
 */
internal class PttDispatcher(
    private val serviceScope: CoroutineScope,
    private val inputModeController: InputModeController,
    private val audioSessionManager: PttAudioSessionManager,
    private val audioCoordinator: HostAudioCoordinator = HostAudioCoordinator(),
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val publishInputMode: () -> Unit,
    private val cancelIdleTimer: () -> Unit,
    private val decidePttDispatch: () -> PttDispatchDecision?,
    private val logAudioRouteSnapshot: (String) -> Unit,
    private val updateCarMediaState: () -> Unit,
) {
    private val captureLeaseLock = Any()
    private val captureLeases = mutableMapOf<Long, HostCaptureLease>()

    val activePttSession: PttAudioSessionManager.SessionSnapshot?
        get() = audioSessionManager.activeSession

    fun reserveCaptureAdmission(): HostCaptureAdmission = audioCoordinator.reserveCapture()

    fun abandonCaptureAdmission(lease: HostCaptureLease): Boolean = audioCoordinator.releaseCapture(lease)

    fun reservePendingPtt(source: PttSource, channelId: String): Boolean = when (val admission = reserveCaptureAdmission()) {
        is HostCaptureAdmission.Granted -> reservePendingPtt(source, channelId, admission.lease)
        HostCaptureAdmission.RejectedByPlayback,
        HostCaptureAdmission.Busy,
        HostCaptureAdmission.Closed,
        -> false
    }

    fun reservePendingPtt(source: PttSource, channelId: String, lease: HostCaptureLease): Boolean {
        if (!audioSessionManager.reservePending(source, channelId, inputModeController.mode)) {
            abandonCaptureAdmission(lease)
            return false
        }
        val sessionId = activePttSession?.id
        if (sessionId == null || !attachCaptureLease(sessionId, lease)) {
            audioSessionManager.cancelPending(source, "Host capture admission lost")
            abandonCaptureAdmission(lease)
            return false
        }
        return true
    }

    fun dispatchPttPressed(source: PttSource): Boolean {
        val pendingSameSource = activePttSession?.source == source
        val captureLease = if (pendingSameSource) {
            null
        } else {
            when (val admission = audioCoordinator.reserveCapture()) {
                is HostCaptureAdmission.Granted -> admission.lease
                HostCaptureAdmission.RejectedByPlayback -> {
                    Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=playback-active")
                    return false
                }
                HostCaptureAdmission.Busy -> {
                    Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=host-audio-busy")
                    return false
                }
                HostCaptureAdmission.Closed -> return false
            }
        }
        fun reject() {
            captureLease?.let(audioCoordinator::releaseCapture)
        }
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_PRESS_BEGIN source=$source activeSession=${audioSessionManager.isActive} " +
                "modeBefore=${inputModeController.mode} availability=${inputModeController.availability}",
        )
        logAudioRouteSnapshot("ptt-press-begin-$source")
        if (audioSessionManager.isActive && !pendingSameSource) {
            Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=active-session")
            reject()
            return false
        }
        val transitioned = inputModeController.autoTransitionFor(source)
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_AUTO_TRANSITION source=$source ok=$transitioned modeAfter=${inputModeController.mode}",
        )
        logAudioRouteSnapshot("ptt-after-transition-$source")
        if (!transitioned) {
            Log.d(
                ROUTE_LOG_TAG,
                "PTT_ERROR_BEEP_SKIP source=$source reason=transition-failed mode=${inputModeController.mode}",
            )
            reject()
            return false
        }
        publishInputMode()
        cancelIdleTimer()
        val decision = decidePttDispatch()

        Log.d(ROUTE_LOG_TAG, "PTT_DECIDE decision=${decision?.let { it::class.simpleName }} channel=${decision?.channelId}")
        if (decision == null) {
            reject()
            return false
        }
        val activeChannelId = decision.channelId

        if (decision is PttDispatchDecision.ErrorBeep) {
            reject()
            val route = resolvePttAudioRoute(inputModeController.mode)
            Log.d(
                ROUTE_LOG_TAG,
                "PTT_ERROR_BEEP source=$source reason=dispatch-decision route=${route.routeDebugString()}",
            )
            serviceScope.launch {
                playRouteErrorBeepIfAcquired(route)
            }
            return false
        }

        val started = audioSessionManager.start(
            source = source,
            channelId = activeChannelId,
            mode = inputModeController.mode,
        )
        if (!started) {
            Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=session-start-rejected")
            reject()
            return false
        }
        val sessionId = activePttSession?.id
        if (sessionId == null || (captureLease != null && !attachCaptureLease(sessionId, captureLease))) {
            audioSessionManager.cancelActive("Host capture admission lost")
            reject()
            return false
        }
        Log.d(ROUTE_LOG_TAG, "PTT_DISPATCH channel=$activeChannelId mode=${inputModeController.mode}")
        updateCarMediaState()
        return true
    }

    private fun attachCaptureLease(sessionId: Long, lease: HostCaptureLease): Boolean {
        if (!audioCoordinator.commitCapture(lease)) return false
        synchronized(captureLeaseLock) { captureLeases[sessionId] = lease }
        return true
    }

    fun dispatchPttReleased(source: PttSource) {
        if (audioCoordinator.consumeRejectedPttRelease()) return
        val session = activePttSession?.takeIf { ownsPttRelease(it.source, source) } ?: return
        audioSessionManager.release(source)

        updateCarMediaState()
    }

    /** Observes the existing terminal publication; it never performs input terminal cleanup. */
    fun onTerminalCompleted(completion: PttAudioSessionManager.TerminalCompletion) {
        val lease = synchronized(captureLeaseLock) { captureLeases.remove(completion.sessionId) } ?: return
        audioCoordinator.releaseCapture(lease)
    }

    /** Forcefully abort the current PTT session (regardless of source). */
    fun forceReleaseActivePtt() {
        val session = activePttSession ?: return
        cancelIdleTimer()
        audioSessionManager.cancelActive("Force release")
        if (session.source == PttSource.CarTelecom) {
            TelecomCarPttCoordinator.forceAbort()
        }
        updateCarMediaState()
    }
    /** Cancel only a pending/pre-capture session for the requested source. */
    fun cancelPending(
        source: PttSource,
        reason: String = "Telecom connection ended",
    ): Boolean = audioSessionManager.cancelPending(source, reason)

    fun isTerminalCarSource(): Boolean =
        activePttSession?.source == PttSource.CarTelecom

}
