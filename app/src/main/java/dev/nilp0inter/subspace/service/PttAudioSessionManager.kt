package dev.nilp0inter.subspace.service

import android.util.Log
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureStartResult
import dev.nilp0inter.subspace.audio.CaptureSession
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.CaptureChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.RecordedPcm
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
        session.pttDown = false
        val capture = session.captureSession
        if (capture == null) {
            session.releaseRequested = true
            if (session.route == null && session.setupJob == null) {
                clearIfActive(session)
            }
            return
        }
        scope.launch { finishSession(session, capture) }
    }

    fun cancelActive(reason: String) {
        val session = active ?: return
        session.pttDown = false
        session.cancelRequested = true
        val capture = session.captureSession
        if (capture == null) {
            if (session.route == null && session.setupJob == null) {
                channelRouter.onInputCancelled(session.channelId, reason)
                clearIfActive(session)
            }
            return
        }
        scope.launch { cancelRunningSession(session, capture, reason) }
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
            val gateResult = route.routeGate.await()
            logRoute("AUDIO_SESSION_ROUTE_GATE id=${session.id} gate=${route.routeGate.name} result=$gateResult")
            if (active !== session) return
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
                    cancelSetup(session, gateResult.reason, releaseRoute = true)
                    return
                }
            }
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { active === session && session.pttDown && !session.cancelRequested },
            )
            if (active !== session) return
            when (result) {
                CaptureStartResult.SessionActive -> {
                    failSetup(
                        session,
                        "Capture session already active",
                        releaseRoute = route.endpoint == AudioRouteEndpoint.Car,
                    )
                }
                CaptureStartResult.ScoUnavailable -> {
                    failSetup(session, "SCO unavailable", releaseRoute = route.endpoint == AudioRouteEndpoint.Car)
                }
                CaptureStartResult.Cancelled -> cancelSetup(session, "Cancelled", releaseRoute = false)
                CaptureStartResult.RecordingFailed -> {
                    failSetup(session, "Recording failed", releaseRoute = route.endpoint == AudioRouteEndpoint.Car)
                }
                is CaptureStartResult.RecordingSilenced -> {
                    failSetup(session, "Recording silenced", releaseRoute = route.endpoint == AudioRouteEndpoint.Car)
                }
                is CaptureStartResult.Started -> {
                    if (session.releaseRequested || session.cancelRequested || !session.pttDown) {
                        val recording = captureService.cancelSession(result.session)
                        session.captureSession = null
                        completeRelease(session, recording, cancelled = session.cancelRequested)
                    } else {
                        session.captureSession = result.session
                        channelRouter.onInputStarted(
                            channelId,
                            CaptureChannelAudioInputSession(result.session),
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (active === session) failSetup(session, error.message ?: "Audio input setup failed")
        }
    }

    private suspend fun failSetup(
        session: ActiveSession,
        reason: String,
        releaseRoute: Boolean = true,
    ) {
        if (releaseRoute) releaseRouteOnce(session)
        channelRouter.onInputFailed(session.channelId, reason)
        clearIfActive(session)
    }

    private suspend fun cancelSetup(
        session: ActiveSession,
        reason: String,
        releaseRoute: Boolean,
    ) {
        if (releaseRoute) releaseRouteOnce(session)
        channelRouter.onInputCancelled(session.channelId, reason)
        clearIfActive(session)
    }

    private suspend fun finishSession(session: ActiveSession, capture: CaptureSession) {
        if (active !== session || session.releasing) return
        session.releasing = true
        session.captureSession = null
        val recording = capture.stop()
        completeRelease(session, recording, cancelled = false)
    }

    private suspend fun cancelRunningSession(
        session: ActiveSession,
        capture: CaptureSession,
        reason: String,
    ) {
        if (active !== session || session.releasing) return
        session.releasing = true
        session.captureSession = null
        val recording = captureService.cancelSession(capture)
        channelRouter.onInputCancelled(session.channelId, reason)
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
            channelRouter.onInputCancelled(session.channelId, "Cancelled")
        } else {
            val played = when (val result = channelRouter.onInputReleased(session.channelId, recording)) {
                ChannelInputResult.None -> false
                is ChannelInputResult.Playback -> {
                    session.route?.output?.play(result.recording)
                    true
                }
            }
            if (played) channelRouter.onInputPlaybackCompleted(session.channelId)
        }
        releaseRouteOnce(session)
        clearIfActive(session)
    }


    private fun logRoute(message: String) {
        runCatching { Log.d(ROUTE_LOG_TAG, message) }
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

    private data class ActiveSession(
        val id: Long,
        val source: PttSource,
        val channelId: String,
        val mode: InputMode,
        var route: ResolvedAudioRoute? = null,
        var captureSession: CaptureSession? = null,
        var setupJob: Job? = null,
        var pttDown: Boolean = true,
        var releaseRequested: Boolean = false,
        var cancelRequested: Boolean = false,
        var releasing: Boolean = false,
        var routeReleased: Boolean = false,
    ) {
        fun snapshot(): SessionSnapshot = SessionSnapshot(id, source, channelId, mode)
    }
}
