package dev.nilp0inter.subspace.service

import android.util.Log
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.CaptureChannelAudioInputSession
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureSession
import dev.nilp0inter.subspace.audio.CaptureStartResult
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.RouteGateResult
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PttAudioSessionManager(
    private val scope: CoroutineScope,
    private val captureService: CaptureService,
    private val channelRouter: ChannelRouter,
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
) {
    private var nextSessionId = 1L
    private var active: ActiveSession? = null

    val activeSession: SessionSnapshot?
        get() = active?.snapshot()

    val isActive: Boolean
        get() = active != null

    fun reservePending(source: PttSource, channelId: String, mode: InputMode): Boolean {
        if (active != null) return false
        active = ActiveSession(
            id = nextSessionId++,
            source = source,
            channelId = channelId,
            mode = mode,
        )
        logRoute("AUDIO_SESSION_PENDING id=${active?.id} source=$source channel=$channelId mode=$mode")
        return true
    }

    fun start(source: PttSource, channelId: String, mode: InputMode): Boolean {
        val existing = active
        val session = when {
            existing == null -> ActiveSession(
                id = nextSessionId++,
                source = source,
                channelId = channelId,
                mode = mode,
            ).also { active = it }
            existing.source == source && existing.route == null && existing.setupJob == null -> existing
            else -> return false
        }
        session.setupJob = scope.launch { runSetup(session, channelId, mode) }
        return true
    }

    fun release(source: PttSource) {
        val session = active?.takeIf { ownsPttRelease(it.source, source) } ?: return
        if (!claimTerminal(session, TerminalClaim.NormalRelease)) return
        session.pttDown = false
        session.releaseRequested = true
        val capture = session.captureSession
        if (capture == null) {
            if (session.route == null && session.setupJob == null) {
                scope.launch { completeSetupTerminal(session, "Released") }
            }
            return
        }
        scope.launch { finishSession(session, capture) }
    }

    fun cancelActive(reason: String) {
        val session = active ?: return
        if (!claimTerminal(session, TerminalClaim.Cancellation)) return
        session.pttDown = false
        session.cancelRequested = true
        val capture = session.captureSession
        if (capture == null) {
            if (session.route == null && session.setupJob == null) {
                scope.launch { completeSetupTerminal(session, reason) }
            }
            return
        }
        scope.launch { cancelRunningSession(session, capture, reason) }
    }

    fun cancelPending(source: PttSource, reason: String): Boolean {
        val session = active?.takeIf {
            it.source == source && it.captureSession == null
        } ?: return false
        if (!claimTerminal(session, TerminalClaim.Cancellation)) return false
        session.pttDown = false
        session.cancelRequested = true
        if (session.route == null && session.setupJob == null) {
            scope.launch { completeSetupTerminal(session, reason) }
        }
        return true
    }

    private suspend fun runSetup(
        session: ActiveSession,
        channelId: String,
        mode: InputMode,
    ) {
        try {
            val route = resolvePttAudioRoute(mode)
            if (active !== session) return
            session.route = route
            logRoute("AUDIO_SESSION_ROUTE id=${session.id} ${route.routeDebugString()}")
            if (completeClaimedSetup(session, "Released")) return
            val gateResult = route.routeGate.await()
            logRoute("AUDIO_SESSION_ROUTE_GATE id=${session.id} gate=${route.routeGate.name} result=$gateResult")
            if (active !== session) return
            if (completeClaimedSetup(session, "Released")) return
            when (gateResult) {
                is RouteGateResult.Success -> Unit
                is RouteGateResult.Failure -> {
                    failSetup(session, gateResult.reason)
                    return
                }
                is RouteGateResult.Timeout -> {
                    failSetup(session, gateResult.reason)
                    return
                }
                is RouteGateResult.Cancellation -> {
                    cancelSetup(session, gateResult.reason, playFeedback = true)
                    return
                }
            }
            val target = when (val acceptance = channelRouter.prepareInput(channelId)) {
                is ChannelInputAcceptance.Accepted -> acceptance.target
                is ChannelInputAcceptance.Refused -> {
                    failSetup(session, acceptance.reason)
                    return
                }
                is ChannelInputAcceptance.Unavailable -> {
                    failSetup(session, acceptance.reason)
                    return
                }
            }
            if (active !== session) return
            session.channelTarget = target
            if (completeClaimedSetup(session, "Released")) return
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = {
                    active === session &&
                        session.pttDown &&
                        session.terminalClaim == TerminalClaim.None
                },
            )
            if (active !== session) return
            when (result) {
                CaptureStartResult.SessionActive ->
                    failSetup(session, "Capture session already active")
                CaptureStartResult.ScoUnavailable -> failSetup(session, "SCO unavailable")
                CaptureStartResult.Cancelled -> cancelSetup(session, "Cancelled")
                CaptureStartResult.RecordingFailed -> failSetup(session, "Recording failed")
                is CaptureStartResult.RecordingSilenced ->
                    failSetup(session, "Recording silenced")
                is CaptureStartResult.Started -> {
                    if (session.terminalClaim != TerminalClaim.None || !session.pttDown) {
                        val recording = captureService.cancelSession(result.session)
                        session.captureSession = null
                        completeRelease(session, recording, cancelled = true)
                    } else {
                        session.captureSession = result.session
                        target.onInputStarted(CaptureChannelAudioInputSession(result.session))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (active === session) failSetup(session, error.message ?: "Audio input setup failed")
        }
    }

    private suspend fun failSetup(session: ActiveSession, reason: String) {
        if (!claimTerminal(session, TerminalClaim.Failure)) {
            completeClaimedSetup(session, reason)
            return
        }
        playProblemBeep(session)
        releaseRouteOnce(session)
        session.channelTarget?.onInputFailed(reason)
        clearIfActive(session)
    }

    private suspend fun cancelSetup(
        session: ActiveSession,
        reason: String,
        playFeedback: Boolean = false,
    ) {
        if (claimTerminal(session, TerminalClaim.Cancellation)) {
            if (playFeedback) playProblemBeep(session)
            completeSetupTerminal(session, reason)
        } else {
            completeClaimedSetup(session, reason)
        }
    }

    private suspend fun finishSession(session: ActiveSession, capture: CaptureSession) {
        if (active !== session || session.terminalClaim != TerminalClaim.NormalRelease) return
        session.captureSession = null
        val recording = capture.stop()
        completeRelease(session, recording, cancelled = false)
    }

    private suspend fun cancelRunningSession(
        session: ActiveSession,
        capture: CaptureSession,
        reason: String,
    ) {
        if (active !== session || session.terminalClaim != TerminalClaim.Cancellation) return
        session.captureSession = null
        val recording = captureService.cancelSession(capture)
        session.channelTarget?.onInputCancelled(reason)
        releaseRouteOnce(session)
        clearIfActive(session)
        if (!recording.isEmpty) logRoute("AUDIO_SESSION_CANCEL_DROPPED_PCM id=${session.id} samples=${recording.samples.size}")
    }

    private suspend fun completeRelease(
        session: ActiveSession,
        recording: RecordedPcm,
        cancelled: Boolean,
    ) {
        if (active !== session) return
        if (cancelled) {
            session.channelTarget?.onInputCancelled("Cancelled")
        } else {
            val target = session.channelTarget
            val played = when (val result = target?.onInputReleased(recording) ?: ChannelInputResult.None) {
                ChannelInputResult.None -> false
                is ChannelInputResult.Playback -> {
                    session.route?.output?.play(result.recording)
                    true
                }
            }
            if (played) target?.onInputPlaybackCompleted()
        }
        releaseRouteOnce(session)
        clearIfActive(session)
    }


    private fun logRoute(message: String) {
        runCatching { Log.d(ROUTE_LOG_TAG, message) }
    }
    private suspend fun playProblemBeep(session: ActiveSession) {
        val route = session.route ?: return
        runCatching { route.output.playErrorBeep(route.sco.coldStart) }
    }

    private fun claimTerminal(session: ActiveSession, claim: TerminalClaim): Boolean {
        if (active !== session || session.terminalClaim != TerminalClaim.None) return false
        session.terminalClaim = claim
        return true
    }

    private suspend fun completeClaimedSetup(session: ActiveSession, reason: String): Boolean {
        if (session.terminalClaim == TerminalClaim.None) return false
        if (session.terminalClaim == TerminalClaim.Failure) return true
        completeSetupTerminal(session, reason)
        return true
    }

    private suspend fun completeSetupTerminal(session: ActiveSession, reason: String) {
        if (active !== session || session.captureSession != null) return
        if (session.route?.endpoint == AudioRouteEndpoint.Car) releaseRouteOnce(session)
        session.channelTarget?.onInputCancelled(reason)
        clearIfActive(session)
    }

    private suspend fun releaseRouteOnce(session: ActiveSession) {
        if (session.routeReleased) return
        session.routeReleased = true
        session.route?.output?.releaseRoute()
    }

    private fun clearIfActive(session: ActiveSession) {
        if (active === session) active = null
    }

    data class SessionSnapshot(
        val id: Long,
        val source: PttSource,
        val channelId: String,
        val mode: InputMode,
    )

    private enum class TerminalClaim {
        None,
        NormalRelease,
        Cancellation,
        Failure,
    }

    private data class ActiveSession(
        val id: Long,
        val source: PttSource,
        val channelId: String,
        val mode: InputMode,
        var channelTarget: ChannelInputTarget? = null,
        var route: ResolvedAudioRoute? = null,
        var captureSession: CaptureSession? = null,
        var setupJob: Job? = null,
        var pttDown: Boolean = true,
        var releaseRequested: Boolean = false,
        var cancelRequested: Boolean = false,
        var terminalClaim: TerminalClaim = TerminalClaim.None,
        var routeReleased: Boolean = false,
    ) {
        fun snapshot(): SessionSnapshot = SessionSnapshot(id, source, channelId, mode)
    }
}
