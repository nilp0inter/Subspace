package dev.nilp0inter.subspace.service

import android.util.Log
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import dev.nilp0inter.subspace.model.KeyboardChannel

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
    private val channelRouter: ChannelRouter,
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val publishInputMode: () -> Unit,
    private val cancelIdleTimer: () -> Unit,
    private val startIdleTimer: () -> Unit,
    private val decidePttDispatch: (AppState) -> PttDispatchDecision?,
    private val appStateProvider: () -> AppState,
    private val logAudioRouteSnapshot: (String) -> Unit,
    private val updateCarMediaState: () -> Unit,
) {
    /** Active PTT session — tracks the current source, channel, and route. */
    var activePttSession: PttSession? = null
        private set

    data class PttSession(
        val source: PttSource,
        val channelId: String,
        val route: ResolvedAudioRoute,
    )

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
        if (inputModeController.mode != InputMode.Work) {
            sco.requestImmediateRelease("mode-switch-${inputModeController.mode}")
        }
        cancelIdleTimer()

        val appState = appStateProvider()
        val decision = decidePttDispatch(appState) ?: return false
        val activeChannelId = decision.channelId

        val route = resolvePttAudioRoute(inputModeController.mode)

        if (decision is PttDispatchDecision.ErrorBeep) {
            Log.d(
                ROUTE_LOG_TAG,
                "PTT_ERROR_BEEP source=$source reason=dispatch-decision route=${route.routeDebugString()}",
            )
            serviceScope.launch {
                playRouteErrorBeepIfAcquired(route)
            }
            return false
        }

        activePttSession = PttSession(
            source = source,
            channelId = activeChannelId,
            route = route,
        )

        channelRouter.onPttPressed(activeChannelId, route)
        updateCarMediaState()
        return true
    }

    fun dispatchPttReleased(source: PttSource) {
        val session = activePttSession?.takeIf { ownsPttRelease(it.source, source) } ?: return
        activePttSession = null
        val activeChannelId = session.channelId
        val route = session.route

        channelRouter.onPttReleased(activeChannelId, route)

        if (inputModeController.mode == InputMode.OnTheRoad) {
            startIdleTimer()
        }
        updateCarMediaState()
    }

    /** Forcefully abort the current PTT session (regardless of source). */
    fun forceReleaseActivePtt() {
        val session = activePttSession ?: return
        activePttSession = null
        cancelIdleTimer()
        channelRouter.cancelAndRelease(session.channelId)
        TelecomCarPttCoordinator.forceAbort()
        updateCarMediaState()
    }

    fun isTerminalCarSource(): Boolean =
        activePttSession?.source == PttSource.CarTelecom

    fun isKeyguardSession(): Boolean =
        activePttSession?.channelId == KeyboardChannel.ID

    fun activeRoute(): ResolvedAudioRoute? = activePttSession?.route
}
