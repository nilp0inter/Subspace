package dev.nilp0inter.subspace.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CarTelecomStarterTest {
    @Test
    fun timedOutHfpPrimeStopsTheExactStartedDevice() = runTest {
        val car = TestDevice("car")
        val startedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()

        val primed = primeHfpDeviceForTelecom(
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
            timeoutMs = 100L,
            pollMs = 10L,
        )

        assertFalse(primed)
        assertEquals(listOf(car), startedDevices)
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    private data class TestDevice(val name: String)
}
