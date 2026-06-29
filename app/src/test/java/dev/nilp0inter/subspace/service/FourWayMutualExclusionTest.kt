package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.FakeSttTranscriber
import dev.nilp0inter.subspace.audio.FakeTtsSynthesizer
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SynthesisOutcome
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.SttStatus
import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TtsStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4-way mutual exclusion logic tests.
 *
 * Mirrors the rule `PttForegroundService` enforces: at most one of echo, STT,
 * TTS, and STT↔TTS can be enabled at a time. Enabling one disables and
 * cancels the other three.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FourWayMutualExclusionTest {
    @Test
    fun enablingTtsDisablesEchoSttAndSttTts() = runTest {
        val echo = newEcho()
        val stt = newStt()
        val tts = TtsController(this, FakeSco(), FakeOutput(), FakeTtsSynthesizer())
        val sttTts = newSttTts()

        echo.setEnabled(true)
        stt.setEnabled(true)
        sttTts.setEnabled(true)
        assertTrue(echo.enabled)
        assertTrue(stt.enabled)
        assertTrue(sttTts.enabled)

        // Mirror PttForegroundService.setTtsEnabled(true) logic.
        if (echo.enabled) { echo.setEnabled(false); echo.cancelAndRelease() }
        if (stt.enabled) { stt.setEnabled(false); stt.cancelAndRelease() }
        if (sttTts.enabled) { sttTts.setEnabled(false); sttTts.cancelAndRelease() }
        tts.setEnabled(true)

        assertFalse(echo.enabled)
        assertFalse(stt.enabled)
        assertFalse(sttTts.enabled)
        assertTrue(tts.enabled)
        assertTrue(echo.status.value == EchoStatus.Idle)
        assertTrue(stt.status.value == SttStatus.Idle)
        assertTrue(sttTts.status.value == SttTtsStatus.Idle)
    }

    @Test
    fun enablingSttTtsDisablesEchoSttAndTts() = runTest {
        val echo = newEcho()
        val stt = newStt()
        val tts = TtsController(this, FakeSco(), FakeOutput(), FakeTtsSynthesizer())
        val sttTts = newSttTts()

        echo.setEnabled(true)
        stt.setEnabled(true)
        tts.setEnabled(true)

        if (echo.enabled) { echo.setEnabled(false); echo.cancelAndRelease() }
        if (stt.enabled) { stt.setEnabled(false); stt.cancelAndRelease() }
        if (tts.enabled) { tts.setEnabled(false); tts.cancelAndRelease() }
        sttTts.setEnabled(true)

        assertFalse(echo.enabled)
        assertFalse(stt.enabled)
        assertFalse(tts.enabled)
        assertTrue(sttTts.enabled)
    }

    @Test
    fun enablingEchoDisablesSttTtsAndSttTts() = runTest {
        val echo = newEcho()
        val stt = newStt()
        val tts = TtsController(this, FakeSco(), FakeOutput(), FakeTtsSynthesizer())
        val sttTts = newSttTts()

        stt.setEnabled(true)
        tts.setEnabled(true)
        sttTts.setEnabled(true)

        if (stt.enabled) { stt.setEnabled(false); stt.cancelAndRelease() }
        if (tts.enabled) { tts.setEnabled(false); tts.cancelAndRelease() }
        if (sttTts.enabled) { sttTts.setEnabled(false); sttTts.cancelAndRelease() }
        echo.setEnabled(true)

        assertFalse(stt.enabled)
        assertFalse(tts.enabled)
        assertFalse(sttTts.enabled)
        assertTrue(echo.enabled)
    }

    @Test
    fun enablingSttDisablesEchoTtsAndSttTts() = runTest {
        val echo = newEcho()
        val stt = newStt()
        val tts = TtsController(this, FakeSco(), FakeOutput(), FakeTtsSynthesizer())
        val sttTts = newSttTts()

        echo.setEnabled(true)
        tts.setEnabled(true)
        sttTts.setEnabled(true)

        if (echo.enabled) { echo.setEnabled(false); echo.cancelAndRelease() }
        if (tts.enabled) { tts.setEnabled(false); tts.cancelAndRelease() }
        if (sttTts.enabled) { sttTts.setEnabled(false); sttTts.cancelAndRelease() }
        stt.setEnabled(true)

        assertFalse(echo.enabled)
        assertFalse(tts.enabled)
        assertFalse(sttTts.enabled)
        assertTrue(stt.enabled)
    }

    @Test
    fun disablingAllLeavesAllOff() = runTest {
        val echo = newEcho()
        val stt = newStt()
        val tts = TtsController(this, FakeSco(), FakeOutput(), FakeTtsSynthesizer())
        val sttTts = newSttTts()

        echo.setEnabled(true)
        echo.setEnabled(false)
        stt.setEnabled(false)
        tts.setEnabled(false)
        sttTts.setEnabled(false)

        assertFalse(echo.enabled)
        assertFalse(stt.enabled)
        assertFalse(tts.enabled)
        assertFalse(sttTts.enabled)
    }

    private fun kotlinx.coroutines.test.TestScope.newEcho(): EchoController {
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        return EchoController(this, FakeSco(), captureService, source, FakeOutput())
    }

    private fun kotlinx.coroutines.test.TestScope.newStt(): SttController {
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        return SttController(this, FakeSco(), captureService, source, FakeOutput(), TranscriptionService(FakeSttTranscriber()))
    }

    private fun kotlinx.coroutines.test.TestScope.newSttTts(): SttTtsController {
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        return SttTtsController(this, FakeSco(), captureService, source, FakeOutput(), FakeSttTranscriber(), FakeTtsSynthesizer())
    }

    private class FakeSco : ScoRoute {
        override val state: StateFlow<ScoState> = MutableStateFlow(ScoState.Inactive)
        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean = true
        override fun isActive(): Boolean = false
        override fun release() {}
    }

    private class FakeOutput : PcmOutput {
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun playReadyBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) {}
    }
}
