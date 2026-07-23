package dev.nilp0inter.subspace.audiofile

import dev.nilp0inter.subspace.storage.BackendFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.6/6.7: Recording export.
 *
 * WAV/PCM export borrows (never consumes) the Recording and publishes only a complete authorized
 * destination under create-new/replace semantics with exact metadata/bytes. OGG/Vorbis export
 * reuses the injected encoder through quota-bound app-private staging, streams the complete
 * artifact to the mount, exposes no temporary path or codec-native diagnostic, and cleans staging
 * on success, encoder failure, provider failure, cancellation, and timeout.
 */
class AudioFileExportTest {

    private val samples = shortArrayOf(0, 1, -1, 32767, -32768, 2, -2, 3)
    private val rate = 16_000
    private val wavNew = AudioExportOptions(AudioFileFormat.WAV_PCM_S16LE, AudioExportMode.CREATE_NEW)
    private val wavReplace = AudioExportOptions(AudioFileFormat.WAV_PCM_S16LE, AudioExportMode.REPLACE)
    private val oggNew = AudioExportOptions(AudioFileFormat.OGG_VORBIS, AudioExportMode.CREATE_NEW)

    private fun code(o: AudioFileOutcome<*>): AudioFileErrorCode = o.audioFailure().code

    private fun pcm() = PcmMonoS16Le(samples, rate)

    // -- WAV export (6.6) ---------------------------------------------------

