package dev.nilp0inter.subspace.service

import android.content.Context
import android.speech.tts.TextToSpeech
import dev.nilp0inter.subspace.audio.FakeSttTranscriber
import dev.nilp0inter.subspace.audio.FakeTtsSynthesizer
import dev.nilp0inter.subspace.audio.ModelVerifier
import dev.nilp0inter.subspace.audio.NavigationTtsEngine
import dev.nilp0inter.subspace.audio.NavigationTtsFailure
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.PrepareResult
import dev.nilp0inter.subspace.audio.StateLossCallback
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.TextOutputAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCoreInitializerTest {
    @Test
    fun `CoreInit retains successful STT TTS journal text-output and navigation construction`() = runTest {
        val navigation = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        val coreInit: CoreInit = fixture.initializer

        assertSame(fixture.stt, coreInit.constructSttTranscriber())
        assertSame(fixture.tts, coreInit.constructTtsSynthesizer())
        coreInit.constructTtsController(requireNotNull(fixture.tts))
        coreInit.constructJournalPttController()
        fixture.textOutputAvailability.value = TextOutputAvailability.Available
        coreInit.initializeTextOutputCapability()
        val navigationResult = coreInit.prepareNavigationTts()

        assertEquals(
            File(fixture.modelRoot, ModelVerifier.PARAKEET_DIR),
            fixture.initializer.sttModelDir,
        )
        assertEquals(
            File(fixture.modelRoot, ModelVerifier.SUPERTONIC_DIR),
            fixture.initializer.supertonicModelDir,
        )
        assertSame(fixture.stt, coreInit.sttTranscriber)
        assertSame(fixture.tts, coreInit.ttsSynthesizer)
        assertSame(fixture.controller, coreInit.ttsController)
        assertSame(fixture.journal, coreInit.journalController)
        assertSame(navigation, coreInit.navigationTtsEngine)
        assertTrue(navigationResult is PrepareResult.Success)
        assertEquals(CapabilityAvailability.Available, coreInit.textOutputAvailability)
    }

    @Test
    fun `CoreInit preserves failed native journal text-output and navigation outcomes without retaining resources`() = runTest {
        val navigation = mockk<NavigationTtsEngine>()
        val navigationFailure = NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed("engine unavailable")
        coEvery { navigation.prepare() } returns PrepareResult.Failure(navigationFailure)
        coEvery { navigation.shutdown() } just Runs
        val journalFailure = IllegalStateException("journal unavailable")
        val fixture = Fixture(
            scope = backgroundScope,
            stt = null,
            tts = null,
            navigationEngines = ArrayDeque(listOf(navigation)),
            journalControllerFactory = JournalControllerFactory { _, _ -> throw journalFailure },
        )
        val coreInit: CoreInit = fixture.initializer

        assertNull(coreInit.constructSttTranscriber())
        assertNull(coreInit.constructTtsSynthesizer())
        val thrown = assertThrows(IllegalStateException::class.java) {
            coreInit.constructJournalPttController()
        }
        fixture.textOutputAvailability.value = TextOutputAvailability.Closed
        coreInit.initializeTextOutputCapability()
        val navigationResult = coreInit.prepareNavigationTts()

        assertSame(journalFailure, thrown)
        assertNull(coreInit.sttTranscriber)
        assertNull(coreInit.ttsSynthesizer)
        assertNull(coreInit.journalController)
        assertNull(coreInit.navigationTtsEngine)
        assertEquals(
            CapabilityAvailability.Unavailable(
                dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
            ),
            coreInit.textOutputAvailability,
        )
        assertEquals(PrepareResult.Failure(navigationFailure), navigationResult)
        coVerify(exactly = 1) { navigation.shutdown() }
    }

    @Test
    fun `retry discard cancels owned pollers and releases retained resources exactly once across repeated discard`() = runTest {
        val navigation = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        val coreInit: CoreInit = fixture.initializer

        coreInit.constructSttTranscriber()
        val synthesizer = requireNotNull(coreInit.constructTtsSynthesizer())
        coreInit.constructTtsController(synthesizer)
        coreInit.constructJournalPttController()
        coreInit.prepareNavigationTts()
        fixture.journalStorageBackends["active"] = mockk(relaxed = true)

        coreInit.discardControllers()
        coreInit.discardControllers()

        assertTrue(fixture.sttPoller.isCancelled)
        assertTrue(fixture.ttsPoller.isCancelled)
        assertNull(coreInit.sttTranscriber)
        assertNull(coreInit.ttsSynthesizer)
        assertNull(coreInit.ttsController)
        assertNull(coreInit.journalController)
        assertNull(coreInit.navigationTtsEngine)
        assertTrue(fixture.journalStorageBackends.isEmpty())
        verify(exactly = 1) { fixture.controller.cancelAndRelease() }
        coVerify(exactly = 1) { navigation.shutdown() }
    }

    @Test
    fun `shutdown after partial initialization ignores absent resources and is idempotent`() = runTest {
        val navigation = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        val coreInit: CoreInit = fixture.initializer

        coreInit.constructSttTranscriber()
        coreInit.prepareNavigationTts()
        fixture.initializer.shutdown()
        fixture.initializer.shutdown()

        assertTrue(fixture.sttPoller.isCancelled)
        assertNull(coreInit.sttTranscriber)
        assertNull(coreInit.ttsSynthesizer)
        assertNull(coreInit.ttsController)
        assertNull(coreInit.journalController)
        assertNull(coreInit.navigationTtsEngine)
        coVerify(exactly = 1) { navigation.shutdown() }
    }

    @Test
    fun `navigation prepare replaces its retired engine and service shutdown closes the replacement once`() = runTest {
        val first = preparedNavigationEngine()
        val replacement = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(first, replacement)),
        )

        fixture.initializer.prepareNavigationTts()
        fixture.initializer.prepareNavigationTts()
        fixture.initializer.shutdown()
        fixture.initializer.shutdown()

        assertNull(fixture.initializer.navigationTtsEngine)
        coVerify(exactly = 1) { first.shutdown() }
        coVerify(exactly = 1) { replacement.shutdown() }
    }

    private fun preparedNavigationEngine(): NavigationTtsEngine = mockk {
        coEvery { prepare() } returns PrepareResult.Success(mockk<TextToSpeech>(relaxed = true))
        coEvery { shutdown() } just Runs
    }

    private class Fixture(
        scope: CoroutineScope,
        val stt: SttTranscriber? = FakeSttTranscriber(),
        val tts: TtsSynthesizer? = FakeTtsSynthesizer(),
        val controller: TtsController = mockk(relaxed = true),
        val journal: JournalController = mockk(relaxed = true),
        val navigationEngines: ArrayDeque<NavigationTtsEngine>,
        journalControllerFactory: JournalControllerFactory = JournalControllerFactory { _, _ -> journal },
    ) {
        val modelRoot = File("/service-core-initializer-test-models")
        val textOutputAvailability: MutableStateFlow<TextOutputAvailability> =
            MutableStateFlow(TextOutputAvailability.Available)
        val journalStorageBackends = ConcurrentHashMap<String, ServiceJournalStorageBackend>()
        val sttPoller = Job()
        val ttsPoller = Job()

        private val textOutputService = mockk<SleepwalkerTextOutputService> {
            every { availability } returns textOutputAvailability
        }

        val initializer = ServiceCoreInitializer(
            context = mockk<Context>(relaxed = true),
            scope = scope,
            nativeLibraryDirProvider = { "/native" },
            filesDirProvider = { modelRoot },
            textOutputService = textOutputService,
            journalStorageBackends = journalStorageBackends,
            channelCatalogue = { mockk(relaxed = true) },
            modelStatusSink = ModelStatusSink { },
            navigationStateLoss = StateLossCallback { _, _ -> },
            hostAudioPlay = { true },
            sttFactory = SttFactory { _, _ -> stt },
            ttsFactory = TtsFactory { _, _ -> tts },
            ttsControllerFactory = TtsControllerFactory { _, _, _ -> controller },
            transcriptionServiceFactory = TranscriptionServiceFactory { mockk<TranscriptionService>(relaxed = true) },
            pcmTranscriberFallback = PcmTranscriberFallback { mockk<PcmTranscriber>(relaxed = true) },
            navigationTtsEngineFactory = NavigationTtsEngineFactory { _, _ -> navigationEngines.removeFirst() },
            journalControllerFactory = journalControllerFactory,
            sttPollerFactory = SttPollerFactory { _, _, _, _ -> sttPoller },
            ttsPollerFactory = TtsPollerFactory { _, _, _, _, _, _ -> ttsPoller },
        )
    }
}
