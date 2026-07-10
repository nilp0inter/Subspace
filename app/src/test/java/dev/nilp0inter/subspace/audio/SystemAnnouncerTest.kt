package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.AnnouncementResult
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.mockk.*

@OptIn(ExperimentalCoroutinesApi::class)
class SystemAnnouncerTest {

    // -- Fakes -------------------------------------------------------------

    private class FakeScoRoute : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        var releaseCount = 0
            private set

        override fun hasAvailableScoDevice(): Boolean = true

        override suspend fun acquire(): Boolean {
            _state.value = ScoState.Active
            return true
        }

        override fun isActive(): Boolean = _state.value == ScoState.Active

        override fun release() {
            releaseCount += 1
            _state.value = ScoState.Inactive
        }
    }

    /** Records whether PCM or a beep was played so tests can distinguish the two paths. */
    private class RecordingPcmOutput : PcmOutput {
        var beepCount = 0
            private set
        var playbackCount = 0
            private set
        var lastPlayed: RecordedPcm? = null
            private set

        override suspend fun playReadyBeep(coldStart: Boolean) {
            beepCount += 1
        }

        override suspend fun playErrorBeep(coldStart: Boolean) {}

        override suspend fun play(recording: RecordedPcm) {
            playbackCount += 1
            lastPlayed = recording
        }
    }

    // -- Helpers -----------------------------------------------------------

    private fun threePhraseVocab(): Map<String, String> = linkedMapOf(
        "a" to "alpha",
        "b" to "beta",
        "c" to "gamma",
    )

    // -- 1. Real phrase counts ---------------------------------------------

    @Test
    fun precomputeUsesVocabularyDerivedTotal() = runTest {
        val synth = FakeTtsSynthesizer()
        val announcer = SystemAnnouncer(synth)
        val vocab = linkedMapOf(
            "a" to "alpha",
            "b" to "beta",
            "c" to "gamma",
            "d" to "delta",
            "e" to "epsilon",
        )

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        assertTrue("expected Ready, got $result", result is AnnouncementResult.Ready)
        val ready = result as AnnouncementResult.Ready
        assertEquals(5, ready.renderedKeys.size)
        assertTrue(ready.renderedKeys.containsAll(vocab.keys))
        assertEquals(5, synth.callCount)
    }

    // -- 2. All-phrase success ---------------------------------------------

    @Test
    fun allPhraseSuccessReturnsReady() = runTest {
        val synth = FakeTtsSynthesizer()
        val announcer = SystemAnnouncer(synth)
        val vocab = threePhraseVocab()

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        assertTrue(result is AnnouncementResult.Ready)
        val ready = result as AnnouncementResult.Ready
        assertEquals(3, ready.renderedKeys.size)
        assertTrue(ready.renderedKeys.containsAll(vocab.keys))
        assertEquals(3, synth.callCount)
        // Each phrase was synthesized with non-empty PCM.
        for (key in vocab.keys) {
            assertTrue("'$key' should be in renderedKeys", key in ready.renderedKeys)
        }
        // precomputeState should reflect the Ready result.
        assertEquals(result, announcer.precomputeState.value)
    }

    // -- 3. Empty output counts as failure ---------------------------------

    @Test
    fun emptySamplesCountAsFailureForThatPhrase() = runTest {
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Success(FloatArray(0)))
        }
        val announcer = SystemAnnouncer(synth)
        val vocab = linkedMapOf("a" to "alpha")

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        assertTrue(result is AnnouncementResult.Failed)
        val failed = result as AnnouncementResult.Failed
        assertEquals("a", failed.failedKey)
        assertEquals(1, failed.total)
        assertEquals(0, failed.completed)
        assertTrue(announcer.precomputeState.value is AnnouncementResult.Failed)
    }

    // -- 4. Partial synthesis failure -------------------------------------

    @Test
    fun partialSynthesisFailureReturnsFailedWithKey() = runTest {
        val synth = FakeTtsSynthesizer().apply {
            setOutcomeFactory { req ->
                if (req.text == "beta") {
                    SynthesisOutcome.Failure("model error")
                } else {
                    SynthesisOutcome.Success(FloatArray(req.text.length * 10) { 0.5f })
                }
            }
        }
        val announcer = SystemAnnouncer(synth)
        val vocab = threePhraseVocab()

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        assertTrue(result is AnnouncementResult.Failed)
        val failed = result as AnnouncementResult.Failed
        assertEquals("b", failed.failedKey)
        assertEquals(3, failed.total)
        // "a" rendered successfully before "b" failed.
        assertEquals(1, failed.completed)
        assertTrue(announcer.precomputeState.value is AnnouncementResult.Failed)
    }

    // -- 5. Retry re-renders and replaces entries --------------------------

    @Test
    fun retryReRendersAndReplacesEntries() = runTest {
        val synth = FakeTtsSynthesizer()
        val announcer = SystemAnnouncer(synth)
        val vocab = threePhraseVocab()

        val result1 = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()
        assertTrue(result1 is AnnouncementResult.Ready)
        assertEquals(3, synth.callCount)

        // Change the outcome and re-render.
        synth.setOutcome(SynthesisOutcome.Success(FloatArray(200) { 0.7f }))
        val result2 = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        assertTrue(result2 is AnnouncementResult.Ready)
        val ready2 = result2 as AnnouncementResult.Ready
        assertEquals(3, ready2.renderedKeys.size)
        assertTrue(ready2.renderedKeys.containsAll(vocab.keys))
        // All three phrases were re-synthesized.
        assertEquals(6, synth.callCount)
    }

    // -- 6. Beep fallback distinct from precompute success -----------------

    @Test
    fun announcePlaysCachedPcmAfterSuccessfulPrecompute() = runTest {
        val synth = FakeTtsSynthesizer()
        val announcer = SystemAnnouncer(synth)
        val vocab = threePhraseVocab()

        val precomputeResult = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()
        assertTrue(precomputeResult is AnnouncementResult.Ready)
        assertEquals(precomputeResult, announcer.precomputeState.value)

        val sco = FakeScoRoute()
        val output = RecordingPcmOutput()
        announcer.announce("a", sco, output)
        advanceUntilIdle()

        // Cached entry → play PCM, not beep.
        assertEquals(1, output.playbackCount)
        assertEquals(0, output.beepCount)
        val played = output.lastPlayed
        assertNotNull(played)
        assertFalse("played PCM must be non-empty", played!!.isEmpty)
    }

    @Test
    fun announceUsesBeepForMissingCacheEntry() = runTest {
        val synth = FakeTtsSynthesizer()
        val announcer = SystemAnnouncer(synth)

        // No precompute — cache is empty.
        val sco = FakeScoRoute()
        val output = RecordingPcmOutput()
        announcer.announce("missing", sco, output)
        advanceUntilIdle()

        assertEquals(0, output.playbackCount)
        assertEquals(1, output.beepCount)
    }

    @Test
    fun precomputeFailureDoesNotReportReadyEvenThoughAnnounceUsesBeep() = runTest {
        val synth = FakeTtsSynthesizer().apply {
            setOutcomeFactory { req ->
                if (req.text == "beta") {
                    SynthesisOutcome.Failure("model error")
                } else {
                    SynthesisOutcome.Success(FloatArray(req.text.length * 10) { 0.5f })
                }
            }
        }
        val announcer = SystemAnnouncer(synth)
        val vocab = threePhraseVocab()

        val precomputeResult = announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        // Precompute failed — NOT Ready.
        assertTrue(precomputeResult is AnnouncementResult.Failed)
        assertTrue(announcer.precomputeState.value is AnnouncementResult.Failed)
        assertFalse(announcer.precomputeState.value is AnnouncementResult.Ready)

        // announce() for the unrendered key still produces a beep — a defensive
        // runtime fallback for cache loss. This does NOT turn the precompute
        // into bootstrap success.
        val sco = FakeScoRoute()
        val output = RecordingPcmOutput()
        announcer.announce("b", sco, output)
        advanceUntilIdle()

        assertEquals(0, output.playbackCount)
        assertEquals(1, output.beepCount)

        // The precompute state remains Failed.
        assertTrue(announcer.precomputeState.value is AnnouncementResult.Failed)
    }

    @Test
    fun announceForCachedKeyDoesNotBeepAndUncachedKeyDoesBeep() = runTest {
        val synth = FakeTtsSynthesizer()
        val announcer = SystemAnnouncer(synth)
        val vocab = threePhraseVocab()
        announcer.precompute(vocab, "/tmp/M1.json", 16_000)
        advanceUntilIdle()

        // Cached key → play PCM.
        val sco1 = FakeScoRoute()
        val output1 = RecordingPcmOutput()
        announcer.announce("a", sco1, output1)
        advanceUntilIdle()
        assertEquals(1, output1.playbackCount)
        assertEquals(0, output1.beepCount)

        // Uncached key → beep.
        val sco2 = FakeScoRoute()
        val output2 = RecordingPcmOutput()
        announcer.announce("unknown", sco2, output2)
        advanceUntilIdle()
        assertEquals(0, output2.playbackCount)
        assertEquals(1, output2.beepCount)
    }

    @Test
    fun persistentCacheAllHitsNoSynthesis() = runTest {
        val synth = FakeTtsSynthesizer()
        val cache = mockk<AnnouncementPcmCache>()
        val vocab = threePhraseVocab()
        val expectedPcm = RecordedPcm(shortArrayOf(1, 2, 3), 16000)
        val loaded = vocab.mapValues { expectedPcm }

        coEvery { cache.load(vocab, any()) } returns loaded
        coEvery { cache.commit(vocab, any(), any()) } returns AnnouncementCacheCommitResult.Unchanged

        val announcer = SystemAnnouncer(synth, cache)
        
        val states = mutableListOf<AnnouncementResult>()
        val collectJob = launch {
            announcer.precomputeState.collect { states.add(it) }
        }

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()
        collectJob.cancel()

        assertTrue(result is AnnouncementResult.Ready)
        assertEquals(3, (result as AnnouncementResult.Ready).renderedKeys.size)
        assertEquals(0, synth.callCount)

        // Verify that no Rendering progress states were emitted
        val renderingStates = states.filterIsInstance<AnnouncementResult.Rendering>()
        assertTrue("Expected no rendering states, but got: $renderingStates", renderingStates.isEmpty())
        
        // Check that the last state is Ready
        assertTrue(states.last() is AnnouncementResult.Ready)

        coVerify(exactly = 1) { cache.load(vocab, any()) }
        coVerify(exactly = 1) { cache.commit(vocab, any(), any()) }
    }

    @Test
    fun persistentCachePartialHitsProgress() = runTest {
        val synth = FakeTtsSynthesizer().apply {
            setOutcomeFactory { req ->
                Thread.sleep(50)
                SynthesisOutcome.Success(FloatArray(req.text.length * 10) { 0.5f })
            }
        }
        val cache = mockk<AnnouncementPcmCache>()
        val vocab = linkedMapOf(
            "a" to "alpha",
            "b" to "beta",
            "c" to "gamma"
        )
        
        val expectedPcm = RecordedPcm(shortArrayOf(1, 2, 3), 16000)
        val loaded = mapOf("a" to expectedPcm)

        coEvery { cache.load(vocab, any()) } returns loaded
        coEvery { cache.commit(vocab, any(), any()) } returns AnnouncementCacheCommitResult.Unchanged

        val announcer = SystemAnnouncer(synth, cache)

        val states = mutableListOf<AnnouncementResult>()
        val collectJob = launch {
            announcer.precomputeState.collect { states.add(it) }
        }

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()
        collectJob.cancel()

        assertTrue(result is AnnouncementResult.Ready)
        assertEquals(2, synth.callCount)

        val renderingStates = states.filterIsInstance<AnnouncementResult.Rendering>()
        assertEquals(2, renderingStates.size)

        assertEquals(1, renderingStates[0].completed)
        assertEquals("b", renderingStates[0].currentKey)
        assertEquals(3, renderingStates[0].total)

        assertEquals(2, renderingStates[1].completed)
        assertEquals("c", renderingStates[1].currentKey)
        assertEquals(3, renderingStates[1].total)
    }

    @Test
    fun persistentCacheDuplicateTextCounts() = runTest {
        val synth = FakeTtsSynthesizer().apply {
            setOutcomeFactory { req ->
                Thread.sleep(50)
                SynthesisOutcome.Success(FloatArray(req.text.length * 10) { 0.5f })
            }
        }
        val cache = mockk<AnnouncementPcmCache>()
        val vocab = linkedMapOf(
            "a" to "alpha",
            "b" to "beta",
            "c" to "beta"
        )

        coEvery { cache.load(vocab, any()) } returns emptyMap()
        coEvery { cache.commit(vocab, any(), any()) } returns AnnouncementCacheCommitResult.Unchanged

        val announcer = SystemAnnouncer(synth, cache)

        val states = mutableListOf<AnnouncementResult>()
        val collectJob = launch {
            announcer.precomputeState.collect { states.add(it) }
        }

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()
        collectJob.cancel()

        assertTrue(result is AnnouncementResult.Ready)
        assertEquals(2, synth.callCount)

        val renderingStates = states.filterIsInstance<AnnouncementResult.Rendering>()
        assertEquals(2, renderingStates.size)

        assertEquals(0, renderingStates[0].completed)
        assertEquals("a", renderingStates[0].currentKey)
        assertEquals(3, renderingStates[0].total)

        assertEquals(1, renderingStates[1].completed)
        assertEquals("b", renderingStates[1].currentKey)
        assertEquals(3, renderingStates[1].total)

        val readyState = states.last() as AnnouncementResult.Ready
        assertEquals(3, readyState.renderedKeys.size)
    }

    @Test
    fun persistentCacheGroupFailures() = runTest {
        val synth = FakeTtsSynthesizer().apply {
            setOutcomeFactory { req ->
                if (req.text == "beta") {
                    SynthesisOutcome.Failure("tts error")
                } else {
                    SynthesisOutcome.Success(FloatArray(100) { 0.5f })
                }
            }
        }
        val cache = mockk<AnnouncementPcmCache>()
        val vocab = linkedMapOf(
            "a" to "alpha",
            "b" to "beta",
            "c" to "beta"
        )

        coEvery { cache.load(vocab, any()) } returns emptyMap()
        coEvery { cache.commit(vocab, any(), any()) } returns AnnouncementCacheCommitResult.Unchanged

        val announcer = SystemAnnouncer(synth, cache)

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()

        assertTrue(result is AnnouncementResult.Failed)
        val failed = result as AnnouncementResult.Failed
        assertEquals("b", failed.failedKey)
        assertEquals(3, failed.total)
        assertEquals(1, failed.completed)

        coVerify(exactly = 0) { cache.commit(any(), any(), any()) }

        val sco = FakeScoRoute()
        val output = RecordingPcmOutput()
        announcer.announce("b", sco, output)
        advanceUntilIdle()
        assertEquals(0, output.playbackCount)
        assertEquals(1, output.beepCount)
    }

    @Test
    fun persistentCacheEmptyVocabulary() = runTest {
        val synth = FakeTtsSynthesizer()
        val cache = mockk<AnnouncementPcmCache>()
        
        coEvery { cache.commit(emptyMap(), any(), emptyMap()) } returns AnnouncementCacheCommitResult.Written

        val announcer = SystemAnnouncer(synth, cache)

        val result = announcer.precompute(emptyMap(), "/tmp/M1.json", 16000)
        advanceUntilIdle()

        assertTrue(result is AnnouncementResult.Ready)
        assertEquals(0, (result as AnnouncementResult.Ready).renderedKeys.size)
        assertEquals(0, synth.callCount)

        coVerify(exactly = 1) { cache.commit(emptyMap(), any(), emptyMap()) }
    }

    @Test
    fun persistentCacheNonfatalCommitResults() = runTest {
        val synth = FakeTtsSynthesizer()
        val cache = mockk<AnnouncementPcmCache>()
        val vocab = threePhraseVocab()

        coEvery { cache.load(vocab, any()) } returns emptyMap()
        coEvery { cache.commit(vocab, any(), any()) } returns AnnouncementCacheCommitResult.Failed("Disk write failed")

        val announcer = SystemAnnouncer(synth, cache)

        val result = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()

        assertTrue(result is AnnouncementResult.Ready)
        val ready = result as AnnouncementResult.Ready
        assertEquals(3, ready.renderedKeys.size)
    }

    @Test
    fun persistentCacheStaleExclusion() = runTest {
        val synth = FakeTtsSynthesizer()
        val cache = mockk<AnnouncementPcmCache>()
        val vocab = linkedMapOf("a" to "alpha")

        coEvery { cache.load(vocab, any()) } returns emptyMap()
        coEvery { cache.commit(vocab, any(), any()) } returns AnnouncementCacheCommitResult.Unchanged

        val announcer = SystemAnnouncer(synth, cache)

        val result1 = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()
        assertTrue(result1 is AnnouncementResult.Ready)

        val sco1 = FakeScoRoute()
        val output1 = RecordingPcmOutput()
        announcer.announce("a", sco1, output1)
        advanceUntilIdle()
        assertEquals(1, output1.playbackCount)
        assertEquals(0, output1.beepCount)

        synth.modelStatus = dev.nilp0inter.subspace.model.TtsModelStatus.Failed
        synth.loadError = "model error"

        val result2 = announcer.precompute(vocab, "/tmp/M1.json", 16000)
        advanceUntilIdle()
        assertTrue(result2 is AnnouncementResult.Failed)

        val sco2 = FakeScoRoute()
        val output2 = RecordingPcmOutput()
        announcer.announce("a", sco2, output2)
        advanceUntilIdle()
        assertEquals(0, output2.playbackCount)
        assertEquals(1, output2.beepCount)
    }

    @Test
    fun persistentCacheConcurrentPrecompute() = runTest {
        val synth = FakeTtsSynthesizer()
        val cache = mockk<AnnouncementPcmCache>()
        val vocabA = linkedMapOf("a" to "alpha")
        val vocabB = linkedMapOf("b" to "beta")

        val executionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())

        coEvery { cache.load(vocabA, any()) } coAnswers {
            executionOrder.add("load_start_a")
            delay(100)
            executionOrder.add("load_end_a")
            emptyMap()
        }
        coEvery { cache.load(vocabB, any()) } coAnswers {
            executionOrder.add("load_start_b")
            delay(100)
            executionOrder.add("load_end_b")
            emptyMap()
        }
        coEvery { cache.commit(any(), any(), any()) } returns AnnouncementCacheCommitResult.Unchanged

        val announcer = SystemAnnouncer(synth, cache)

        val jobA = launch {
            announcer.precompute(vocabA, "/tmp/M1.json", 16000)
        }
        delay(10)
        val jobB = launch {
            announcer.precompute(vocabB, "/tmp/M1.json", 16000)
        }

        jobA.join()
        jobB.join()

        assertEquals(
            listOf("load_start_a", "load_end_a", "load_start_b", "load_end_b"),
            executionOrder
        )
    }

    @Test
    fun persistentCacheSummaryLogVerification() = runTest {
        mockkStatic(android.util.Log::class)
        val logs = mutableListOf<String>()
        every { android.util.Log.i("SystemAnnouncer", capture(logs)) } returns 0

        try {
            val synth = FakeTtsSynthesizer()
            val cache = mockk<AnnouncementPcmCache>()
            val announcer = SystemAnnouncer(synth, cache)

            // Scenario 1: Ready
            val vocab1 = linkedMapOf("a" to "alpha", "b" to "beta")
            val pcm = RecordedPcm(shortArrayOf(1, 2, 3), 16000)
            coEvery { cache.load(vocab1, any()) } returns mapOf("a" to pcm)
            coEvery { cache.commit(vocab1, any(), any()) } returns AnnouncementCacheCommitResult.Unchanged
            
            announcer.precompute(vocab1, "/tmp/M1.json", 16000)
            advanceUntilIdle()

            assertEquals(1, logs.size)
            assertEquals(
                "ANNOUNCEMENT_CACHE_SUMMARY hits=1 misses=1 syntheses=1 commit=unchanged outcome=ready",
                logs[0]
            )
            logs.clear()

            // Scenario 2: Failed
            val vocab2 = linkedMapOf("c" to "gamma")
            coEvery { cache.load(vocab2, any()) } returns emptyMap()
            synth.setOutcome(SynthesisOutcome.Failure("synthesizer error"))

            announcer.precompute(vocab2, "/tmp/M1.json", 16000)
            advanceUntilIdle()

            assertEquals(1, logs.size)
            assertEquals(
                "ANNOUNCEMENT_CACHE_SUMMARY hits=0 misses=1 syntheses=1 commit=skipped outcome=failed",
                logs[0]
            )
            logs.clear()

            // Scenario 3: Cancelled
            val vocab3 = linkedMapOf("d" to "delta")
            coEvery { cache.load(vocab3, any()) } coAnswers {
                delay(1000)
                emptyMap()
            }

            val job = launch {
                try {
                    announcer.precompute(vocab3, "/tmp/M1.json", 16000)
                } catch (e: Exception) {
                    // Ignore cancellation
                }
            }
            delay(50)
            job.cancel()
            job.join()

            assertEquals(1, logs.size)
            assertEquals(
                "ANNOUNCEMENT_CACHE_SUMMARY hits=0 misses=0 syntheses=0 commit=skipped outcome=cancelled",
                logs[0]
            )

        } finally {
            unmockkStatic(android.util.Log::class)
        }
    }
}