    @Test
    fun exportWavCreateNewWritesExactCompleteDestination() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew).audioSuccess()

        val expected = WavPcm16.encode(pcm())
        assertArrayEquals(expected, h.mem.fileContent(listOf("capture.wav")))

        assertEquals(AudioExportStatus.WRITTEN, result.status)
        assertEquals(AudioFileFormat.WAV_PCM_S16LE, result.format)
        assertEquals(rate, result.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(samples.size.toLong() * 1000 / rate, result.durationMs)
        assertEquals(expected.size.toLong(), result.bytes)
        // WAV export writes directly through the mount: no staging is used.
        assertEquals(0, h.staging.activeFileCount)
    }

    @Test
    fun exportWavBorrowsAndDoesNotConsume() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavNew).audioSuccess()
        assertEquals(1, h.recordings.liveCount()) // still live after export
        // A second export of the same borrowed Recording succeeds (replace to reuse the path).
        h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavReplace).audioSuccess()
        assertEquals(1, h.recordings.liveCount())
    }

    @Test
    fun exportWavReplaceOverwritesExistingDestination() = runTest {
        val h = AudioFileHarness()
        h.mem.seedFile(listOf("capture.wav"), byteArrayOf(9, 9, 9))
        val rec = h.recording(pcm())
        h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavReplace).audioSuccess()
        assertArrayEquals(WavPcm16.encode(pcm()), h.mem.fileContent(listOf("capture.wav")))
    }

    @Test
    fun exportWavCreateNewOnExistingIsExists() = runTest {
        val h = AudioFileHarness()
        val original = byteArrayOf(9, 9, 9)
        h.mem.seedFile(listOf("capture.wav"), original)
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_EXISTS, code(result))
        assertArrayEquals(original, h.mem.fileContent(listOf("capture.wav"))) // unchanged
    }

    @Test
    fun exportWavToDirectoryIsInvalidValue() = runTest {
        val h = AudioFileHarness()
        h.mem.seedDir(listOf("capture.wav"))
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_INVALID_VALUE, code(result))
    }

    @Test
    fun exportWavMissingParentIsNotFound() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "nodir/capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_NOT_FOUND, code(result))
    }

    @Test
    fun exportWavAbsolutePathIsInvalidPath() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "/capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_INVALID_PATH, code(result))
    }

    @Test
    fun exportWavProviderFailurePublishesNothing() = runTest {
        val h = AudioFileHarness()
        h.mem.writeFailureDuringPublish = BackendFailure.NO_SPACE
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_NO_SPACE, code(result))
        assertFalse(h.mem.exists(listOf("capture.wav"))) // complete-on-success: no partial node
    }

    @Test
    fun exportWavThrownFailureLeaksNoPlatformDetail() = runTest {
        val h = AudioFileHarness()
        h.mem.throwOn = "writeFile"
        val rec = h.recording(pcm())
        val error = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew).audioFailure()
        assertEquals(AudioFileErrorCode.E_IO, error.code)
        val reason = error.reason ?: ""
        assertTrue(reason, !reason.contains("content://"))
        assertTrue(reason, !reason.contains("/storage"))
        assertTrue(reason, !reason.contains("SAF"))
    }

    // -- OGG export (6.7): reuse encoder via app-private staging ------------

    @Test
    fun exportOggReusesEncoderAndStreamsStagedArtifactToMount() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew).audioSuccess()

        // The reused encoder was invoked exactly once with the borrowed PCM and rate.
        assertEquals(1, h.encoder.calls.size)
        assertEquals(samples.size, h.encoder.calls[0].pcmSize)
        assertEquals(rate, h.encoder.calls[0].sampleRate)
        // The encoder wrote to app-private staging, not the mount path.
        assertTrue(h.encoder.calls[0].absolutePath, h.encoder.calls[0].absolutePath.contains("audio-stage"))

        // The complete staged artifact was streamed to the authorized mount destination.
        assertArrayEquals(h.encoder.payload, h.mem.fileContent(listOf("recording.ogg")))

        assertEquals(AudioExportStatus.WRITTEN, result.status)
        assertEquals(AudioFileFormat.OGG_VORBIS, result.format)
        assertEquals(rate, result.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(h.encoder.payload.size.toLong(), result.bytes)

        // Staging cleaned on success: no live file, budgets released, artifact deleted.
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
        assertEquals(0L, h.staging.processStagingBytes)
    }

    @Test
    fun exportOggResultCarriesNoStagingPathOrDiagnostic() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew).audioSuccess()
        // The success result exposes only portable scalars; there is no path/diagnostic surface.
        val stagingPath = h.encoder.calls[0].absolutePath
        assertEquals(AudioFileFormat.OGG_VORBIS, result.format)
        assertFalse(result.toString().contains(stagingPath))
    }

    @Test
    fun exportOggEncoderFailureIsNormalizedHostFailureWithoutDiagnosticLeak() = runTest {
        val h = AudioFileHarness()
        h.encoder.failEncode = true
        val rec = h.recording(pcm())
        val error = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew).audioFailure()
        assertEquals(AudioFileErrorCode.E_HOST_FAILURE, error.code)
        val reason = error.reason ?: ""
        assertTrue(reason, !reason.contains("vorbis"))
        assertTrue(reason, !reason.contains("code=-7"))
        // Staging cleaned and no destination published on codec failure.
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    @Test
    fun exportOggEmptyEncoderOutputIsHostFailure() = runTest {
        val h = AudioFileHarness()
        h.encoder.produceNothing = true
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_HOST_FAILURE, code(result))
        assertEquals(0, h.staging.activeFileCount)
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    @Test
    fun exportOggCreateNewOnExistingFailsBeforeStaging() = runTest {
        val h = AudioFileHarness()
        h.mem.seedFile(listOf("recording.ogg"), byteArrayOf(1, 2, 3))
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_EXISTS, code(result))
        // Destination admission is validated before any codec/staging effect.
        assertTrue(h.encoder.calls.isEmpty())
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
    }

    @Test
    fun exportOggPerOperationStagingCeilingIsTooLarge() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(maxStagingBytesPerOperation = 4))
        val rec = h.recording(pcm()) // encoder payload defaults to 8 bytes > 4 ceiling
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(result))
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    @Test
    fun exportOggStagingBudgetExhaustionIsTooLarge() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(maxStagingBytesPerGeneration = 4))
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(result))
        assertTrue(h.encoder.calls.isEmpty()) // rejected before the codec runs
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    @Test
    fun exportOggProviderFailureDuringPublishCleansStaging() = runTest {
        val h = AudioFileHarness()
        h.mem.writeFailureDuringPublish = BackendFailure.NO_SPACE
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_NO_SPACE, code(result))
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    @Test
    fun exportOggCancellationDuringEncodeCleansStagingAndPublishesNothing() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        h.encoder.gate = CompletableDeferred()
        val handle = h.handle()
        val job = launch { h.adapter.export(h.owner, rec, handle, "recording.ogg", oggNew) }
        yield() // op admits, acquires staging, suspends inside the encoder
        assertEquals(1, h.staging.activeFileCount)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
        assertEquals(0, h.adapter.operations.activeCount)
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    @Test
    fun exportOggTimeoutDuringEncodeCleansStaging() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(operationDeadlineMs = 50))
        val rec = h.recording(pcm())
        h.encoder.encodeDelayMs = 10_000 // virtual time; withTimeout(50) fires first
        val result = h.adapter.export(h.owner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_TIMEOUT, code(result))
        assertEquals(0, h.staging.activeFileCount)
        assertEquals(0L, h.staging.generationStagingBytes)
        assertEquals(0, h.adapter.operations.activeCount)
        assertFalse(h.mem.exists(listOf("recording.ogg")))
    }

    // -- Ownership: export borrows the current execution's Recording --------

    @Test
    fun exportForeignRecordingIsInvalidArgument() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm()) // owned by h.owner
        val result = h.adapter.export(h.taskOwner, rec, h.handle(), "recording.ogg", oggNew)
        assertEquals(AudioFileErrorCode.E_INVALID_ARGUMENT, code(result))
        assertTrue(h.encoder.calls.isEmpty()) // rejected before any codec effect
    }

    @Test
    fun exportStaleRecordingIsStale() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm(), h.owner)
        val futureOwner = ExecutionOwner("input-1", ExecutionOwnerKind.INPUT, generation = 99)
        val result = h.adapter.export(futureOwner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_STALE, code(result))
    }
}
