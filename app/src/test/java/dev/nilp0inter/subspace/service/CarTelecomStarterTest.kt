package dev.nilp0inter.subspace.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarTelecomStarterTest {
    @Test
    fun successfulHfpPrimeStopsExactCarAfterConnectAndWaitsForDisconnect() = runTest {
        val car = TestDevice("car")
        val events = mutableListOf<String>()
        val startedDevices = mutableListOf<TestDevice>()
        val observedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()
        var audioConnected = false

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = { device ->
                    startedDevices += device
                    events += "start"
                    true
                },
                isAudioConnected = { device ->
                    observedDevices += device
                    when {
                        audioConnected && "connected" !in events -> events += "connected"
                        !audioConnected && "stop" in events && "disconnected" !in events -> events += "disconnected"
                    }
                    audioConnected
                },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    events += "stop"
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        assertEquals(listOf("start"), events)
        assertFalse(priming.isCompleted)

        audioConnected = true
        advanceTimeBy(POLL_MS)
        runCurrent()
        assertEquals(listOf("start", "connected", "stop"), events)
        assertFalse("must retain the handoff until HFP audio disconnects", priming.isCompleted)

        audioConnected = false
        advanceTimeBy(POLL_MS)
        runCurrent()

        assertTrue(priming.await())
        assertEquals(listOf("start", "connected", "stop", "disconnected"), events)
        assertEquals(1, startedDevices.size)
        assertSame(car, startedDevices.single())
        assertTrue(observedDevices.all { it === car })
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun disconnectTimeoutFailsAfterStoppingExactConnectedCar() = runTest {
        val car = TestDevice("car")
        val events = mutableListOf<String>()
        val stoppedDevices = mutableListOf<TestDevice>()
        var audioConnected = false

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = {
                    events += "start"
                    true
                },
                isAudioConnected = {
                    if (audioConnected && "connected" !in events) events += "connected"
                    audioConnected
                },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    events += "stop"
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        audioConnected = true
        advanceTimeBy(POLL_MS)
        runCurrent()
        assertEquals(listOf("start", "connected", "stop"), events)
        assertFalse(priming.isCompleted)

        advanceTimeBy(TIMEOUT_MS)
        runCurrent()

        assertFalse(priming.await())
        assertEquals(listOf("start", "connected", "stop"), events)
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun timedOutHfpPrimeStopsTheExactStartedDevice() = runTest {
        val car = TestDevice("car")
        val startedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = { device ->
                    startedDevices += device
                    true
                },
                isAudioConnected = { false },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        assertFalse(priming.isCompleted)
        assertTrue(stoppedDevices.isEmpty())

        advanceTimeBy(TIMEOUT_MS)
        runCurrent()

        assertFalse(priming.await())
        assertEquals(1, startedDevices.size)
        assertSame(car, startedDevices.single())
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun cancelledHfpPrimeStopsTheExactStartedDevice() = runTest {
        val car = TestDevice("car")
        val startedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = { device ->
                    startedDevices += device
                    true
                },
                isAudioConnected = { false },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        priming.cancelAndJoin()

        assertTrue(priming.isCancelled)
        assertEquals(1, startedDevices.size)
        assertSame(car, startedDevices.single())
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    private companion object {
        const val TIMEOUT_MS = 100L
        const val POLL_MS = 10L
    }

    private data class TestDevice(val name: String)
}
