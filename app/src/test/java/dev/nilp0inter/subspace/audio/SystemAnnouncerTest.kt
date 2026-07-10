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
}
