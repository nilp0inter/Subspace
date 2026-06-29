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
    fun selectsScoRouteWhenScoDeviceIsAvailable() = runTest {
        val scoRoute = FakeScoRoute(hasDevice = true, isActive = false)
        val scoOutput = FakeOutput()
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)
        val localOutput = FakeOutput()
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoSource, localOutput, localSource)

        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoSource, resolved.source)
        // The resolved output delegates to the SCO output (now wrapped in
        // ScopedPcmOutput); verify by observing a delegated `play` call.
        resolved.output.play(RecordedPcm(shortArrayOf(1), 16_000))
        assertEquals(1, scoOutput.playCount)
        assertEquals(0, localOutput.playCount)
    }

    @Test
    fun selectsScoRouteWhenScoIsAlreadyActive() = runTest {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = true)
        val scoOutput = FakeOutput()
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)
        val localOutput = FakeOutput()
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoSource, localOutput, localSource)

        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoSource, resolved.source)
        resolved.output.play(RecordedPcm(shortArrayOf(1), 16_000))
        assertEquals(1, scoOutput.playCount)
        assertEquals(0, localOutput.playCount)
    }

    @Test
    fun selectsLocalRouteWhenScoIsUnavailable() = runTest {
        val scoRoute = FakeScoRoute(hasDevice = false, isActive = false)
        val scoOutput = FakeOutput()
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)
        val localOutput = FakeOutput()
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveAudioRoute(scoRoute, scoOutput, scoSource, localOutput, localSource)

        assertTrue(resolved.sco is NoopScoRoute)
        assertEquals(localSource, resolved.source)
        resolved.output.play(RecordedPcm(shortArrayOf(1), 16_000))
        assertEquals(0, scoOutput.playCount)
        assertEquals(1, localOutput.playCount)
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

    @Test
    fun scoBranchReleaseRouteReleasesTheScoRoute() = runTest {
        val scoRoute = RecordingFakeScoRoute()
        val scoOutput = FakeOutput()
        val resolved = resolveAudioRoute(
            scoRoute,
            scoOutput,
            CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
            FakeOutput(),
            CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic),
        )

        resolved.output.releaseRoute()

        assertEquals(
            "SCO branch: output.releaseRoute() must call scoRoute.release()",
            1,
            scoRoute.releaseCount,
        )
    }

    @Test
    fun localBranchReleaseRouteIsNoOpAndDoesNotTouchAnyRoute() = runTest {
        val scoRoute = RecordingFakeScoRoute(hasDevice = false, isActive = false)
        val resolved = resolveAudioRoute(
            scoRoute,
            FakeOutput(),
            CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
            FakeOutput(),
            CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic),
        )

        resolved.output.releaseRoute()

        assertEquals(
            "Local branch: output.releaseRoute() must not release any route",
            0,
            scoRoute.releaseCount,
        )
    }

    private class RecordingFakeScoRoute(
        private val hasDevice: Boolean = true,
        private val isActive: Boolean = false,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(
            if (isActive) ScoState.Active else ScoState.Inactive
        )
        override val state: StateFlow<ScoState> = _state
        override val coldStart: Boolean = false
        var releaseCount: Int = 0; private set

        override fun hasAvailableScoDevice(): Boolean = hasDevice
        override suspend fun acquire(): Boolean = true
        override fun isActive(): Boolean = isActive
        override fun release() { releaseCount += 1 }
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
        var playCount: Int = 0; private set

        override suspend fun playReadyBeep(coldStart: Boolean) {}
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) { playCount += 1 }
    }
}