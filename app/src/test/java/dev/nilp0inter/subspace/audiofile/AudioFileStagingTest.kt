package dev.nilp0inter.subspace.audiofile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 6.8: Quota-bound app-private codec staging.
 *
 * Proves finite per-operation/generation/process byte limits, exact-once cleanup that deletes the
 * temporary artifact and releases the accounted budget back to the ledgers, per-operation ceiling
 * enforcement on the actual encoded size, fail-closed behavior after close, and generation-only
 * reset.
 */
class AudioFileStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun staging(limits: AudioFileLimits = AudioFileLimits()) =
        AudioFileStaging(tmp.newFolder(), limits)

    private fun code(o: AudioFileOutcome<*>): AudioFileErrorCode = o.audioFailure().code

    @Test
    fun acquireCommitReleaseTracksActualBytesAndDeletesFile() {
        val s = staging()
        val staged = s.acquire(100).audioSuccess()
        assertEquals(100L, s.generationStagingBytes)
        assertEquals(100L, s.processStagingBytes)
        assertEquals(1, s.activeFileCount)

        // The codec writes its real output; commit reconciles the reserve down to the actual size.
        staged.file.writeBytes(ByteArray(30))
        s.commit(staged, 30).audioSuccess()
        assertEquals(30L, s.generationStagingBytes)
        assertEquals(30L, s.processStagingBytes)

        s.release(staged)
        assertEquals(0L, s.generationStagingBytes)
        assertEquals(0L, s.processStagingBytes)
        assertEquals(0, s.activeFileCount)
        assertFalse(staged.file.exists()) // temporary artifact deleted
    }

    @Test
    fun releaseIsExactOnceAndNeverDoubleReleases() {
        val s = staging()
        val staged = s.acquire(100).audioSuccess()
        s.commit(staged, 40).audioSuccess()
        s.release(staged)
        assertEquals(0L, s.generationStagingBytes)
        // Second release is a no-op: budget does not go negative.
        s.release(staged)
        assertEquals(0L, s.generationStagingBytes)
        assertEquals(0L, s.processStagingBytes)
        assertEquals(0, s.activeFileCount)
    }

    @Test
    fun generationBudgetExhaustionRejectsTooLarge() {
        val s = staging(AudioFileLimits(maxStagingBytesPerGeneration = 100, maxStagingBytesPerProcess = 1_000_000))
        s.acquire(60).audioSuccess()
        val second = s.acquire(60) // 60 + 60 > 100
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(second))
        assertEquals(60L, s.generationStagingBytes) // rejected acquire reserved nothing
    }

    @Test
    fun processBudgetExhaustionSurvivesGenerationReset() {
        val s = staging(AudioFileLimits(maxStagingBytesPerGeneration = 1_000_000, maxStagingBytesPerProcess = 100))
        val first = s.acquire(60).audioSuccess()
        assertEquals(60L, s.processStagingBytes)
        s.release(first) // frees generation AND process; reacquire to hold process only
        val held = s.acquire(60).audioSuccess()
        s.resetGeneration() // resets generation, never process
        assertEquals(0L, s.generationStagingBytes)
        assertEquals(60L, s.processStagingBytes)
        val over = s.acquire(60) // process 60 + 60 > 100
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(over))
        s.release(held)
    }

    @Test
    fun commitOverPerOperationCeilingRejectsTooLarge() {
        val s = staging(AudioFileLimits(maxStagingBytesPerOperation = 50))
        val staged = s.acquire(100).audioSuccess()
        val committed = s.commit(staged, 60) // 60 > 50 per-op ceiling
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(committed))
        s.release(staged)
        assertEquals(0L, s.generationStagingBytes)
    }

    @Test
    fun acquireAfterCloseFailsClosed() {
        val s = staging()
        s.close()
        assertTrue(s.isClosed)
        assertEquals(AudioFileErrorCode.E_CLOSED, code(s.acquire(10)))
    }

    @Test
    fun closeIsIdempotent() {
        val s = staging()
        s.close()
        s.close()
        assertEquals(AudioFileErrorCode.E_CLOSED, code(s.acquire(10)))
    }

    @Test
    fun resetGenerationClearsGenerationBudgetOnly() {
        val s = staging()
        val staged = s.acquire(100).audioSuccess()
        assertEquals(100L, s.generationStagingBytes)
        assertEquals(100L, s.processStagingBytes)
        s.resetGeneration()
        assertEquals(0L, s.generationStagingBytes)
        assertEquals(100L, s.processStagingBytes) // process never reset
        s.release(staged)
    }
}
