package dev.nilp0inter.subspace.service

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubspaceLoggerChannelTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "subspace-channel-test")
        SubspaceLogger.initialize(tempDir)
        SubspaceLogger.clear()
        SubspaceLogger.setGlobalLevel(LogLevel.Verbose)
        SubspaceLogger.clearAllTagLevels()
    }

    @After
    fun tearDown() {
        SubspaceLogger.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun loggingCallReturnsImmediatelyWithoutBlocking() {
        // A logging call on a "critical thread" must return in well under
        // the time it takes to write to disk. We measure the call time
        // and assert it's sub-millisecond (the channel push is non-blocking).
        val start = System.nanoTime()
        SubspaceLogger.d("NonBlockingTest", "x".repeat(2000))
        val elapsedNanos = System.nanoTime() - start

        // trySend on an unlimited channel should complete in microseconds.
        // Use a generous 5ms ceiling to avoid flakiness on slow CI.
        val elapsedMs = elapsedNanos / 1_000_000
        assertTrue(
            "Log call took ${elapsedMs}ms — expected non-blocking (<5ms)",
            elapsedMs < 5,
        )
    }

    @Test
    fun manyRapidLogCallsDoNotBlock() {
        // Simulate high-frequency logging from a critical thread.
        val start = System.nanoTime()
        for (i in 0 until 1000) {
            SubspaceLogger.d("BurstTest", "entry $i")
        }
        val elapsedNanos = System.nanoTime() - start

        // 1000 non-blocking channel sends should complete in under 100ms.
        val elapsedMs = elapsedNanos / 1_000_000
        assertTrue(
            "1000 log calls took ${elapsedMs}ms — expected non-blocking (<100ms)",
            elapsedMs < 100,
        )

        // Wait for the dispatcher to drain
        runBlocking {
            delay(500)
        }

        val entries = SubspaceLogger.entries.value
        assertEquals(1000, entries.size)
    }

    @Test
    fun entriesFlowUpdatesAsynchronously() {
        SubspaceLogger.i("FlowTest", "async entry")

        // The entry should appear in the StateFlow after the dispatcher drains
        runBlocking {
            delay(300)
        }

        val entries = SubspaceLogger.entries.value
        assertTrue(entries.isNotEmpty())
        assertEquals("FlowTest", entries.last().tag)
        assertEquals(LogLevel.Info, entries.last().level)
    }
}