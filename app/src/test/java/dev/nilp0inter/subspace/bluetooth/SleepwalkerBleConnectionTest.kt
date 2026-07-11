package dev.nilp0inter.subspace.bluetooth

import android.content.Context
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepwalkerBleConnectionTest {

    @Test
    fun scanningCallersJoinOneAttemptAndReceiveItsTerminalResult() = runTest {
        val connection = ExposedSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Scanning)
        }
        val context = mockk<Context>(relaxed = true)

        val first = async {
            connection.ensureConnected(adapter = null, context = context, timeoutMs = 60_000)
        }
        val second = async {
            connection.ensureConnected(adapter = null, context = context, timeoutMs = 60_000)
        }
        runCurrent()

        connection.disconnect()

        val expected = SleepwalkerConnectionResult.Failed("Sleepwalker bridge disconnected")
        assertEquals(expected, first.await())
        assertEquals(expected, second.await())
        assertEquals(KeyboardConnectionState.Disconnected, connection.connectionState.value)
    }

    @Test
    fun cancellingOneScanningWaiterDoesNotCancelTheSharedAttempt() = runTest {
        val connection = ExposedSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Scanning)
        }
        val context = mockk<Context>(relaxed = true)

        val cancelledWaiter = async {
            connection.ensureConnected(adapter = null, context = context, timeoutMs = 60_000)
        }
        val remainingWaiter = async {
            connection.ensureConnected(adapter = null, context = context, timeoutMs = 60_000)
        }
        runCurrent()

        cancelledWaiter.cancelAndJoin()
        connection.disconnect()

        assertTrue(cancelledWaiter.isCancelled)
        assertEquals(
            SleepwalkerConnectionResult.Failed("Sleepwalker bridge disconnected"),
            remainingWaiter.await(),
        )
        assertEquals(KeyboardConnectionState.Disconnected, connection.connectionState.value)
    }

    @Test
    fun scanningAttemptTimesOutAndRestoresDisconnectedState() = runTest {
        val connection = ExposedSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Scanning)
        }
        val context = mockk<Context>(relaxed = true)

        assertEquals(
            SleepwalkerConnectionResult.TimedOut,
            connection.ensureConnected(adapter = null, context = context, timeoutMs = 0),
        )
        assertEquals(KeyboardConnectionState.Disconnected, connection.connectionState.value)
    }

    @Test
    fun connectedBridgeReturnsImmediatelyWithoutAnAdapter() = runTest {
        val connection = ExposedSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val context = mockk<Context>(relaxed = true)

        assertEquals(
            SleepwalkerConnectionResult.Connected,
            connection.ensureConnected(adapter = null, context = context, timeoutMs = 60_000),
        )
        assertEquals(KeyboardConnectionState.Connected, connection.connectionState.value)
    }

    private class ExposedSleepwalkerBleConnection : SleepwalkerBleConnection() {
        fun setConnectionState(state: KeyboardConnectionState) {
            _connectionState.value = state
        }
    }
}
