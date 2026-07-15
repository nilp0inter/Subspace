package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.AcquiredPlaybackRoute
import dev.nilp0inter.subspace.audio.ActivePcmPlayback
import dev.nilp0inter.subspace.audio.PlaybackCompletion
import dev.nilp0inter.subspace.audio.PlaybackRouteAcquisition
import dev.nilp0inter.subspace.audio.PlaybackRouteStrategy
import dev.nilp0inter.subspace.audio.RecordedPcm
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The process-wide conversational-audio owner.
 *
 * Capture code remains authoritative for capture mechanics. It receives an opaque lease before
 * entering its existing path and returns that lease only from its existing terminal callback.
 * Playback resolves a physical route only after this coordinator has granted its lease.
 */
internal class HostAudioCoordinator(
    private val newOperationId: () -> HostAudioOperationId = { HostAudioOperationId(UUID.randomUUID().toString()) },
) {
    private val lock = Any()
    private var closed = false
    private var owner: Owner? = null
    private var activePlayback: ActivePcmPlayback? = null
    private var rejectedPressPendingRelease = false

    /**
     * This is intentionally synchronous: PTT ingress must reject playback before the protected
     * dispatcher can auto-transition the mode or reserve an input session.
     */
    fun reserveCapture(): HostCaptureAdmission {
        val (playback, admission) = synchronized(lock) {
            if (closed) return@synchronized null to HostCaptureAdmission.Closed
            when (owner) {
                null -> {
                    val lease = HostCaptureLease(newOperationId())
                    owner = Owner.Capture(lease)
                    null to HostCaptureAdmission.Granted(lease)
                }
                is Owner.Playback -> {
                    rejectedPressPendingRelease = true
                    activePlayback to HostCaptureAdmission.RejectedByPlayback
                }
                else -> null to HostCaptureAdmission.Busy
            }
        }
        playback?.rejectPttWithTone()
        return admission
    }

    /** Marks a capture lease as handed to the unchanged capture/session lifecycle. */
    fun commitCapture(lease: HostCaptureLease): Boolean = synchronized(lock) {
        val capture = owner as? Owner.Capture ?: return@synchronized false
        if (capture.lease != lease) return@synchronized false
        capture.committed = true
        true
    }

    /** Called exclusively by the existing PTT terminal-completion observer. */
    fun releaseCapture(lease: HostCaptureLease): Boolean = synchronized(lock) {
        val capture = owner as? Owner.Capture ?: return@synchronized false
        if (capture.lease != lease) return@synchronized false
        owner = null
        true
    }

    /** Consumes the release paired with a PTT press rejected during active playback. */
    fun consumeRejectedPttRelease(): Boolean = synchronized(lock) {
        if (!rejectedPressPendingRelease) return@synchronized false
        rejectedPressPendingRelease = false
        true
    }

    suspend fun play(
        recording: RecordedPcm,
        strategy: () -> PlaybackRouteStrategy,
    ): HostPlaybackResult {
        val operation = synchronized(lock) {
            if (closed) return HostPlaybackResult.Closed
            if (owner != null) return HostPlaybackResult.Busy
            Owner.Playback(newOperationId()).also { owner = it }
        }
        var route: AcquiredPlaybackRoute? = null
        var playback: ActivePcmPlayback? = null
        var completion: PlaybackCompletion? = null
        var result: HostPlaybackResult = HostPlaybackResult.Interrupted
        try {
            when (val acquired = strategy().acquire()) {
                is PlaybackRouteAcquisition.Acquired -> route = acquired.route
                PlaybackRouteAcquisition.Busy -> {
                    result = HostPlaybackResult.Busy
                    return result
                }
                is PlaybackRouteAcquisition.Unavailable -> {
                    result = HostPlaybackResult.Unavailable(acquired.reason)
                    return result
                }
                is PlaybackRouteAcquisition.Failed -> {
                    result = HostPlaybackResult.Failed(acquired.reason)
                    return result
                }
            }

            val acquiredRoute = checkNotNull(route)
            val startedPlayback = acquiredRoute.start(recording)
            playback = startedPlayback
            val skipOnStart = synchronized(lock) {
                val current = owner as? Owner.Playback
                if (current !== operation || closed) null else {
                    activePlayback = startedPlayback
                    current.terminating
                }
            }
            if (skipOnStart == null || skipOnStart) startedPlayback.skip()

            completion = startedPlayback.awaitCompletion()
            result = if (skipOnStart == null) {
                HostPlaybackResult.Closed
            } else {
                when (val terminal = completion) {
                    PlaybackCompletion.Completed -> HostPlaybackResult.Completed
                    PlaybackCompletion.ExplicitlySkipped -> HostPlaybackResult.ExplicitlySkipped
                    PlaybackCompletion.Interrupted -> HostPlaybackResult.Interrupted
                    is PlaybackCompletion.Failed -> HostPlaybackResult.Failed(terminal.reason)
                    null -> HostPlaybackResult.Failed("Playback completed without a terminal result")
                }
            }
        } catch (error: CancellationException) {
            result = HostPlaybackResult.Interrupted
            throw error
        } catch (error: Exception) {
            result = HostPlaybackResult.Failed(error.message ?: "Unable to play recording")
        } finally {
            withContext(NonCancellable) {
                val active = playback
                if (active != null && completion == null) {
                    active.skip()
                    runCatching { active.awaitCompletion() }
                }
                route?.let { acquired -> runCatching { acquired.release() } }
                releasePlaybackReservation(operation, result)
            }
        }
        return result
    }

    suspend fun consumeSosDuringPlayback(): HostSosDisposition {
        val control = synchronized(lock) {
            val current = owner as? Owner.Playback ?: return@synchronized null
            current.terminating = true
            current to activePlayback
        } ?: return HostSosDisposition.DispatchToChannel
        control.second?.skip()
        return HostSosDisposition.ConsumedByPlayback
    }

    suspend fun close() {
        val playback = synchronized(lock) {
            closed = true
            (owner as? Owner.Playback)?.terminating = true
            activePlayback
        }
        playback?.skip()
    }

    private fun releasePlaybackReservation(
        playback: Owner.Playback,
        result: HostPlaybackResult,
    ): HostPlaybackResult {
        synchronized(lock) {
            if (owner == playback) {
                owner = null
                activePlayback = null
                rejectedPressPendingRelease = false
            }
        }
        return result
    }

    private sealed interface Owner {
        class Capture(val lease: HostCaptureLease, var committed: Boolean = false) : Owner
        class Playback(val operationId: HostAudioOperationId, var terminating: Boolean = false) : Owner
    }
}
