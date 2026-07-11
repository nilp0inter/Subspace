package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.TtsStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TtsControllerTest {
    @Test
    fun successfulDiagnosticSynthesisPlaysRenderedAudioAndReleasesItsRoute() = runTest {
        val output = RecordingOutput()
        val controller = controller(
            scope = this,
            output = output,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f, -0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, output.played.size)
        assertFalse(output.played.single().isEmpty)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun diagnosticSynthesisFailureSurfacesHostErrorWithoutPlayback() = runTest {
        val output = RecordingOutput()
        val controller = controller(
            scope = this,
            output = output,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Failure("model unavailable"))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(TtsStatus.Error("model unavailable"), controller.status.value)
        assertTrue(output.played.isEmpty())
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun cancellingDiagnosticPlaybackReturnsIdleAndReleasesRouteOnce() = runTest {
        val output = RecordingOutput(playbackStarted = CompletableDeferred())
        val controller = controller(
            scope = this,
            output = output,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        assertTrue(output.playbackStarted!!.isCompleted)
        assertEquals(TtsStatus.Playing, controller.status.value)

        controller.cancelAndRelease()
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, output.played.size)
        assertEquals(1, output.releaseRouteCount)
    }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        output: RecordingOutput,
        synthesizer: TtsSynthesizer,
        dispatcher: CoroutineDispatcher,
    ) = TtsController(
        scope = scope,
        sco = AvailableScoRoute(),
        output = output,
        synthesizer = synthesizer,
        synthesisDispatcher = dispatcher,
    )

    private class AvailableScoRoute : ScoRoute {
        override val endpoint = AudioRouteEndpoint.Rsm
        override val state: StateFlow<ScoState> = MutableStateFlow(ScoState.Active)
        override val coldStart = false

        override fun hasAvailableScoDevice() = true
        override suspend fun acquire() = true
        override fun isActive() = true
        override fun release() = Unit
    }

    private class RecordingOutput(
        val playbackStarted: CompletableDeferred<Unit>? = null,
    ) : PcmOutput {
        val played = mutableListOf<RecordedPcm>()
        var releaseRouteCount = 0
            private set

        override suspend fun playErrorBeep(coldStart: Boolean) = Unit
        override suspend fun playReadyBeep(coldStart: Boolean) = Unit
        override suspend fun play(recording: RecordedPcm) {
            played += recording
            playbackStarted?.complete(Unit)
            if (playbackStarted != null) awaitCancellation()
        }
        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }
}
