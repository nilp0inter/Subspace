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
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)
        val localOutput = FakeOutput()
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoSource, localOutput, localSource)

        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoOutput, resolved.output)
        assertEquals(scoSource, resolved.source)
    }

    @Test
    fun selectsScoRouteWhenScoIsAlreadyActive() {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = true)
        val scoOutput = FakeOutput()
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)
        val localOutput = FakeOutput()
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoSource, localOutput, localSource)

        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoOutput, resolved.output)
        assertEquals(scoSource, resolved.source)
    }

    @Test
    fun selectsLocalRouteWhenScoIsUnavailable() {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = false)
        val scoOutput = FakeOutput()
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)
        val localOutput = FakeOutput()
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoSource, localOutput, localSource)

        assertTrue(resolved.sco is NoopScoRoute)
        assertEquals(localOutput, resolved.output)
        assertEquals(localSource, resolved.source)
    }

    @Test
    fun localRouteScoIsNoopAndDoesNotRequireBluetooth() = runTest {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = false)
        val resolved = resolveAudioRoute(
            scoRoute,
            FakeOutput(),
            CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
            FakeOutput(),
            CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic),
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
}