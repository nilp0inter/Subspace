package dev.nilp0inter.subspace.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouterTest {
    @Test
    fun activeChannelReceivesPttInsteadOfTestMode() {
        val channel = FakeRoute(enabled = true)
        val echo = FakeRoute(enabled = true)
        val router = AudioRouter(channel.route(), mapOf(AudioTestMode.Echo to echo.route()))

        router.onPttPressed()
        router.onPttReleased()

        assertEquals(1, channel.pressCount)
        assertEquals(1, channel.releaseCount)
        assertEquals(0, echo.pressCount)
        assertEquals(0, echo.releaseCount)
    }

    @Test
    fun activatingChannelDisablesAndCancelsTestModes() {
        val channel = FakeRoute()
        val echo = FakeRoute(enabled = true)
        val stt = FakeRoute(enabled = true)
        val router = AudioRouter(
            channel.route(),
            mapOf(AudioTestMode.Echo to echo.route(), AudioTestMode.Stt to stt.route()),
        )

        router.setChannelEnabled(true)

        assertTrue(channel.enabled)
        assertFalse(echo.enabled)
        assertFalse(stt.enabled)
        assertEquals(1, echo.cancelCount)
        assertEquals(1, stt.cancelCount)
    }

    @Test
    fun activatingTestModeDisablesAndCancelsChannelAndOtherTests() {
        val channel = FakeRoute(enabled = true)
        val echo = FakeRoute(enabled = true)
        val stt = FakeRoute()
        val router = AudioRouter(
            channel.route(),
            mapOf(AudioTestMode.Echo to echo.route(), AudioTestMode.Stt to stt.route()),
        )

        router.setTestModeEnabled(AudioTestMode.Stt, true)

        assertFalse(channel.enabled)
        assertFalse(echo.enabled)
        assertTrue(stt.enabled)
        assertEquals(1, channel.cancelCount)
        assertEquals(1, echo.cancelCount)
    }

    private class FakeRoute(enabled: Boolean = false) {
        var enabled = enabled
        var cancelCount = 0
        var pressCount = 0
        var releaseCount = 0

        fun route(): AudioRoute = AudioRoute(
            enabled = { enabled },
            setEnabled = { enabled = it },
            cancel = { cancelCount += 1 },
            onPttPressed = { pressCount += 1 },
            onPttReleased = { releaseCount += 1 },
        )
    }
}
