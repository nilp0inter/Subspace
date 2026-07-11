package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.bluetooth.SleepwalkerConnectionResult
import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRejectionReason
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceChannelCapabilityHostTest {
    @Test
    fun immediateAcquisitionIsRecoverableWhileTheLinkIsDisconnectedOrConnecting() = runTest {
        val connection = ControllableConnection()
        val host = host(service(connection) { error("immediate acquisition must not connect") })
        runCurrent()

        val disconnected = host.acquire(identity("disconnected"), CapabilityKey.TextOutput)

        connection.setState(KeyboardConnectionState.Connecting)
        runCurrent()
        val connecting = host.acquire(identity("connecting"), CapabilityKey.TextOutput)

        assertTrue(disconnected is HostedCapabilityAcquisition.Recoverable)
        assertTrue(connecting is HostedCapabilityAcquisition.Recoverable)
    }

    @Test
    fun immediateAcquisitionIsUnavailableAfterTheTextOutputHostCloses() = runTest {
        val connection = ControllableConnection().apply { setState(KeyboardConnectionState.Connected) }
        val output = service(connection) { error("closed service must not connect") }
        val host = host(output)
        runCurrent()

        output.close()

        assertTrue(
            host.acquire(identity("closed"), CapabilityKey.TextOutput) is HostedCapabilityAcquisition.Unavailable,
        )
    }

    @Test
    fun preparationReturnsAvailableOnlyAfterCurrentConnectionProofAndBindsTheRequestingInstance() = runTest {
        val connection = ControllableConnection()
        var connectCalls = 0
        val host = host(service(connection) {
            connectCalls += 1
            connection.setState(KeyboardConnectionState.Connected)
            SleepwalkerConnectionResult.Connected
        })
        val requestingIdentity = identity("requesting-instance")

        val acquired = host.prepareAndAcquire(requestingIdentity, CapabilityKey.TextOutput, 50)
        val port = availablePort(acquired)
        val outcome = port.sendText(TextOutputRequest("", TextOutputProfile("linux:us")))

        assertEquals(1, connectCalls)
        assertEquals(
            TextDeliveryOutcome.Rejected(
                "requesting-instance:1",
                TextOutputRejectionReason.EMPTY_TEXT,
            ),
            outcome,
        )
    }

    @Test
    fun failedTimedOutAndStaleConnectedPreparationNeverReturnAvailable() = runTest {
        val failed = host(
            service(ControllableConnection()) { SleepwalkerConnectionResult.Failed("adapter rejected connection") },
        ).prepareAndAcquire(identity("failed"), CapabilityKey.TextOutput, 50)

        val stalledConnection = ControllableConnection()
        val stalled = CompletableDeferred<SleepwalkerConnectionResult>()
        val timedOutHost = host(
            service(stalledConnection, preparationTimeoutMs = 25) { stalled.await() },
        )
        val timedOut = async {
            timedOutHost.prepareAndAcquire(identity("timed-out"), CapabilityKey.TextOutput, 25)
        }
        runCurrent()
        advanceTimeBy(25)

        val raceConnection = ControllableConnection()
        val staleConnected = host(service(raceConnection) {
            raceConnection.setState(KeyboardConnectionState.Connected)
            raceConnection.setState(KeyboardConnectionState.Disconnected)
            SleepwalkerConnectionResult.Connected
        }).prepareAndAcquire(identity("stale-connected"), CapabilityKey.TextOutput, 50)

        assertNotAvailable(failed)
        assertNotAvailable(timedOut.await())
        assertNotAvailable(staleConnected)
    }

    @Test
    fun connectedImmediateAcquisitionIsAvailableWithoutStartingPreparation() = runTest {
        val connection = ControllableConnection().apply { setState(KeyboardConnectionState.Connected) }
        var connectCalls = 0
        val host = host(service(connection) {
            connectCalls += 1
            error("connected acquisition must not start preparation")
        })
        runCurrent()

        val acquisition = host.acquire(identity("already-connected"), CapabilityKey.TextOutput)

        assertTrue(acquisition is HostedCapabilityAcquisition.Available)
        assertEquals(0, connectCalls)
    }

    private fun TestScope.service(
        connection: ControllableConnection,
        preparationTimeoutMs: Long = 1_000,
        connect: suspend () -> SleepwalkerConnectionResult,
    ): SleepwalkerTextOutputService = SleepwalkerTextOutputService(
        scope = backgroundScope,
        connection = connection,
        hid = LowLevelHidImpl(),
        keymapDatabase = SeedKeymapDatabase,
        connect = { connect() },
        preparationTimeoutMs = preparationTimeoutMs,
        deliveryTimeoutMs = 1_000,
    )

    private fun host(textOutputService: SleepwalkerTextOutputService): ServiceChannelCapabilityHost =
        ServiceChannelCapabilityHost(
            textOutputService = textOutputService,
            transcription = { null },
            synthesis = { null },
            audioOperation = { null },
            journal = { null },
        )

    private fun identity(instanceId: String): CapabilityScopeIdentity =
        CapabilityScopeIdentity(instanceId, RuntimeGeneration(0))

    private fun availablePort(
        acquisition: HostedCapabilityAcquisition<TextOutputCapability>,
    ): TextOutputCapability {
        assertTrue(acquisition is HostedCapabilityAcquisition.Available)
        return (acquisition as HostedCapabilityAcquisition.Available).port
    }

    private fun assertNotAvailable(acquisition: HostedCapabilityAcquisition<TextOutputCapability>) {
        assertFalse(acquisition is HostedCapabilityAcquisition.Available)
    }

    private class ControllableConnection : SleepwalkerBleConnection() {
        fun setState(state: KeyboardConnectionState) {
            _connectionState.value = state
        }
    }
}
