package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.AudioRecorder
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.FakeSttTranscriber
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.SttStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mutual-exclusion logic tests.
 *
 * The real `PttForegroundService` requires Android context; these tests
 * exercise the same mutual-exclusion rule the service enforces: at most one of
 * echo and STT can be enabled at a time, and enabling one cancels the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SttEchoMutualExclusionTest {
    @Test
    fun enablingSttDisablesAndCancelsEcho() = runTest {
        val echo = EchoController(this, FakeSco(), FakeRecorder(), FakeOutput())
        val stt = SttController(
            this,
            FakeSco(),
            FakeRecorder(),
            FakeOutput(),
            TranscriptionService(FakeSttTranscriber()),
        )
        echo.setEnabled(true)
        assertTrue(echo.enabled)

        // Mirror PttForegroundService.setSttEnabled(true) logic.
        if (echo.enabled) {
            echo.setEnabled(false)
            echo.cancelAndRelease()
        }
        stt.setEnabled(true)

        assertFalse(echo.enabled)
        assertTrue(stt.enabled)
        assertTrue(echo.status.value == EchoStatus.Idle)
    }

    @Test
    fun enablingEchoDisablesAndCancelsStt() = runTest {
        val echo = EchoController(this, FakeSco(), FakeRecorder(), FakeOutput())
        val stt = SttController(
            this,
            FakeSco(),
            FakeRecorder(),
            FakeOutput(),
            TranscriptionService(FakeSttTranscriber()),
        )
        stt.setEnabled(true)
        assertTrue(stt.enabled)

        // Mirror PttForegroundService.setEchoEnabled(true) logic.
        if (stt.enabled) {
            stt.setEnabled(false)
            stt.cancelAndRelease()
        }
        echo.setEnabled(true)

        assertFalse(stt.enabled)
        assertTrue(echo.enabled)
        assertTrue(stt.status.value == SttStatus.Idle)
    }

    @Test
    fun disablingBothLeavesBothOff() = runTest {
        val echo = EchoController(this, FakeSco(), FakeRecorder(), FakeOutput())
        val stt = SttController(
            this,
            FakeSco(),
            FakeRecorder(),
            FakeOutput(),
            TranscriptionService(FakeSttTranscriber()),
        )
        stt.setEnabled(true)
        stt.setEnabled(false)
        echo.setEnabled(false)

        assertFalse(stt.enabled)
        assertFalse(echo.enabled)
    }

    private class FakeSco : ScoRoute {
        override val state: StateFlow<ScoState> = MutableStateFlow(ScoState.Inactive)
        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean = true
        override fun isActive(): Boolean = false
        override fun release() {}
    }

    private class FakeRecorder : AudioRecorder {
        override val isActive: Boolean = false
        override suspend fun start(): Boolean = true
        override fun stopIfActiveOrEmpty(): RecordedPcm = RecordedPcm(shortArrayOf(), 16_000)
    }

    private class FakeOutput : PcmOutput {
        override suspend fun playReadyBeep() {}
        override suspend fun play(recording: RecordedPcm) {}
    }
}
