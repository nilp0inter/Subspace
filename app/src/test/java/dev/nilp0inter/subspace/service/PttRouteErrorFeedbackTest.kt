package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.NoopCaptureSource
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttRouteErrorFeedbackTest {
    @Test
    fun routeAcquisitionFailureSkipsErrorBeepAndRouteRelease() = runTest {
        val sco = RecordingScoRoute(acquireResult = false, endpoint = AudioRouteEndpoint.Rsm)
        val output = RecordingOutput(endpoint = AudioRouteEndpoint.Rsm)
        val route = ResolvedAudioRoute(
            sco = sco,
            output = output,
            source = NoopCaptureSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )

        val played = playRouteErrorBeepIfAcquired(route)

        assertFalse(played)
        assertEquals(1, sco.acquireCount)
        assertEquals(emptyList<AudioRouteEndpoint>(), output.errorBeepEndpoints)
        assertEquals(0, output.releaseRouteCount)
    }

    @Test
    fun acquiredRoutePlaysErrorBeepOnThatRouteAndReleasesIt() = runTest {
        val sco = RecordingScoRoute(
            acquireResult = true,
            endpoint = AudioRouteEndpoint.Rsm,
            coldStart = true,
        )
        val output = RecordingOutput(endpoint = AudioRouteEndpoint.Rsm)
        val route = ResolvedAudioRoute(
            sco = sco,
            output = output,
            source = NoopCaptureSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )

        val played = playRouteErrorBeepIfAcquired(route)

        assertTrue(played)
        assertEquals(listOf(AudioRouteEndpoint.Rsm), output.errorBeepEndpoints)
        assertEquals(listOf(true), output.errorBeepColdStarts)
        assertEquals(1, output.releaseRouteCount)
    }

    private class RecordingScoRoute(
        private val acquireResult: Boolean,
        override val endpoint: AudioRouteEndpoint,
        override val coldStart: Boolean = false,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        var acquireCount = 0
            private set

        override fun hasAvailableScoDevice(): Boolean = acquireResult

        override suspend fun acquire(): Boolean {
            acquireCount += 1
            if (acquireResult) _state.value = ScoState.Active
            return acquireResult
        }

        override fun isActive(): Boolean = state.value == ScoState.Active
        override fun release() = Unit
    }

    private class RecordingOutput(
        private val endpoint: AudioRouteEndpoint,
    ) : PcmOutput {
        val errorBeepEndpoints = mutableListOf<AudioRouteEndpoint>()
        val errorBeepColdStarts = mutableListOf<Boolean>()
        var releaseRouteCount = 0
            private set

        override suspend fun playReadyBeep(coldStart: Boolean) = Unit

        override suspend fun playErrorBeep(coldStart: Boolean) {
            errorBeepEndpoints += endpoint
            errorBeepColdStarts += coldStart
        }

        override suspend fun play(recording: RecordedPcm) = Unit

        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }
}
