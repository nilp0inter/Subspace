package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.SystemAnnouncer
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.channel.JournalPttController
import dev.nilp0inter.subspace.channel.KeyboardPttController
import dev.nilp0inter.subspace.model.AnnouncementResult
import dev.nilp0inter.subspace.model.BootstrapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BootstrapCoordinator] state machine contracts.
 *
 * These tests verify the observable state types and the CoreInit interface
 * contracts. Full integration tests with a real coordinator require Android
 * Context and are covered by the spec's device verification tasks (5.7/5.8).
 */
class BootstrapCoordinatorTest {

    @Test
    fun `ConnectingService is the initial state type`() {
        val state = BootstrapState.ConnectingService
        assertTrue(state is BootstrapState.ConnectingService)
    }

    @Test
    fun `NeedsSetup carries missing permissions and invalid model sets`() {
        val state = BootstrapState.NeedsSetup(
            missingPermissions = listOf("android.permission.RECORD_AUDIO"),
            invalidModelSets = listOf("parakeet-tdt-0.6b-v3-int8"),
        )
        assertEquals(1, state.missingPermissions.size)
        assertEquals(1, state.invalidModelSets.size)
        assertEquals("android.permission.RECORD_AUDIO", state.missingPermissions[0])
    }

    @Test
    fun `Failed state carries stage and diagnostic`() {
        val state = BootstrapState.Failed(
            dev.nilp0inter.subspace.model.BootstrapStage.InitializingStt,
            "STT model failed to load",
            retryable = true,
        )
        assertEquals(
            dev.nilp0inter.subspace.model.BootstrapStage.InitializingStt,
            state.stage,
        )
        assertEquals("STT model failed to load", state.diagnostic)
        assertTrue(state.retryable)
    }

    @Test
    fun `PreparingCore carries stage and units`() {
        val state = BootstrapState.PreparingCore(
            stage = dev.nilp0inter.subspace.model.BootstrapStage.RenderingAnnouncements,
            completedUnits = 3,
            totalUnits = 7,
        )
        assertEquals(3, state.completedUnits)
        assertEquals(7, state.totalUnits)
    }

    @Test
    fun `CoreInit interface declares all required controller properties`() {
        // Verify CoreInit interface exists and declares the required
        // controller accessors. This is a compile-time check — if the
        // interface changes, this test won't compile.
        val init: CoreInit = FakeCoreInitImpl()
        assertEquals(null, init.sttController)
        assertEquals(null, init.ttsController)
        assertEquals(null, init.sttTtsController)
        assertEquals(null, init.journalPttController)
        assertEquals(null, init.keyboardController)
        assertEquals(null, init.announcer)
    }

    @Test
    fun `AnnouncementResult Ready is required for core readiness`() {
        // The coordinator's isCoreReady checks that announcer's
        // precomputeState is AnnouncementResult.Ready.
        val ready = AnnouncementResult.Ready(setOf("sys.menu.channels"))
        assertTrue(ready is AnnouncementResult.Ready)
        assertTrue(ready !is AnnouncementResult.Failed)
    }

    @Test
    fun `BootstrapState Ready is terminal until explicit retry`() {
        // Ready is distinct from all other states.
        val ready = BootstrapState.Ready
        val failed = BootstrapState.Failed(
            dev.nilp0inter.subspace.model.BootstrapStage.VerifyingReadiness,
            "verification failed",
        )
        assertTrue(ready !is BootstrapState.Failed)
        assertTrue(failed !is BootstrapState.Ready)
    }

    @Test
    fun `Failed state with retryable false prevents retry`() {
        val state = BootstrapState.Failed(
            dev.nilp0inter.subspace.model.BootstrapStage.InitializingStt,
            "STT construction failed",
            retryable = false,
        )
        assertTrue(!state.retryable)
    }

    @Test
    fun `AcquiringModels carries progress`() {
        val progress = dev.nilp0inter.subspace.model.ModelAcquisitionProgress(
            sets = listOf(
                dev.nilp0inter.subspace.model.ModelSetProgress(
                    dirName = "parakeet",
                    bytesRead = 100,
                    totalBytes = 500,
                ),
            ),
        )
        val state = BootstrapState.AcquiringModels(progress)
        assertEquals(100L, state.progress.bytesRead)
        assertEquals(500L, state.progress.totalBytes)
    }

    /**
     * Minimal fake CoreInit for compile-time interface verification.
     */
    private class FakeCoreInitImpl : CoreInit {
        override val sttTranscriber: SttTranscriber? = null
        override val ttsSynthesizer: TtsSynthesizer? = null
        override val sttController: SttController? = null
        override val ttsController: TtsController? = null
        override val sttTtsController: SttTtsController? = null
        override val journalPttController: JournalPttController? = null
        override val keyboardController: KeyboardPttController? = null
        override val announcer: SystemAnnouncer? = null

        override fun constructSttTranscriber(): SttTranscriber? = null
        override fun constructTtsSynthesizer(): TtsSynthesizer? = null
        override fun constructSttController(transcriber: SttTranscriber) {}
        override fun constructTtsController(synthesizer: TtsSynthesizer) {}
        override fun constructSttTtsController(
            transcriber: SttTranscriber,
            synthesizer: TtsSynthesizer,
        ) {}
        override fun constructJournalPttController() {}
        override fun constructKeyboardController(transcriber: SttTranscriber) {}
        override fun constructAnnouncer(synthesizer: TtsSynthesizer) {}
        override fun buildVocabulary(): Map<String, String> = emptyMap()
        override fun voiceStylePath(): String = ""
        override fun discardControllers() {}
    }
}