package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ActivePcmPlayback
import dev.nilp0inter.subspace.audio.PlaybackCompletion
import dev.nilp0inter.subspace.audio.PlaybackRouteAcquisition
import dev.nilp0inter.subspace.audio.PlaybackRouteStrategy
import dev.nilp0inter.subspace.audio.RecordedPcm
import java.util.UUID

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
        val route = when (val acquired = strategy().acquire()) {
            is PlaybackRouteAcquisition.Acquired -> acquired.route
            PlaybackRouteAcquisition.Busy -> return releasePlaybackReservation(operation, HostPlaybackResult.Busy)
            is PlaybackRouteAcquisition.Unavailable -> return releasePlaybackReservation(
                operation,
                HostPlaybackResult.Unavailable(acquired.reason),
            )
            is PlaybackRouteAcquisition.Failed -> return releasePlaybackReservation(
                operation,
                HostPlaybackResult.Failed(acquired.reason),
            )
        }
        val playback = try {
            route.start(recording)
        } catch (error: Exception) {
            runCatching { route.release() }
            return releasePlaybackReservation(
                operation,
                HostPlaybackResult.Failed(error.message ?: "Unable to start playback"),
            )
        }
        val skipOnStart = synchronized(lock) {
            val current = owner as? Owner.Playback
            if (current !== operation || closed) null else {
                activePlayback = playback
                current.terminating
            }
        }
        if (skipOnStart == null) {
            playback.skip()
            runCatching { route.release() }
            return releasePlaybackReservation(operation, HostPlaybackResult.Closed)
        }
        if (skipOnStart) playback.skip()
        val result = try {
            when (val completion = playback.awaitCompletion()) {
                PlaybackCompletion.Completed -> HostPlaybackResult.Completed
                PlaybackCompletion.ExplicitlySkipped -> HostPlaybackResult.ExplicitlySkipped
                PlaybackCompletion.Interrupted -> HostPlaybackResult.Interrupted
                is PlaybackCompletion.Failed -> HostPlaybackResult.Failed(completion.reason)
            }
        } finally {
            runCatching { route.release() }
        }
        return releasePlaybackReservation(operation, result)
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
            activePlayback.also {
                activePlayback = null
                owner = null
                rejectedPressPendingRelease = false
            }
        }
        playback?.skip()
    }

    private fun releasePlaybackReservation(
        playback: Owner.Playback,
        result: HostPlaybackResult,
    ): HostPlaybackResult {
        synchronized(lock) {
            if (owner == playback) owner = null
            activePlayback = null
            rejectedPressPendingRelease = false
        }
        return result
    }

    private sealed interface Owner {
        class Capture(val lease: HostCaptureLease, var committed: Boolean = false) : Owner
        class Playback(val operationId: HostAudioOperationId, var terminating: Boolean = false) : Owner
    }
}
