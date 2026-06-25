package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteResolverTest {

    @Test
    fun selectsScoRouteWhenScoDeviceIsAvailable() {
        val scoRoute = FakeScoRoute(hasDevice = true, isActive = false)
        val scoOutput = FakeOutput()
        val scoRecorder = FakeRecorder()
        val localOutput = FakeOutput()
        val localRecorder = FakeRecorder()

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoRecorder, localOutput, localRecorder)

        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoOutput, resolved.output)
        assertEquals(scoRecorder, resolved.recorder)
    }

    @Test
    fun selectsScoRouteWhenScoIsAlreadyActive() {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = true)
        val scoOutput = FakeOutput()
        val scoRecorder = FakeRecorder()
        val localOutput = FakeOutput()
        val localRecorder = FakeRecorder()

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoRecorder, localOutput, localRecorder)

        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoOutput, resolved.output)
        assertEquals(scoRecorder, resolved.recorder)
    }

    @Test
    fun selectsLocalRouteWhenScoIsUnavailable() {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = false)
        val scoOutput = FakeOutput()
        val scoRecorder = FakeRecorder()
        val localOutput = FakeOutput()
        val localRecorder = FakeRecorder()

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoRecorder, localOutput, localRecorder)

        assertTrue(resolved.sco is NoopScoRoute)
        assertEquals(localOutput, resolved.output)
        assertEquals(localRecorder, resolved.recorder)
    }

    @Test
    fun localRouteScoIsNoopAndDoesNotRequireBluetooth() = runTest {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = false)
        val resolved = resolveAudioRoute(
            scoRoute,
            FakeOutput(),
            FakeRecorder(),
            FakeOutput(),
            FakeRecorder(),
        )

        assertTrue(resolved.sco.acquire())
        assertTrue(resolved.sco.isActive())
        assertFalse(resolved.sco.hasAvailableScoDevice())
    }

    private class FakeScoRoute(
        private val hasDevice: Boolean,
        private val isActive: Boolean,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(
            if (isActive) ScoState.Active else ScoState.Inactive
        )
        override val state: StateFlow<ScoState> = _state
        override val coldStart: Boolean = false

        override fun hasAvailableScoDevice(): Boolean = hasDevice
        override suspend fun acquire(): Boolean = true
        override fun isActive(): Boolean = isActive
        override fun release() {}
    }

    private class FakeOutput : PcmOutput {
        override suspend fun playReadyBeep(coldStart: Boolean) {}
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) {}
    }

    private class FakeRecorder : AudioRecorder {
        override var isActive: Boolean = false
        override suspend fun start(): Boolean = true
        override fun stopIfActiveOrEmpty(): RecordedPcm = RecordedPcm(shortArrayOf(), 16_000)
    }
}
