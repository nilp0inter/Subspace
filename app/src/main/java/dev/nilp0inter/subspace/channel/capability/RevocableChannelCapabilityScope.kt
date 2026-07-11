package dev.nilp0inter.subspace.channel.capability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Host-owned capability scope for one channel instance and one runtime generation.
 * The host creates a fresh scope for every generation; no global capability lookup is
 * available to providers or runtimes.
 */
class RevocableChannelCapabilityScope(
    override val identity: CapabilityScopeIdentity,
    declaredCapabilities: Set<ChannelCapability>,
    private val host: ChannelCapabilityHost,
    private val diagnostics: CapabilityDiagnosticSink = CapabilityDiagnosticSink { },
) : ChannelCapabilityScope {
    override val declaredCapabilities: Set<ChannelCapability> = declaredCapabilities.toSet()
    private val closed = AtomicBoolean(false)
    private val leases = ConcurrentHashMap.newKeySet<ManagedLease<*>>()

    override val isClosed: Boolean
        get() = closed.get()

    override suspend fun availability(key: CapabilityKey<*>): CapabilityAvailabilityResult {
        if (!isDeclared(key)) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.DENIED)
            return CapabilityAvailabilityResult.Denied(CapabilityDeniedReason.UNDECLARED)
        }
        if (closed.get()) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.CLOSED)
            return CapabilityAvailabilityResult.Closed
        }
        return try {
            val availability = host.availability(identity, key)
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, availability.outcome())
            CapabilityAvailabilityResult.State(availability)
        } catch (_: CancellationException) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.CANCELLED)
            CapabilityAvailabilityResult.Cancelled
        } catch (_: Exception) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.FAILED)
            CapabilityAvailabilityResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }

    override suspend fun <T : ChannelCapabilityPort> acquire(
        key: CapabilityKey<T>,
        policy: CapabilityAcquisitionPolicy,
    ): CapabilityAcquisition<T> {
        if (!isDeclared(key)) {
            record(key.capability, CapabilityDiagnosticPhase.ACQUISITION, CapabilityDiagnosticOutcome.DENIED)
            return CapabilityAcquisition.Denied(CapabilityDeniedReason.UNDECLARED)
        }
        if (closed.get()) {
            record(key.capability, CapabilityDiagnosticPhase.ACQUISITION, CapabilityDiagnosticOutcome.CLOSED)
            return CapabilityAcquisition.Closed
        }

        val initial = acquireFromHost(key, preparationTimeoutMillis = null)
        if (initial !is HostedCapabilityAcquisition.Recoverable) {
            return bind(key, initial, CapabilityDiagnosticPhase.ACQUISITION)
        }

        record(key.capability, CapabilityDiagnosticPhase.ACQUISITION, CapabilityDiagnosticOutcome.RECOVERABLE)
        val preparation = policy as? CapabilityAcquisitionPolicy.PrepareRecoverable
            ?: return CapabilityAcquisition.Recoverable(initial.reason)
        if (closed.get()) {
            record(key.capability, CapabilityDiagnosticPhase.PREPARATION, CapabilityDiagnosticOutcome.CLOSED)
            return CapabilityAcquisition.Closed
        }

        return bind(
            key,
            acquireFromHost(key, preparation.timeoutMillis),
            CapabilityDiagnosticPhase.PREPARATION,
        )
    }

    /**
     * Invalidates every lease in this generation. The first caller owns each lease's
     * cleanup; concurrent retirement, timeout, and shutdown callers only observe the
     * already-terminal state.
     */
    suspend fun revoke(): CapabilityScopeTerminationResult {
        if (!closed.compareAndSet(false, true)) return CapabilityScopeTerminationResult.AlreadyClosed

        var cleanupFailed = false
        for (lease in leases.toList()) {
            if (lease.revoke() is CapabilityReleaseResult.CleanupFailed) cleanupFailed = true
        }
        return if (cleanupFailed) {
            CapabilityScopeTerminationResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED)
        } else {
            CapabilityScopeTerminationResult.Revoked
        }
    }

    private suspend fun <T : ChannelCapabilityPort> acquireFromHost(
        key: CapabilityKey<T>,
        preparationTimeoutMillis: Long?,
    ): HostedCapabilityAcquisition<T> {
        return try {
            if (preparationTimeoutMillis == null) {
                host.acquire(identity, key)
            } else {
                host.prepareAndAcquire(identity, key, preparationTimeoutMillis)
            }
        } catch (_: CancellationException) {
            HostedCapabilityAcquisition.Cancelled
        } catch (_: Exception) {
            HostedCapabilityAcquisition.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }

    private suspend fun <T : ChannelCapabilityPort> bind(
        key: CapabilityKey<T>,
        hosted: HostedCapabilityAcquisition<T>,
        phase: CapabilityDiagnosticPhase,
    ): CapabilityAcquisition<T> {
        return when (hosted) {
            is HostedCapabilityAcquisition.Available -> {
                val lease = ManagedLease(key.capability, hosted.port, hosted.cleanup)
                if (closed.get()) {
                    lease.revoke()
                    record(key.capability, phase, CapabilityDiagnosticOutcome.CLOSED)
                    CapabilityAcquisition.Closed
                } else {
                    leases += lease
                    if (closed.get()) {
                        lease.revoke()
                        record(key.capability, phase, CapabilityDiagnosticOutcome.CLOSED)
                        CapabilityAcquisition.Closed
                    } else {
                        record(key.capability, phase, CapabilityDiagnosticOutcome.AVAILABLE)
                        CapabilityAcquisition.Available(lease)
                    }
                }
            }
            is HostedCapabilityAcquisition.Recoverable -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.RECOVERABLE)
                CapabilityAcquisition.Recoverable(hosted.reason)
            }
            is HostedCapabilityAcquisition.Unavailable -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.UNAVAILABLE)
                CapabilityAcquisition.Unavailable(hosted.reason)
            }
            HostedCapabilityAcquisition.Cancelled -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.CANCELLED)
                CapabilityAcquisition.Cancelled
            }
            is HostedCapabilityAcquisition.Failed -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.FAILED)
                CapabilityAcquisition.Failed(hosted.reason)
            }
        }
    }

    private fun isDeclared(key: CapabilityKey<*>): Boolean = key.capability in declaredCapabilities

    private fun record(
        capability: ChannelCapability,
        phase: CapabilityDiagnosticPhase,
        outcome: CapabilityDiagnosticOutcome,
    ) {
        // Diagnostics must never change capability behavior or expose an exception message.
        runCatching { diagnostics.record(CapabilityDiagnostic(identity, capability, phase, outcome)) }
    }

    private fun CapabilityAvailability.outcome(): CapabilityDiagnosticOutcome = when (this) {
        CapabilityAvailability.Available -> CapabilityDiagnosticOutcome.AVAILABLE
        CapabilityAvailability.Recoverable -> CapabilityDiagnosticOutcome.RECOVERABLE
        is CapabilityAvailability.Unavailable -> CapabilityDiagnosticOutcome.UNAVAILABLE
    }

    private inner class ManagedLease<T : ChannelCapabilityPort>(
        override val capability: ChannelCapability,
        private val port: T,
        private val cleanup: suspend (CapabilityLeaseTermination) -> Unit,
    ) : CapabilityLease<T> {
        private val leaseState = AtomicReference(CapabilityLeaseState.ACTIVE)

        override val identity: CapabilityScopeIdentity
            get() = this@RevocableChannelCapabilityScope.identity

        override val state: CapabilityLeaseState
            get() = leaseState.get()

        override suspend fun <R> use(
            operation: suspend (T) -> CapabilityOperationResult<R>,
        ): CapabilityOperationResult<R> {
            if (leaseState.get() != CapabilityLeaseState.ACTIVE || closed.get()) {
                record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.CLOSED)
                return CapabilityOperationResult.Closed
            }
            return try {
                val result = operation(port)
                if (leaseState.get() != CapabilityLeaseState.ACTIVE || closed.get()) {
                    record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.CLOSED)
                    CapabilityOperationResult.Closed
                } else {
                    record(capability, CapabilityDiagnosticPhase.OPERATION, result.outcome())
                    result
                }
            } catch (_: CancellationException) {
                record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.CANCELLED)
                CapabilityOperationResult.Cancelled
            } catch (_: Exception) {
                record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.FAILED)
                CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
            }
        }

        override suspend fun release(): CapabilityReleaseResult = terminate(
            expected = CapabilityLeaseState.ACTIVE,
            terminal = CapabilityLeaseState.RELEASED,
            termination = CapabilityLeaseTermination.RELEASED,
            phase = CapabilityDiagnosticPhase.RELEASE,
        )

        suspend fun revoke(): CapabilityReleaseResult = terminate(
            expected = CapabilityLeaseState.ACTIVE,
            terminal = CapabilityLeaseState.REVOKED,
            termination = CapabilityLeaseTermination.REVOKED,
            phase = CapabilityDiagnosticPhase.REVOCATION,
        )

        private suspend fun terminate(
            expected: CapabilityLeaseState,
            terminal: CapabilityLeaseState,
            termination: CapabilityLeaseTermination,
            phase: CapabilityDiagnosticPhase,
        ): CapabilityReleaseResult {
            if (!leaseState.compareAndSet(expected, terminal)) return CapabilityReleaseResult.AlreadyTerminated
            leases -= this
            return try {
                cleanup(termination)
                record(capability, phase, CapabilityDiagnosticOutcome.RELEASED)
                CapabilityReleaseResult.Released
            } catch (_: CancellationException) {
                record(capability, CapabilityDiagnosticPhase.CLEANUP, CapabilityDiagnosticOutcome.CANCELLED)
                CapabilityReleaseResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED)
            } catch (_: Exception) {
                record(capability, CapabilityDiagnosticPhase.CLEANUP, CapabilityDiagnosticOutcome.FAILED)
                CapabilityReleaseResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED)
            }
        }

        private fun <R> CapabilityOperationResult<R>.outcome(): CapabilityDiagnosticOutcome = when (this) {
            is CapabilityOperationResult.Success -> CapabilityDiagnosticOutcome.AVAILABLE
            is CapabilityOperationResult.Unavailable -> CapabilityDiagnosticOutcome.UNAVAILABLE
            is CapabilityOperationResult.Denied -> CapabilityDiagnosticOutcome.DENIED
            CapabilityOperationResult.Closed -> CapabilityDiagnosticOutcome.CLOSED
            CapabilityOperationResult.Cancelled -> CapabilityDiagnosticOutcome.CANCELLED
            is CapabilityOperationResult.Failed -> CapabilityDiagnosticOutcome.FAILED
        }
    }
}

sealed interface CapabilityScopeTerminationResult {
    data object Revoked : CapabilityScopeTerminationResult
    data object AlreadyClosed : CapabilityScopeTerminationResult
    data class CleanupFailed(val reason: CapabilityFailureReason) : CapabilityScopeTerminationResult
}
