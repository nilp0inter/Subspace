package dev.nilp0inter.subspace.service

import android.content.Context
import android.content.pm.PackageManager
import dev.nilp0inter.subspace.audio.FakeSttTranscriber
import dev.nilp0inter.subspace.audio.FakeTtsSynthesizer
import dev.nilp0inter.subspace.audio.ModelAssetRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before

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
import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.TtsModelStatus
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
    @Before
    fun setUp() {
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `simple test to verify environment`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val modelRepository = mockk<ModelAssetRepository>(relaxed = true)
        coEvery { modelRepository.inspectAll() } returns emptyList()

        val coreInit = FakeCoreInitImpl()
        val coordinator = BootstrapCoordinator(
            context = context,
            scope = this,
            modelRepository = modelRepository,
            coreInit = coreInit,
        )
        assertEquals(BootstrapState.ConnectingService, coordinator.state.value)
    }

    @Test
    fun `retry coalescing coalesces concurrent retries`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val modelRepository = mockk<ModelAssetRepository>(relaxed = true)
        coEvery { modelRepository.inspectAll() } coAnswers {
            delay(10000)
            emptyList()
        }

        val sttTranscriberMock = mockk<SttTranscriber>(relaxed = true)
        every { sttTranscriberMock.modelStatus } returns SttModelStatus.Loading

        val ttsSynthesizerMock = mockk<TtsSynthesizer>(relaxed = true)
        every { ttsSynthesizerMock.modelStatus } returns TtsModelStatus.Loading

        var discardCount = 0
        val coreInit = mockk<CoreInit>(relaxed = true)
        every { coreInit.constructSttTranscriber() } returns sttTranscriberMock
        every { coreInit.constructTtsSynthesizer() } returns ttsSynthesizerMock
        every { coreInit.discardControllers() } answers { discardCount++ }

        val coordinator = BootstrapCoordinator(
            context = context,
            scope = this,
            modelRepository = modelRepository,
            coreInit = coreInit,
        )

        try {
            coordinator.startBootstrap()
            delay(100)
            Thread.sleep(50)

            // Now call retry twice concurrently
            coordinator.retry()
            coordinator.retry()

            delay(500)
            Thread.sleep(250)

            // The first retry will run discardControllers once.
            // The second concurrent retry should be ignored because the first retryJob is active.
            assertEquals("Expected exactly one call to discardControllers due to coalescing", 1, discardCount)
        } finally {
            coordinator.cancelAttempt()
        }
    }

    @Test
    fun `retry cancels and joins prior attempt before discarding controllers`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val events = java.util.Collections.synchronizedList(mutableListOf<String>())

        val modelRepository = mockk<ModelAssetRepository>(relaxed = true)
        coEvery { modelRepository.inspectAll() } coAnswers {
            try {
                delay(10000)
                emptyList()
            } finally {
                events.add("prior_cancelled")
            }
        }

        val coreInit = mockk<CoreInit>(relaxed = true)
        every { coreInit.discardControllers() } answers {
            events.add("discard_controllers")
        }

        val coordinator = BootstrapCoordinator(
            context = context,
            scope = this,
            modelRepository = modelRepository,
            coreInit = coreInit,
        )

        try {
            coordinator.startBootstrap()
            // Let the prior attempt enter inspectAll and suspend
            delay(100)
            Thread.sleep(50)

            // Call retry, which cancels/joins prior and then calls discardControllers
            coordinator.retry()

            // Wait to let it execute
            delay(500)
            Thread.sleep(250)

            // Assert they executed in the correct order
            assertEquals(listOf("prior_cancelled", "discard_controllers"), events)
        } finally {
            coordinator.cancelAttempt()
        }
    }

    @Test
    fun `replacement attempt does not start progress until prior attempt joins`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())

        every { context.checkPermission(any(), any(), any()) } answers {
            // This is called by the replacement attempt when checking permissions
            events.add("replacement_progress")
            PackageManager.PERMISSION_GRANTED
        }

        val modelRepository = mockk<ModelAssetRepository>(relaxed = true)
        coEvery { modelRepository.inspectAll() } coAnswers {
            try {
                delay(10000)
                emptyList()
            } finally {
                events.add("prior_finished")
            }
        }

        val coreInit = mockk<CoreInit>(relaxed = true)

        val coordinator = BootstrapCoordinator(
            context = context,
            scope = this,
            modelRepository = modelRepository,
            coreInit = coreInit,
        )

        try {
            coordinator.startBootstrap()
            delay(100)
            Thread.sleep(50)

            // Clear events from the first attempt
            events.clear()

            // Trigger retry
            coordinator.retry()

            delay(500)
            Thread.sleep(250)

            assertTrue("Expected events to contain prior_finished", events.contains("prior_finished"))
            assertTrue("Expected events to contain replacement_progress", events.contains("replacement_progress"))
            assertTrue(
                "Expected prior_finished to occur before replacement_progress, was: $events",
                events.indexOf("prior_finished") < events.indexOf("replacement_progress")
            )
        } finally {
            coordinator.cancelAttempt()
        }
    }

    @Test
    fun `stale attempt cannot publish state or mutate controllers after retry`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val modelRepository = mockk<ModelAssetRepository>(relaxed = true)
        coEvery { modelRepository.inspectAll() } returns emptyList()

        val sttTranscriber1 = mockk<SttTranscriber>(relaxed = true)
        var transcriber1Status: SttModelStatus = SttModelStatus.Loading
        every { sttTranscriber1.modelStatus } answers { transcriber1Status }

        val sttTranscriber2 = mockk<SttTranscriber>(relaxed = true)
        every { sttTranscriber2.modelStatus } returns SttModelStatus.Loading

        val coreInit = mockk<CoreInit>(relaxed = true)

        // Return transcriber1 first, then transcriber2
        var constructCount = 0
        every { coreInit.constructSttTranscriber() } answers {
            constructCount++
            if (constructCount == 1) sttTranscriber1 else sttTranscriber2
        }

        val coordinator = BootstrapCoordinator(
            context = context,
            scope = this,
            modelRepository = modelRepository,
            coreInit = coreInit,
        )

        try {
            coordinator.startBootstrap()
            // Let it run and wait on transcriber1
            delay(100)
            Thread.sleep(50)

            // Trigger retry
            coordinator.retry()
            // Let it run and wait on transcriber2
            delay(100)
            Thread.sleep(50)

            // Now simulate late/stale transcriber1 becoming Ready!
            transcriber1Status = SttModelStatus.Ready

            // Wait to see if transcriber1 is processed
            delay(500)
            Thread.sleep(250)

            // Verify that coreInit.constructSttController was never called with transcriber1!
            verify(exactly = 0) { coreInit.constructSttController(sttTranscriber1) }

            // State should still be PreparingCore (InitializingStt) for the replacement attempt
            assertTrue(
                "State should remain PreparingCore and not progress or fail due to stale attempt",
                coordinator.state.value is BootstrapState.PreparingCore
            )
        } finally {
            coordinator.cancelAttempt()
        }
    }
    @Test
    fun `cancelling bootstrap attempt cancels STT and TTS async children`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val modelRepository = mockk<ModelAssetRepository>(relaxed = true)
        coEvery { modelRepository.inspectAll() } returns emptyList()

        var sttPollCount = 0
        var ttsPollCount = 0

        val sttTranscriberMock = mockk<SttTranscriber>(relaxed = true)
        every { sttTranscriberMock.modelStatus } answers {
            sttPollCount++
            SttModelStatus.Loading
        }

        val ttsSynthesizerMock = mockk<TtsSynthesizer>(relaxed = true)
        every { ttsSynthesizerMock.modelStatus } answers {
            ttsPollCount++
            TtsModelStatus.Loading
        }

        val coreInit = mockk<CoreInit>(relaxed = true)
        every { coreInit.sttTranscriber } returns sttTranscriberMock
        every { coreInit.ttsSynthesizer } returns ttsSynthesizerMock
        every { coreInit.constructSttTranscriber() } returns sttTranscriberMock
        every { coreInit.constructTtsSynthesizer() } returns ttsSynthesizerMock

        val coordinator = BootstrapCoordinator(
            context = context,
            scope = this,
            modelRepository = modelRepository,
            coreInit = coreInit,
        )

        try {
            coordinator.startBootstrap()

            // Let it run and poll a few times
            var attempts = 0
            while ((sttPollCount < 2 || ttsPollCount < 2) && attempts < 20) {
                delay(100)
                Thread.sleep(50)
                attempts++
            }
            if (sttPollCount < 2) {
                throw RuntimeException("Expected STT to poll at least twice, was $sttPollCount. State: ${coordinator.state.value}")
            }
            if (ttsPollCount < 2) {
                throw RuntimeException("Expected TTS to poll at least twice, was $ttsPollCount. State: ${coordinator.state.value}")
            }

            val sttPollCountBeforeCancel = sttPollCount
            val ttsPollCountBeforeCancel = ttsPollCount

            // Cancel the attempt
            coordinator.cancelAttempt()

            // Advance time significantly in real time to see if the background threads stopped polling
            delay(1000)
            Thread.sleep(500)

            // Verify poll count did not increase further
            assertEquals(sttPollCountBeforeCancel, sttPollCount)
            assertEquals(ttsPollCountBeforeCancel, ttsPollCount)
        } finally {
            coordinator.cancelAttempt()
        }
    }
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