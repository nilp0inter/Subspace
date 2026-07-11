package dev.nilp0inter.subspace.service

import android.content.Context
import android.content.pm.PackageManager
import dev.nilp0inter.subspace.audio.FakeSttTranscriber
import dev.nilp0inter.subspace.audio.FakeTtsSynthesizer
import dev.nilp0inter.subspace.audio.ModelAssetRepository
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.SystemAnnouncer
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.model.AnnouncementResult
import dev.nilp0inter.subspace.model.BootstrapStage
import dev.nilp0inter.subspace.model.BootstrapState
import dev.nilp0inter.subspace.model.SttModelStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapCoordinatorTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `ready native engines and host services complete bootstrap`() = runTest {
        val coreInit = FakeCoreInit()
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `missing all-files access enters dedicated setup before model download or core initialization`() = runTest {
        var modelDownloadCount = 0
        val coreInit = FakeCoreInit()
        val coordinator = coordinator(
            coreInit = coreInit,
            modelRepository = modelRepository(
                inspect = { emptyList() },
                ensureReady = {
                    modelDownloadCount += 1
                    true
                },
            ),
            hasManageExternalStorage = { false },
        )

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(
            BootstrapState.NeedsSetup(needsManageExternalStorage = true),
            coordinator.state.value,
        )
        assertEquals(0, coreInit.sttConstructionCount)
        assertEquals(0, coreInit.ttsConstructionCount)

        coordinator.startModelAcquisition()
        advanceUntilIdle()

        assertEquals(0, modelDownloadCount)
        assertEquals(
            BootstrapState.NeedsSetup(needsManageExternalStorage = true),
            coordinator.state.value,
        )
    }

    @Test
    fun `refreshing after all-files access is granted completes bootstrap`() = runTest {
        var hasAllFilesAccess = false
        val coreInit = FakeCoreInit()
        val coordinator = coordinator(
            coreInit = coreInit,
            hasManageExternalStorage = { hasAllFilesAccess },
        )

        coordinator.startBootstrap()
        advanceUntilIdle()
        assertEquals(
            BootstrapState.NeedsSetup(needsManageExternalStorage = true),
            coordinator.state.value,
        )

        hasAllFilesAccess = true
        coordinator.refreshPrerequisites()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `loading native STT past its deadline fails at STT initialization`() = runTest {
        val coreInit = FakeCoreInit(
            sttFactory = { FakeSttTranscriber(modelStatus = SttModelStatus.Loading) },
        )
        val coordinator = coordinator(coreInit).apply {
            sttTimeoutMs = 999L
        }

        coordinator.startBootstrap()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(
            BootstrapState.Failed(
                BootstrapStage.InitializingStt,
                "STT initialization timed out after 999ms",
            ),
            coordinator.state.value,
        )
        coordinator.cancelAttempt()
        runCurrent()
    }

    @Test
    fun `native STT load failure surfaces its diagnostic and blocks readiness`() = runTest {
        val coreInit = FakeCoreInit(
            sttFactory = {
                FakeSttTranscriber(
                    modelStatus = SttModelStatus.Failed,
                    loadError = "corrupt native model",
                )
            },
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(
            BootstrapState.Failed(
                BootstrapStage.InitializingStt,
                "STT model failed to load: corrupt native model",
            ),
            coordinator.state.value,
        )
    }

    @Test
    fun `announcement rendering failure identifies the phrase and blocks readiness`() = runTest {
        val coreInit = FakeCoreInit(
            announcementBehavior = {
                AnnouncementResult.Failed(
                    completed = 0,
                    total = 1,
                    failedKey = "sys.error",
                    reason = "empty PCM",
                )
            },
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(
            BootstrapState.Failed(
                BootstrapStage.RenderingAnnouncements,
                "Announcement 'sys.error' failed: empty PCM",
            ),
            coordinator.state.value,
        )
    }

    @Test
    fun `concurrent retries coalesce and discard only after the prior attempt joins`() = runTest {
        val events = mutableListOf<String>()
        val firstInspectionStarted = CompletableDeferred<Unit>()
        var inspectionCount = 0
        val modelRepository = modelRepository(inspect = {
            inspectionCount += 1
            if (inspectionCount == 1) {
                firstInspectionStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "prior joined"
                }
            } else {
                events += "replacement inspected"
                emptyList()
            }
        })
        val coreInit = FakeCoreInit(onDiscard = { events += "controllers discarded" })
        val coordinator = coordinator(coreInit, modelRepository)

        coordinator.startBootstrap()
        runCurrent()
        firstInspectionStarted.await()

        coordinator.retry()
        coordinator.retry()
        advanceUntilIdle()

        assertEquals(
            listOf("prior joined", "controllers discarded", "replacement inspected"),
            events,
        )
        assertEquals(1, coreInit.discardCount)
        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `recoverable physical text output and journal recovery do not block core readiness`() = runTest {
        val coreInit = FakeCoreInit(
            textOutputAfterInitialization = CapabilityAvailability.Recoverable,
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(CapabilityAvailability.Recoverable, coreInit.textOutputAvailability)
        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `unavailable semantic text output fails readiness with an actionable diagnostic`() = runTest {
        val unavailable = CapabilityAvailability.Unavailable(
            dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
        )
        val coreInit = FakeCoreInit(textOutputAfterInitialization = unavailable)
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is BootstrapState.Failed)
        state as BootstrapState.Failed
        assertEquals(BootstrapStage.VerifyingReadiness, state.stage)
        assertTrue(state.diagnostic.contains("textOutputAvailability=$unavailable"))
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        coreInit: CoreInit,
        modelRepository: ModelAssetRepository = modelRepository(inspect = { emptyList() }),
        hasManageExternalStorage: () -> Boolean = { true },
    ): BootstrapCoordinator = BootstrapCoordinator(
        context = grantedContext(),
        scope = this,
        modelRepository = modelRepository,
        coreInit = coreInit,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        hasManageExternalStorage = hasManageExternalStorage,
    )

    private fun grantedContext(): Context = mockk(relaxed = true) {
        every { checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
    }

    private fun modelRepository(
        inspect: suspend () -> List<dev.nilp0inter.subspace.model.ModelAssetResult>,
        ensureReady: suspend () -> Boolean = { true },
    ): ModelAssetRepository = mockk(relaxed = true) {
        coEvery { inspectAll() } coAnswers { inspect() }
        coEvery { ensureAllReady() } coAnswers { ensureReady() }
    }

    /**
     * Host-boundary fake matching the current [CoreInit] contract. Construction methods publish
     * the same observable services that production readiness consumes; retry clears all of them.
     */
    private class FakeCoreInit(
        private val sttFactory: () -> SttTranscriber? = { FakeSttTranscriber() },
        private val ttsFactory: () -> TtsSynthesizer? = { FakeTtsSynthesizer() },
        private val textOutputAfterInitialization: CapabilityAvailability =
            CapabilityAvailability.Available,
        private val announcementBehavior: suspend () -> AnnouncementResult = {
            AnnouncementResult.Ready(setOf("sys.ready"))
        },
        private val onDiscard: () -> Unit = {},
    ) : CoreInit {
        override var sttTranscriber: SttTranscriber? = null
            private set
        override var ttsSynthesizer: TtsSynthesizer? = null
            private set
        override var ttsController: TtsController? = null
            private set
        override var journalController: JournalController? = null
            private set
        override var textOutputAvailability: CapabilityAvailability =
            CapabilityAvailability.Unavailable(
                dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
            )
            private set
        override var announcer: SystemAnnouncer? = null
            private set

        var discardCount: Int = 0
            private set
        var sttConstructionCount: Int = 0
            private set
        var ttsConstructionCount: Int = 0
            private set

        override fun constructSttTranscriber(): SttTranscriber? = sttFactory().also {
            sttConstructionCount += 1
            sttTranscriber = it
        }

        override fun constructTtsSynthesizer(): TtsSynthesizer? = ttsFactory().also {
            ttsConstructionCount += 1
            ttsSynthesizer = it
        }

        override fun constructTtsController(synthesizer: TtsSynthesizer) {
            ttsController = mockk(relaxed = true)
        }

        override fun constructJournalPttController() {
            journalController = mockk(relaxed = true)
        }

        override fun initializeTextOutputCapability() {
            textOutputAvailability = textOutputAfterInitialization
        }

        override fun constructAnnouncer(synthesizer: TtsSynthesizer) {
            val state = MutableStateFlow<AnnouncementResult>(AnnouncementResult.WaitingForTts)
            announcer = mockk {
                every { precomputeState } returns state
                coEvery { precompute(any(), any(), any()) } coAnswers {
                    announcementBehavior().also { state.value = it }
                }
            }
        }

        override fun buildVocabulary(): Map<String, String> = mapOf("sys.ready" to "Ready")

        override fun voiceStylePath(): String = "/models/voice.json"

        override fun discardControllers() {
            discardCount += 1
            sttTranscriber = null
            ttsSynthesizer = null
            ttsController = null
            journalController = null
            textOutputAvailability = CapabilityAvailability.Unavailable(
                dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
            )
            announcer = null
            onDiscard()
        }
    }
}