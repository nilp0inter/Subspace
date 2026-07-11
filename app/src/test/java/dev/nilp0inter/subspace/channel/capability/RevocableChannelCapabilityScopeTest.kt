package dev.nilp0inter.subspace.channel.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevocableChannelCapabilityScopeTest {

    @Test
    fun declaredCapabilityAcquisitionBindsTheRequestingScopeAndPermitsItsSemanticEffect() = runTest {
        val identity = identity("journal", 4)
        val port = RecordingTextOutput()
        val host = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> CapabilityAvailability.Available },
            acquireHandler = { requestedIdentity, key ->
                assertEquals(identity, requestedIdentity)
                assertEquals(CapabilityKey.TextOutput, key)
                available(port)
            },
            prepareHandler = { _, _, _ -> error("preparation was not requested") },
        )
        val scope = scope(identity, setOf(ChannelCapability.TextOutput), host)

        val lease = scope.acquireTextLease()
        val result = lease.sendText("record this")

        assertEquals(CapabilityOperationResult.Success(Unit), result)
        assertEquals(1, port.sendCount)
        assertEquals(listOf(identity), host.acquireIdentities)
        assertEquals(CapabilityLeaseState.ACTIVE, lease.state)
    }

    @Test
    fun undeclaredCapabilityIsDeniedWithoutConsultingTheHost() = runTest {
        val host = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> error("undeclared access reached host") },
            acquireHandler = { _, _ -> error("undeclared access reached host") },
            prepareHandler = { _, _, _ -> error("undeclared access reached host") },
        )
        val scope = scope(identity("journal", 0), setOf(ChannelCapability.Journal), host)

        val availability = scope.availability(CapabilityKey.TextOutput)
        val acquisition = scope.acquire(CapabilityKey.TextOutput)

        assertEquals(
            CapabilityAvailabilityResult.Denied(CapabilityDeniedReason.UNDECLARED),
            availability,
        )
        assertEquals(
            CapabilityAcquisition.Denied(CapabilityDeniedReason.UNDECLARED),
            acquisition,
        )
        assertTrue(host.availabilityIdentities.isEmpty())
        assertTrue(host.acquireIdentities.isEmpty())
        assertTrue(host.prepareRequests.isEmpty())
    }

    @Test
    fun missingUnavailableAndRecoverableResourcesRemainTypedAndPreparationReceivesTheHostDeadline() = runTest {
        val missingScope = scope(
            identity("missing", 0),
            setOf(ChannelCapability.TextOutput),
            RecordingCapabilityHost(
                availabilityHandler = { _, _ -> CapabilityAvailability.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED) },
                acquireHandler = { _, _ -> HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED) },
                prepareHandler = { _, _, _ -> error("missing capability must not prepare") },
            ),
        )
        val unavailableScope = scope(
            identity("unavailable", 0),
            setOf(ChannelCapability.TextOutput),
            RecordingCapabilityHost(
                availabilityHandler = { _, _ -> CapabilityAvailability.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY) },
                acquireHandler = { _, _ -> HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY) },
                prepareHandler = { _, _, _ -> error("unavailable capability must not prepare") },
            ),
        )
        val recoveredPort = RecordingTextOutput()
        val recoveryHost = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> CapabilityAvailability.Recoverable },
            acquireHandler = { _, _ -> HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.RESOURCE_BUSY) },
            prepareHandler = { _, key, timeoutMillis ->
                assertEquals(CapabilityKey.TextOutput, key)
                assertEquals(275L, timeoutMillis)
                available(recoveredPort)
            },
        )
        val recoverableScope = scope(
            identity("recoverable", 0),
            setOf(ChannelCapability.TextOutput),
            recoveryHost,
        )

        assertEquals(
            CapabilityAcquisition.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED),
            missingScope.acquire(CapabilityKey.TextOutput),
        )
        assertEquals(
            CapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY),
            unavailableScope.acquire(CapabilityKey.TextOutput),
        )
        assertEquals(
            CapabilityAcquisition.Recoverable(CapabilityUnavailableReason.RESOURCE_BUSY),
            recoverableScope.acquire(CapabilityKey.TextOutput),
        )

        val recoveredLease = recoverableScope.acquireTextLease(
            CapabilityAcquisitionPolicy.PrepareRecoverable(275),
        )
        assertEquals(listOf(Triple(identity("recoverable", 0), CapabilityKey.TextOutput, 275L)), recoveryHost.prepareRequests)
        assertEquals(CapabilityOperationResult.Success(Unit), recoveredLease.sendText("after preparation"))
        assertEquals(1, recoveredPort.sendCount)
    }

    @Test
    fun recoverablePreparationFailureRemainsSemanticAndDoesNotCreateALease() = runTest {
        val host = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> CapabilityAvailability.Recoverable },
            acquireHandler = { _, _ -> HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.RESOURCE_BUSY) },
            prepareHandler = { _, _, _ -> HostedCapabilityAcquisition.Failed(CapabilityFailureReason.PREPARATION_FAILED) },
        )
        val scope = scope(identity("keyboard", 2), setOf(ChannelCapability.TextOutput), host)

        val result = scope.acquire(
            CapabilityKey.TextOutput,
            CapabilityAcquisitionPolicy.PrepareRecoverable(31),
        )

        assertEquals(CapabilityAcquisition.Failed(CapabilityFailureReason.PREPARATION_FAILED), result)
        assertEquals(1, host.acquireIdentities.size)
        assertEquals(1, host.prepareRequests.size)
    }

    @Test
    fun separateInstancesAcquireAndUseOnlyTheirOwnHostPorts() = runTest {
        val firstPort = RecordingTextOutput()
        val secondPort = RecordingTextOutput()
        val firstIdentity = identity("first", 0)
        val secondIdentity = identity("second", 0)
        val host = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> CapabilityAvailability.Available },
            acquireHandler = { requestedIdentity, _ ->
                when (requestedIdentity) {
                    firstIdentity -> available(firstPort)
                    secondIdentity -> available(secondPort)
                    else -> error("unexpected capability identity: $requestedIdentity")
                }
            },
            prepareHandler = { _, _, _ -> error("preparation was not requested") },
        )
        val firstScope = scope(firstIdentity, setOf(ChannelCapability.TextOutput), host)
        val secondScope = scope(secondIdentity, setOf(ChannelCapability.TextOutput), host)

        assertEquals(CapabilityOperationResult.Success(Unit), firstScope.acquireTextLease().sendText("first"))
        assertEquals(CapabilityOperationResult.Success(Unit), secondScope.acquireTextLease().sendText("second"))

        assertEquals(1, firstPort.sendCount)
        assertEquals(1, secondPort.sendCount)
        assertEquals(setOf(firstIdentity, secondIdentity), host.acquireIdentities.toSet())
    }

    @Test
    fun replacementGenerationRevokesOldLeaseAndGivesTheNewGenerationAnIndependentPort() = runTest {
        val oldIdentity = identity("keyboard", 7)
        val replacementIdentity = identity("keyboard", 8)
        val oldPort = RecordingTextOutput()
        val replacementPort = RecordingTextOutput()
        val cleanupTerminations = mutableListOf<CapabilityLeaseTermination>()
        val host = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> CapabilityAvailability.Available },
            acquireHandler = { requestedIdentity, _ ->
                when (requestedIdentity) {
                    oldIdentity -> available(oldPort) { cleanupTerminations += it }
                    replacementIdentity -> available(replacementPort) { cleanupTerminations += it }
                    else -> error("unexpected generation: $requestedIdentity")
                }
            },
            prepareHandler = { _, _, _ -> error("preparation was not requested") },
        )
        val oldScope = scope(oldIdentity, setOf(ChannelCapability.TextOutput), host)
        val replacementScope = scope(replacementIdentity, setOf(ChannelCapability.TextOutput), host)
        val oldLease = oldScope.acquireTextLease()

        assertEquals(CapabilityScopeTerminationResult.Revoked, oldScope.revoke())
        assertEquals(CapabilityOperationResult.Closed, oldLease.sendText("late old generation"))
        assertEquals(CapabilityOperationResult.Success(Unit), replacementScope.acquireTextLease().sendText("new generation"))

        assertEquals(0, oldPort.sendCount)
        assertEquals(1, replacementPort.sendCount)
        assertEquals(listOf(CapabilityLeaseTermination.REVOKED), cleanupTerminations)
        assertEquals(CapabilityLeaseState.REVOKED, oldLease.state)
    }

    @Test
    fun releaseAndRevocationPerformCleanupExactlyOnceAndBlockAllLaterEffects() = runTest {
        val releasedPort = RecordingTextOutput()
        val revokedPort = RecordingTextOutput()
        val cleanupTerminations = mutableListOf<CapabilityLeaseTermination>()
        val releaseScope = scope(
            identity("release", 0),
            setOf(ChannelCapability.TextOutput),
            hostFor(releasedPort) { cleanupTerminations += it },
        )
        val revokeScope = scope(
            identity("revoke", 0),
            setOf(ChannelCapability.TextOutput),
            hostFor(revokedPort) { cleanupTerminations += it },
        )
        val releasedLease = releaseScope.acquireTextLease()
        val revokedLease = revokeScope.acquireTextLease()

        assertEquals(CapabilityReleaseResult.Released, releasedLease.release())
        assertEquals(CapabilityReleaseResult.AlreadyTerminated, releasedLease.release())
        assertEquals(CapabilityOperationResult.Closed, releasedLease.sendText("late release"))
        assertEquals(CapabilityScopeTerminationResult.Revoked, revokeScope.revoke())
        assertEquals(CapabilityScopeTerminationResult.AlreadyClosed, revokeScope.revoke())
        assertEquals(CapabilityOperationResult.Closed, revokedLease.sendText("late revoke"))

        assertEquals(
            listOf(CapabilityLeaseTermination.RELEASED, CapabilityLeaseTermination.REVOKED),
            cleanupTerminations,
        )
        assertEquals(0, releasedPort.sendCount)
        assertEquals(0, revokedPort.sendCount)
    }

    @Test
    fun cleanupExceptionIsReportedOnceWhileTheLeaseRemainsPermanentlyClosed() = runTest {
        val port = RecordingTextOutput()
        var cleanupAttempts = 0
        val diagnostics = mutableListOf<CapabilityDiagnostic>()
        val scope = scope(
            identity("cleanup", 1),
            setOf(ChannelCapability.TextOutput),
            hostFor(port) {
                cleanupAttempts += 1
                throw IllegalStateException("host cleanup failure")
            },
            diagnostics,
        )
        val lease = scope.acquireTextLease()

        assertEquals(
            CapabilityReleaseResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED),
            lease.release(),
        )
        assertEquals(CapabilityReleaseResult.AlreadyTerminated, lease.release())
        assertEquals(CapabilityOperationResult.Closed, lease.sendText("must not send"))

        assertEquals(1, cleanupAttempts)
        assertEquals(0, port.sendCount)
        assertTrue(
            diagnostics.any {
                it.phase == CapabilityDiagnosticPhase.CLEANUP &&
                    it.outcome == CapabilityDiagnosticOutcome.FAILED
            },
        )
    }

    @Test
    fun concurrentScopeRevocationAndRuntimeReleaseClaimCleanupOnlyOnce() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val cleanupTerminations = mutableListOf<CapabilityLeaseTermination>()
        val port = RecordingTextOutput()
        val scope = scope(
            identity("race", 0),
            setOf(ChannelCapability.TextOutput),
            hostFor(port) {
                cleanupTerminations += it
                cleanupStarted.complete(Unit)
                allowCleanup.await()
            },
        )
        val lease = scope.acquireTextLease()

        val revocation = async { scope.revoke() }
        cleanupStarted.await()
        val release = async { lease.release() }

        assertEquals(CapabilityReleaseResult.AlreadyTerminated, release.await())
        allowCleanup.complete(Unit)
        assertEquals(CapabilityScopeTerminationResult.Revoked, revocation.await())
        assertEquals(listOf(CapabilityLeaseTermination.REVOKED), cleanupTerminations)
        assertEquals(CapabilityOperationResult.Closed, lease.sendText("after race"))
        assertEquals(0, port.sendCount)
    }

    @Test
    fun cancellationDuringAcquisitionAndOperationIsReturnedAsTypedCancellation() = runTest {
        val cancellation = CancellationException("cancelled by host")
        val cancelledAcquisitionScope = scope(
            identity("cancelled-acquisition", 0),
            setOf(ChannelCapability.TextOutput),
            RecordingCapabilityHost(
                availabilityHandler = { _, _ -> CapabilityAvailability.Available },
                acquireHandler = { _, _ -> throw cancellation },
                prepareHandler = { _, _, _ -> throw cancellation },
            ),
        )
        val cancellingPort = RecordingTextOutput(sendFailure = cancellation)
        val cancelledOperationScope = scope(
            identity("cancelled-operation", 0),
            setOf(ChannelCapability.TextOutput),
            hostFor(cancellingPort),
        )

        assertEquals(CapabilityAcquisition.Cancelled, cancelledAcquisitionScope.acquire(CapabilityKey.TextOutput))
        assertEquals(CapabilityOperationResult.Cancelled, cancelledOperationScope.acquireTextLease().sendText("cancelled"))
        assertEquals(1, cancellingPort.sendCount)
    }

    @Test
    fun hostFailuresAreNormalizedWithoutSensitiveDiagnosticsAndDoNotBreakAnotherScope() = runTest {
        val sensitiveDetails = "audio-payload=raw-samples text=do-not-send address=AA:BB:CC:DD:EE:FF secret=token-42"
        val diagnostics = mutableListOf<CapabilityDiagnostic>()
        val healthyPort = RecordingTextOutput()
        val failedIdentity = identity("failed", 0)
        val healthyIdentity = identity("healthy", 0)
        val host = RecordingCapabilityHost(
            availabilityHandler = { _, _ -> CapabilityAvailability.Available },
            acquireHandler = { requestedIdentity, _ ->
                if (requestedIdentity == failedIdentity) {
                    throw IllegalStateException(sensitiveDetails)
                }
                available(healthyPort)
            },
            prepareHandler = { _, _, _ -> error("preparation was not requested") },
        )
        val failedScope = scope(failedIdentity, setOf(ChannelCapability.TextOutput), host, diagnostics)
        val healthyScope = scope(healthyIdentity, setOf(ChannelCapability.TextOutput), host, diagnostics)

        assertEquals(
            CapabilityAcquisition.Failed(CapabilityFailureReason.HOST_FAILURE),
            failedScope.acquire(CapabilityKey.TextOutput),
        )
        assertEquals(CapabilityOperationResult.Success(Unit), healthyScope.acquireTextLease().sendText("unrelated work"))

        assertEquals(1, healthyPort.sendCount)
        assertTrue(
            diagnostics.contains(
                CapabilityDiagnostic(
                    failedIdentity,
                    ChannelCapability.TextOutput,
                    CapabilityDiagnosticPhase.ACQUISITION,
                    CapabilityDiagnosticOutcome.FAILED,
                ),
            ),
        )
        val emittedDiagnostics = diagnostics.joinToString()
        assertFalse(emittedDiagnostics.contains("audio-payload"))
        assertFalse(emittedDiagnostics.contains("do-not-send"))
        assertFalse(emittedDiagnostics.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(emittedDiagnostics.contains("token-42"))
    }

    private fun identity(instanceId: String, generation: Long) =
        CapabilityScopeIdentity(instanceId, RuntimeGeneration(generation))

    private fun scope(
        identity: CapabilityScopeIdentity,
        declared: Set<ChannelCapability>,
        host: ChannelCapabilityHost,
        diagnostics: MutableList<CapabilityDiagnostic> = mutableListOf(),
    ) = RevocableChannelCapabilityScope(
        identity = identity,
        declaredCapabilities = declared,
        host = host,
        diagnostics = CapabilityDiagnosticSink { diagnostics += it },
    )

    private fun hostFor(
        port: TextOutputCapability,
        cleanup: suspend (CapabilityLeaseTermination) -> Unit = {},
    ) = RecordingCapabilityHost(
        availabilityHandler = { _, _ -> CapabilityAvailability.Available },
        acquireHandler = { _, _ -> available(port, cleanup) },
        prepareHandler = { _, _, _ -> error("preparation was not requested") },
    )

    private fun available(
        port: TextOutputCapability,
        cleanup: suspend (CapabilityLeaseTermination) -> Unit = {},
    ) = HostedCapabilityAcquisition.Available(port, cleanup)

    private suspend fun ChannelCapabilityScope.acquireTextLease(
        policy: CapabilityAcquisitionPolicy = CapabilityAcquisitionPolicy.Immediate,
    ): CapabilityLease<TextOutputCapability> {
        val acquisition = acquire(CapabilityKey.TextOutput, policy)
        assertTrue("expected available text-output lease, got $acquisition", acquisition is CapabilityAcquisition.Available)
        return (acquisition as CapabilityAcquisition.Available).lease
    }

    private suspend fun CapabilityLease<TextOutputCapability>.sendText(text: String): CapabilityOperationResult<Unit> =
        use { port ->
            port.sendText(TextOutputRequest(text, TextOutputProfile("test-profile")))
            CapabilityOperationResult.Success(Unit)
        }

    private class RecordingTextOutput(
        private val sendFailure: Exception? = null,
    ) : TextOutputCapability {
        var sendCount = 0
            private set

        override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
            sendCount += 1
            sendFailure?.let { throw it }
            return TextDeliveryOutcome.Delivered("operation-$sendCount")
        }

        override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome =
            TextDeliveryOutcome.Delivered("key-$sendCount")
    }

    private class RecordingCapabilityHost(
        private val availabilityHandler: suspend (CapabilityScopeIdentity, CapabilityKey<*>) -> CapabilityAvailability,
        private val acquireHandler: suspend (CapabilityScopeIdentity, CapabilityKey<*>) -> HostedCapabilityAcquisition<TextOutputCapability>,
        private val prepareHandler: suspend (CapabilityScopeIdentity, CapabilityKey<*>, Long) -> HostedCapabilityAcquisition<TextOutputCapability>,
    ) : ChannelCapabilityHost {
        val availabilityIdentities = mutableListOf<CapabilityScopeIdentity>()
        val acquireIdentities = mutableListOf<CapabilityScopeIdentity>()
        val prepareRequests = mutableListOf<Triple<CapabilityScopeIdentity, CapabilityKey<*>, Long>>()

        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability {
            availabilityIdentities += identity
            return availabilityHandler(identity, key)
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> {
            acquireIdentities += identity
            return acquireHandler(identity, key) as HostedCapabilityAcquisition<T>
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> {
            prepareRequests += Triple(identity, key, timeoutMillis)
            return prepareHandler(identity, key, timeoutMillis) as HostedCapabilityAcquisition<T>
        }
    }
}
