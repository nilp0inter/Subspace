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
import dev.nilp0inter.subspace.channel.capability.recordedPcmOf
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the host side of one PTT audio session at a time.
 *
 * A terminal signal claims its session while holding [lock], then all terminal work is performed
 * by [cleanupScope]. The cleanup scope retains the caller's dispatcher for deterministic tests,
 * but has no parent job, so cancelling normal service work cannot strand route or target cleanup.
 */
internal class PttAudioSessionManager(
    private val scope: CoroutineScope,
    private val captureService: CaptureService,
    private val channelRouter: ChannelRouter,
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val onTerminalCompleted: (TerminalCompletion) -> Unit = {},
    private val cleanupScope: CoroutineScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) + SupervisorJob(),
    ),
    private val targetReleaseTimeoutMillis: Long = 125_000L,
    private val shutdownAwaitTimeoutMillis: Long = targetReleaseTimeoutMillis + 20_000L,
) {
    private val lock = Any()

    private var nextSessionId = 1L
    private var active: ActiveSession? = null
    private var shutdown = false

    init {
        require(targetReleaseTimeoutMillis > 0) { "Target release timeout must be positive" }
        require(shutdownAwaitTimeoutMillis > 0) { "Shutdown await timeout must be positive" }
    }

    val activeSession: SessionSnapshot?
        get() = synchronized(lock) { active?.snapshot() }

    val isActive: Boolean
        get() = synchronized(lock) { active != null }

    fun reservePending(source: PttSource, channelId: String, mode: InputMode): Boolean {
        val session = synchronized(lock) {
            if (shutdown || active != null) return false
            ActiveSession(
                id = nextSessionId++,
                source = source,
                channelId = channelId,
                mode = mode,
            ).also { active = it }
        }
        logRoute("AUDIO_SESSION_PENDING id=${session.id} source=$source channel=$channelId mode=$mode")
        return true
    }

    fun start(source: PttSource, channelId: String, mode: InputMode): Boolean {
        val session = synchronized(lock) {
            if (shutdown) return false
            val existing = active
            when {
                existing == null -> ActiveSession(
                    id = nextSessionId++,
                    source = source,
                    channelId = channelId,
                    mode = mode,
                ).also { active = it }
                existing.source == source && existing.route == null && existing.setupJob == null &&
                    existing.terminalClaim == TerminalClaim.None -> existing
                else -> return false
            }
        }
        val setupJob = scope.launch { runSetup(session, channelId, mode) }
        synchronized(lock) {
            if (active === session && session.terminalClaim == TerminalClaim.None) {
                session.setupJob = setupJob
            } else {
                setupJob.cancel()
            }
        }
        return true
    }

    fun release(source: PttSource) {
        val session = synchronized(lock) { active?.takeIf { ownsPttRelease(it.source, source) } } ?: return
        requestTerminal(session, TerminalClaim.NormalRelease, "Released")
    }

    fun cancelActive(reason: String) {
        val session = synchronized(lock) { active } ?: return
        requestTerminal(session, TerminalClaim.Cancellation, reason)
    }

    fun cancelPending(source: PttSource, reason: String): Boolean {
        val session = synchronized(lock) {
            active?.takeIf { it.source == source && it.captureSession == null }
        } ?: return false
        return requestTerminal(session, TerminalClaim.Cancellation, reason)
    }

    /**
     * Stops admission, claims cancellation only when no terminal owner exists, and awaits the
     * same cleanup sequence an earlier terminal signal already owns. The non-cancellable wait is
     * bounded and the actual effects run in [cleanupScope], not in the caller's service job.
     */
    suspend fun shutdownAndAwait(reason: String = "Service teardown") {
        val shutdownState = synchronized(lock) {
            shutdown = true
            val session = active
            TerminalShutdown(
                request = session?.let { claimTerminalLocked(it, TerminalClaim.Cancellation, reason) },
                completion = session?.terminalCompletion,
            )
        }
        shutdownState.request?.start()
        try {
            withContext(NonCancellable) {
                shutdownState.completion?.let {
                    withTimeoutOrNull(shutdownAwaitTimeoutMillis) { it.await() }
                }
            }
        } finally {
            cleanupScope.cancel()
        }
    }

    private suspend fun runSetup(
        session: ActiveSession,
        channelId: String,
        mode: InputMode,
    ) {
        try {
            val route = resolvePttAudioRoute(mode)
            when (attachRoute(session, route)) {
                Attachment.Open -> Unit
                Attachment.TerminalQueued -> return
                Attachment.TerminalCompleted -> {
                    releaseLateRoute(session, route)
                    return
                }
            }
            logRoute("AUDIO_SESSION_ROUTE id=${session.id} ${route.routeDebugString()}")

            when (val gateResult = route.routeGate.await()) {
                is RouteGateResult.Success -> Unit
                is RouteGateResult.Failure -> {
                    requestTerminal(session, TerminalClaim.Failure, gateResult.reason, playProblemFeedback = true)
                    return
                }
                is RouteGateResult.Timeout -> {
                    requestTerminal(session, TerminalClaim.Failure, gateResult.reason, playProblemFeedback = true)
                    return
                }
                is RouteGateResult.Cancellation -> {
                    requestTerminal(session, TerminalClaim.Cancellation, gateResult.reason, playProblemFeedback = true)
                    return
                }
            }
            logRoute("AUDIO_SESSION_ROUTE_GATE id=${session.id} gate=${route.routeGate.name} result=success")
            if (!isOpen(session)) return

            val target = when (val acceptance = channelRouter.prepareInput(channelId)) {
                is ChannelInputAcceptance.Accepted -> acceptance.target
                is ChannelInputAcceptance.Refused -> {
                    requestTerminal(session, TerminalClaim.Failure, acceptance.reason, playProblemFeedback = true)
                    return
                }
                is ChannelInputAcceptance.Unavailable -> {
                    requestTerminal(session, TerminalClaim.Failure, acceptance.reason, playProblemFeedback = true)
                    return
                }
            }
            when (attachTarget(session, target)) {
                Attachment.Open -> Unit
                Attachment.TerminalQueued -> return
                Attachment.TerminalCompleted -> {
                    deliverLateTargetTerminal(session, target)
                    return
                }
            }

            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { isOpen(session) },
            )
            when (result) {
                CaptureStartResult.SessionActive ->
                    requestTerminal(session, TerminalClaim.Failure, "Capture session already active", playProblemFeedback = true)
                CaptureStartResult.ScoUnavailable ->
                    requestTerminal(session, TerminalClaim.Failure, "SCO unavailable", playProblemFeedback = true)
                CaptureStartResult.Cancelled ->
                    requestTerminal(session, TerminalClaim.Cancellation, "Cancelled")
                CaptureStartResult.RecordingFailed ->
                    requestTerminal(session, TerminalClaim.Failure, "Recording failed", playProblemFeedback = true)
                is CaptureStartResult.RecordingSilenced ->
                    requestTerminal(session, TerminalClaim.Failure, "Recording silenced", playProblemFeedback = true)
                is CaptureStartResult.Started -> when (attachCapture(session, result.session)) {
                    Attachment.Open -> {
                        try {
                            target.onInputStarted(CaptureChannelAudioInputSession(result.session))
                        } catch (error: Throwable) {
                            requestTerminal(
                                session,
                                TerminalClaim.Failure,
                                error.message ?: "Channel input start failed",
                                playProblemFeedback = true,
                            )
                        }
                    }
                    Attachment.TerminalQueued -> Unit
                    Attachment.TerminalCompleted -> cancelLateCapture(session, result.session)
                }
            }
        } catch (cancelled: CancellationException) {
            requestTerminal(session, TerminalClaim.Cancellation, "Cancelled")
        } catch (error: Throwable) {
            requestTerminal(
                session,
                TerminalClaim.Failure,
                error.message ?: "Audio input setup failed",
                playProblemFeedback = true,
            )
        }
    }

    private fun requestTerminal(
        session: ActiveSession,
        claim: TerminalClaim,
        reason: String,
        playProblemFeedback: Boolean = false,
    ): Boolean {
        val request = synchronized(lock) {
            claimTerminalLocked(session, claim, reason, playProblemFeedback)
        } ?: return false
        request.start()
        return true
    }

    private fun claimTerminalLocked(
        session: ActiveSession,
        claim: TerminalClaim,
        reason: String,
        playProblemFeedback: Boolean = false,
    ): TerminalRequest? {
        if (active !== session || session.terminalClaim != TerminalClaim.None) return null
        session.terminalClaim = claim
        session.terminalReason = reason
        session.playProblemFeedback = playProblemFeedback
        session.pttDown = false
        return TerminalRequest(session, session.setupJob)
    }

    private fun TerminalRequest.start() {
        setupJob?.cancel()
        cleanupScope.launch { runTerminal(session, setupJob) }
    }

    private suspend fun runTerminal(session: ActiveSession, setupJob: Job?) {
        // Setup can have acquired a route or target just before cancellation. Bounded joining
        // gives it the opportunity to attach those resources to the claimed terminal session.
        if (setupJob != null) {
            attemptEffect(session, "setup cancellation") { setupJob.cancelAndJoin() }
        }

        val captureTermination = terminateCapture(session)
        if (session.playProblemFeedback) {
            attemptEffect(session, "problem feedback") {
                session.route?.output?.playErrorBeep(session.route?.sco?.coldStart == true)
            }
        }

        val targetResult = notifyTerminalTarget(session, captureTermination)
        val playback = when (targetResult) {
            is ChannelInputResult.Playback -> targetResult.recording
            is ChannelInputResult.PlaybackOperation -> recordedPcmOf(targetResult.operation).also {
                if (it == null) recordFailure(session, "playback resolution", "invalid host audio operation")
            }
            ChannelInputResult.None, null -> null
        }
        if (playback != null) {
            // Telecom output must relinquish the call capture route before media playback.
            if (session.route?.endpoint == AudioRouteEndpoint.Car) attemptRouteRelease(session)
            attemptEffect(session, "playback") { session.route?.output?.play(playback) }
            attemptEffect(session, "playback completion") { terminalTarget(session)?.onInputPlaybackCompleted() }
        }

        attemptRouteRelease(session)
        attemptCommittedTargetLeaseRelease(session, terminalTarget(session))
        clearAndPublish(session)
    }

    private suspend fun terminateCapture(session: ActiveSession): CaptureTermination {
        val capture = synchronized(lock) {
            if (session.captureTerminationAttempted) return CaptureTermination.None
            session.captureTerminationAttempted = true
            session.captureSession.also { session.captureSession = null }
        } ?: return CaptureTermination.None

        val recording = if (session.terminalClaim == TerminalClaim.NormalRelease) {
            attemptEffect(session, "capture stop") { capture.stop() }
        } else {
            attemptEffect(session, "capture cancellation") { captureService.cancelSession(capture) }
        }
        return CaptureTermination.Attempted(recording)
    }

    private suspend fun notifyTerminalTarget(
        session: ActiveSession,
        captureTermination: CaptureTermination,
    ): ChannelInputResult? {
        val target = synchronized(lock) {
            if (session.targetNotificationAttempted) return null
            session.targetNotificationAttempted = true
            session.channelTarget
        } ?: return null

        return when (session.terminalClaim) {
            TerminalClaim.NormalRelease -> when (captureTermination) {
                is CaptureTermination.Attempted -> {
                    val recording = captureTermination.recording
                    if (recording != null) {
                        attemptEffect(session, "target release", targetReleaseTimeoutMillis) {
                            target.onInputReleased(recording)
                        }
                    } else {
                        attemptEffect(session, "target failure") {
                            target.onInputFailed("Audio input capture did not complete")
                        }
                        null
                    }
                }
                CaptureTermination.None -> {
                    attemptEffect(session, "target cancellation") { target.onInputCancelled(session.terminalReason) }
                    null
                }
            }
            TerminalClaim.Cancellation -> {
                attemptEffect(session, "target cancellation") { target.onInputCancelled(session.terminalReason) }
                null
            }
            TerminalClaim.Failure -> {
                attemptEffect(session, "target failure") { target.onInputFailed(session.terminalReason) }
                null
            }
            TerminalClaim.None -> null
        }
    }

    private suspend fun attemptRouteRelease(session: ActiveSession) {
        val route = synchronized(lock) {
            if (session.routeReleaseAttempted) return
            session.routeReleaseAttempted = true
            session.route
        } ?: return
        attemptEffect(session, "route release") { route.output.releaseRoute() }
    }

    private fun attachRoute(session: ActiveSession, route: ResolvedAudioRoute): Attachment = synchronized(lock) {
        if (active !== session || session.completionPublished) return Attachment.TerminalCompleted
        session.route = route
        if (session.terminalClaim == TerminalClaim.None) Attachment.Open else Attachment.TerminalQueued
    }

    private fun attachTarget(session: ActiveSession, target: ChannelInputTarget): Attachment = synchronized(lock) {
        if (active !== session || session.completionPublished) return Attachment.TerminalCompleted
        session.channelTarget = target
        when {
            session.terminalClaim == TerminalClaim.None -> Attachment.Open
            session.targetNotificationAttempted -> Attachment.TerminalCompleted
            else -> Attachment.TerminalQueued
        }
    }

    private fun attachCapture(session: ActiveSession, capture: CaptureSession): Attachment = synchronized(lock) {
        if (active !== session || session.completionPublished || session.captureTerminationAttempted) {
            return Attachment.TerminalCompleted
        }
        session.captureSession = capture
        if (session.terminalClaim == TerminalClaim.None) Attachment.Open else Attachment.TerminalQueued
    }

    private fun isOpen(session: ActiveSession): Boolean = synchronized(lock) {
        active === session && session.pttDown && session.terminalClaim == TerminalClaim.None
    }

    private fun deliverLateTargetTerminal(session: ActiveSession, target: ChannelInputTarget) {
        cleanupScope.launch {
            // A provider may ignore cancellation and return a committed target after bounded
            // setup cleanup. It still receives one cancellation so its registry lease is released.
            attemptEffect(session, "late target cancellation") {
                target.onInputCancelled(session.terminalReason.ifBlank { "Stale audio input session" })
            }
            attemptCommittedTargetLeaseRelease(session, target)
        }
    }

    private fun cancelLateCapture(session: ActiveSession, capture: CaptureSession) {
        cleanupScope.launch {
            attemptEffect(session, "late capture cancellation") { captureService.cancelSession(capture) }
        }
    }

    private fun releaseLateRoute(session: ActiveSession, route: ResolvedAudioRoute) {
        cleanupScope.launch {
            attemptEffect(session, "late route release") { route.output.releaseRoute() }
        }
    }

    private fun terminalTarget(session: ActiveSession): ChannelInputTarget? = synchronized(lock) { session.channelTarget }

    private suspend fun attemptCommittedTargetLeaseRelease(
        session: ActiveSession,
        target: ChannelInputTarget?,
    ) {
        val leaseOwner = target as? CommittedTargetLeaseOwner ?: return
        val shouldAttempt = synchronized(lock) {
            if (session.committedLeaseReleaseAttempted) false else {
                session.committedLeaseReleaseAttempted = true
                true
            }
        }
        if (shouldAttempt) {
            attemptEffect(session, "committed target lease release") {
                leaseOwner.releaseCommittedTargetLease()
            }
        }
    }

    private suspend fun <T> attemptEffect(
        session: ActiveSession,
        phase: String,
        timeoutMillis: Long = TERMINAL_EFFECT_TIMEOUT_MS,
        action: suspend () -> T,
    ): T? {
        val effect = cleanupScope.async { runCatching { action() } }
        val outcome = withTimeoutOrNull(timeoutMillis) { effect.await() }
        if (outcome == null) {
            effect.cancel()
            recordFailure(session, phase, "timed out")
            return null
        }
        return outcome.getOrElse { error ->
            recordFailure(session, phase, error.message ?: error::class.simpleName.orEmpty())
            null
        }
    }

    private fun recordFailure(session: ActiveSession, phase: String, message: String) {
        synchronized(lock) {
            session.failures += TerminalFailure(phase, message)
        }
        logRoute("AUDIO_SESSION_TERMINAL_FAILURE id=${session.id} phase=$phase reason=$message")
    }

    private fun clearAndPublish(session: ActiveSession) {
        val completion = synchronized(lock) {
            if (session.completionPublished) return
            session.completionPublished = true
            if (active === session) active = null
            session.terminalCompletion()
        }
        try {
            onTerminalCompleted(completion)
        } catch (error: Throwable) {
            recordFailure(session, "terminal completion publication", error.message ?: error::class.simpleName.orEmpty())
        } finally {
            session.terminalCompletion.complete(completion)
        }
    }

    private fun logRoute(message: String) {
        runCatching { Log.d(ROUTE_LOG_TAG, message) }
    }

    data class SessionSnapshot(
        val id: Long,
        val source: PttSource,
        val channelId: String,
        val mode: InputMode,
        val phase: SessionPhase,
    )

    data class TerminalFailure(
        val phase: String,
        val message: String,
    )

    data class TerminalCompletion(
        val sessionId: Long,
        val source: PttSource,
        val channelId: String,
        val mode: InputMode,
        val failures: List<TerminalFailure> = emptyList(),
    )

    enum class SessionPhase { PttHeld, TerminalWork }

    private enum class TerminalClaim {
        None,
        NormalRelease,
        Cancellation,
        Failure,
    }

    private enum class Attachment { Open, TerminalQueued, TerminalCompleted }
    private sealed interface CaptureTermination {
        data class Attempted(val recording: RecordedPcm?) : CaptureTermination
        data object None : CaptureTermination
    }

    private data class TerminalRequest(
        val session: ActiveSession,
        val setupJob: Job?,
    )
    private data class TerminalShutdown(
        val request: TerminalRequest?,
        val completion: CompletableDeferred<TerminalCompletion>?,
    )

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
        var terminalClaim: TerminalClaim = TerminalClaim.None,
        var terminalReason: String = "",
        var playProblemFeedback: Boolean = false,
        var captureTerminationAttempted: Boolean = false,
        var targetNotificationAttempted: Boolean = false,
        var routeReleaseAttempted: Boolean = false,
        var committedLeaseReleaseAttempted: Boolean = false,
        var completionPublished: Boolean = false,
        val failures: MutableList<TerminalFailure> = mutableListOf(),
        val terminalCompletion: CompletableDeferred<TerminalCompletion> = CompletableDeferred(),
    ) {
        fun snapshot(): SessionSnapshot = SessionSnapshot(
            id = id,
            source = source,
            channelId = channelId,
            mode = mode,
            phase = if (terminalClaim == TerminalClaim.None) SessionPhase.PttHeld else SessionPhase.TerminalWork,
        )

        fun terminalCompletion(): TerminalCompletion = TerminalCompletion(
            sessionId = id,
            source = source,
            channelId = channelId,
            mode = mode,
            failures = failures.toList(),
        )
    }

    private companion object {
        const val TERMINAL_EFFECT_TIMEOUT_MS = 5_000L
    }
}
