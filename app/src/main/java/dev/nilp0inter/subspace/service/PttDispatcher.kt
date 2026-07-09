package dev.nilp0inter.subspace.service

import android.util.Log
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.KeyboardChannel
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Encapsulates PTT press/release dispatch and session tracking.
 *
 * Delegates channel-specific routing to [channelRouter] (typically the owning
 * [PttForegroundService]).
 */
internal class PttDispatcher(
    private val serviceScope: CoroutineScope,
    private val sco: ScoAudioController,
    private val inputModeController: InputModeController,
    private val audioSessionManager: PttAudioSessionManager,
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val publishInputMode: () -> Unit,
    private val cancelIdleTimer: () -> Unit,
    private val startIdleTimer: () -> Unit,
    private val decidePttDispatch: (AppState) -> PttDispatchDecision?,
    private val appStateProvider: () -> AppState,
    private val logAudioRouteSnapshot: (String) -> Unit,
    private val updateCarMediaState: () -> Unit,
) {
    val activePttSession: PttAudioSessionManager.SessionSnapshot?
        get() = audioSessionManager.activeSession

    fun reservePendingPtt(source: PttSource, channelId: String): Boolean =
        audioSessionManager.reservePending(source, channelId, inputModeController.mode)

    fun dispatchPttPressed(source: PttSource): Boolean {
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_PRESS_BEGIN source=$source activeSession=${audioSessionManager.isActive} " +
                "modeBefore=${inputModeController.mode} availability=${inputModeController.availability}",
        )
        logAudioRouteSnapshot("ptt-press-begin-$source")
        val pendingSameSource = activePttSession?.source == source
        if (audioSessionManager.isActive && !pendingSameSource) {
            Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=active-session")
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
            return false
        }
        publishInputMode()
        cancelIdleTimer()

        val appState = appStateProvider()
        val decision = decidePttDispatch(appState)
        Log.d(ROUTE_LOG_TAG, "PTT_DECIDE decision=${decision?.let { it::class.simpleName }} channel=${decision?.channelId}")
        if (decision == null) return false
        val activeChannelId = decision.channelId

        if (decision is PttDispatchDecision.ErrorBeep) {
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
            return false
        }

        Log.d(ROUTE_LOG_TAG, "PTT_DISPATCH channel=$activeChannelId mode=${inputModeController.mode}")
        updateCarMediaState()
        return true
    }

    fun dispatchPttReleased(source: PttSource) {
        val session = activePttSession?.takeIf { ownsPttRelease(it.source, source) } ?: return
        audioSessionManager.release(source)

        if (inputModeController.mode == InputMode.OnTheRoad) {
            startIdleTimer()
        }
        updateCarMediaState()
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

    fun isTerminalCarSource(): Boolean =
        activePttSession?.source == PttSource.CarTelecom

    fun isKeyguardSession(): Boolean =
        activePttSession?.channelId == KeyboardChannel.ID
}
