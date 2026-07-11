package dev.nilp0inter.subspace.service

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubspaceLoggerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "subspace-log-test")
        SubspaceLogger.initialize(tempDir)
        SubspaceLogger.clear()
        SubspaceLogger.setGlobalLevel(LogLevel.Debug)
        SubspaceLogger.clearAllTagLevels()
    }

    @After
    fun tearDown() {
        SubspaceLogger.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun globalLevelFilterAllowsAtOrAboveThreshold() {
        SubspaceLogger.setGlobalLevel(LogLevel.Warn)

        SubspaceLogger.d("TestTag", "debug msg - should be filtered")
        SubspaceLogger.w("TestTag", "warn msg - should pass")
        SubspaceLogger.e("TestTag", "error msg - should pass")

        runBlocking {
            delay(200)
        }

        val entries = SubspaceLogger.entries.value
        assertEquals(2, entries.size)
        assertEquals(LogLevel.Warn, entries[0].level)
        assertEquals(LogLevel.Error, entries[1].level)
    }

    @Test
    fun perTagLevelOverridesGlobal() {
        SubspaceLogger.setGlobalLevel(LogLevel.Error)
        SubspaceLogger.setTagLevel("SpecialTag", LogLevel.Debug)

        SubspaceLogger.d("SpecialTag", "debug passes via tag override")
        SubspaceLogger.d("OtherTag", "debug filtered by global")

        runBlocking {
            delay(200)
        }

        val entries = SubspaceLogger.entries.value
        assertEquals(1, entries.size)
        assertEquals("SpecialTag", entries[0].tag)
        assertEquals(LogLevel.Debug, entries[0].level)
    }

    @Test
    fun clearTagLevelRevertsToGlobal() {
        SubspaceLogger.setGlobalLevel(LogLevel.Error)
        SubspaceLogger.setTagLevel("ClearableTag", LogLevel.Debug)
        SubspaceLogger.clearTagLevel("ClearableTag")

        SubspaceLogger.d("ClearableTag", "should be filtered by global")

        runBlocking {
            delay(200)
        }

        val entries = SubspaceLogger.entries.value
        assertEquals(0, entries.size)
    }

    @Test
    fun diskRotationKeepsTotalSizeBounded() {
        SubspaceLogger.setGlobalLevel(LogLevel.Verbose)

        // Write enough entries to trigger at least one rotation (>1 MB)
        val longMessage = "x".repeat(500)
        for (i in 0 until 4000) {
            SubspaceLogger.d("RotationTest", "$i:$longMessage")
        }

        runBlocking {
            delay(1000)
        }

        val activeFile = File(tempDir, "subspace-logs/subspace_logs.0.log")
        val previousFile = File(tempDir, "subspace-logs/subspace_logs.1.log")

        assertTrue("Active file should exist", activeFile.exists())
        assertTrue("Previous file should exist after rotation", previousFile.exists())

        val totalSize = activeFile.length() + previousFile.length()
        assertTrue(
            "Total disk usage ($totalSize bytes) should be bounded to ~2 MB",
            totalSize <= 2 * 1_048_576L + 4096,
        )
    }

    @Test
    fun logsSurviveReinitialize() {
        SubspaceLogger.i("PersistTest", "entry before restart")

        runBlocking {
            delay(300)
        }

        assertEquals(1, SubspaceLogger.entries.value.size)

        // Re-initialize from the same directory
        SubspaceLogger.initialize(tempDir)

        val restored = SubspaceLogger.entries.value
        assertTrue(
            "Historical logs should survive reinitialization",
            restored.any { it.tag == "PersistTest" && it.message == "entry before restart" },
        )
    }

    @Test
    fun clearEmptiesEntriesAndDisk() {
        SubspaceLogger.i("ClearTest", "entry to be cleared")

        runBlocking {
            delay(200)
        }

        assertTrue(SubspaceLogger.entries.value.isNotEmpty())

        SubspaceLogger.clear()

        assertEquals(0, SubspaceLogger.entries.value.size)
        assertEquals(0L, File(tempDir, "subspace-logs/subspace_logs.0.log").length())
    }

    @Test
    fun globalLevelFlowReflectsChanges() {
        val initialLevel = SubspaceLogger.globalLevelFlow.value
        SubspaceLogger.setGlobalLevel(LogLevel.Error)
        assertEquals(LogLevel.Error, SubspaceLogger.globalLevelFlow.value)
        SubspaceLogger.setGlobalLevel(initialLevel)
    }

    @Test
    fun perTagLevelFlowReflectsChanges() {
        SubspaceLogger.setTagLevel("FlowTag", LogLevel.Warn)
        assertEquals(LogLevel.Warn, SubspaceLogger.perTagLevelFlow.value["FlowTag"])
        SubspaceLogger.clearTagLevel("FlowTag")
        assertFalse(SubspaceLogger.perTagLevelFlow.value.containsKey("FlowTag"))
    }
}