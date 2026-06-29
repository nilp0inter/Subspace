package dev.nilp0inter.subspace.audio

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JournalWavWriterTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("journal-wav-test", ".wav")
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun finalizeDuringInFlightWriteSerializesAndDoesNotThrow() {
        val writer = JournalWavWriter(tempFile, SAMPLE_RATE)
        val chunk = ShortArray(SAMPLE_RATE / 10) { 1 } // 100 ms chunk

        val writeStarted = CountDownLatch(1)
        val finalizeDone = CountDownLatch(1)
        val writeDone = CountDownLatch(1)

        val writeThread = Thread {
            try {
                writeStarted.countDown()
                writer.writeChunk(chunk)
                writeDone.countDown()
            } catch (e: Throwable) {
                fail("writeChunk threw during concurrent finalize: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        val finalizeThread = Thread {
            try {
                writeStarted.await(1, TimeUnit.SECONDS)
                writer.finalize()
                finalizeDone.countDown()
            } catch (e: Throwable) {
                fail("finalize threw during concurrent write: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        writeThread.start()
        finalizeThread.start()

        assertTrue("writeChunk did not complete in time", writeDone.await(2, TimeUnit.SECONDS))
        assertTrue("finalize did not complete in time", finalizeDone.await(2, TimeUnit.SECONDS))

        writeThread.join(1_000)
        finalizeThread.join(1_000)

        // Resulting WAV must be well-formed: RIFF header, non-zero data, readable.
        val wav = WavPcmReader.read(tempFile)
        assertTrue("WAV must be readable after concurrent finalize", wav != null)
        wav!!
        assertEquals(SAMPLE_RATE, wav.sampleRate)
        assertEquals(1, wav.channelCount)
        assertEquals(16, wav.bitsPerSample)
        assertTrue("expected non-empty samples, got ${wav.samples.size}", wav.samples.isNotEmpty())
    }

    @Test
    fun writeChunkAfterFinalizeIsNoOp() {
        val writer = JournalWavWriter(tempFile, SAMPLE_RATE)
        val initialChunk = ShortArray(8) { 7 }
        writer.writeChunk(initialChunk)
        writer.finalize()

        val sizeBeforeLateWrite = tempFile.length()
        val lateChunk = ShortArray(16) { 99 }
        writer.writeChunk(lateChunk) // must not throw and must not extend the file

        val sizeAfterLateWrite = tempFile.length()
        assertEquals(
            "file size must be unchanged after a post-finalize writeChunk",
            sizeBeforeLateWrite,
            sizeAfterLateWrite,
        )

        val wav = WavPcmReader.read(tempFile)
        assertTrue(wav != null)
        wav!!
        assertEquals(8, wav.samples.size)
        for (s in wav.samples) assertEquals(7.toShort(), s)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}