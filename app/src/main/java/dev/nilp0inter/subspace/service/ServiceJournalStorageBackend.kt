package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.JournalWavWriter
import dev.nilp0inter.subspace.channel.CaptureState
import dev.nilp0inter.subspace.channel.CaptureTaskState
import dev.nilp0inter.subspace.channel.DerivedTaskState
import dev.nilp0inter.subspace.channel.DerivedTaskStatus
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.JournalEntryMetadata
import dev.nilp0inter.subspace.channel.JournalEntryPaths
import dev.nilp0inter.subspace.channel.JournalMetadataStore
import dev.nilp0inter.subspace.channel.MetadataChannelSnapshot
import dev.nilp0inter.subspace.channel.capability.JournalDerivation
import dev.nilp0inter.subspace.channel.capability.JournalEntryHandle
import dev.nilp0inter.subspace.channel.capability.JournalEntryRequest
import dev.nilp0inter.subspace.channel.capability.JournalStorageBackend
import dev.nilp0inter.subspace.channel.capability.JournalStoredCapture
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording
import dev.nilp0inter.subspace.channel.capability.journalDerivation
import dev.nilp0inter.subspace.channel.capability.journalEntryHandle
import dev.nilp0inter.subspace.channel.capability.journalStoredCapture
import dev.nilp0inter.subspace.channel.capability.recordedPcmOf
import dev.nilp0inter.subspace.model.JournalProviderConfiguration
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-only Journal persistence adapter for one channel instance.
 *
 * Runtime code receives only opaque entry handles. Durable derivative work stays on the shared
 * Journal controller and is deliberately not awaited by the bounded PTT terminal callback.
 */
internal class ServiceJournalStorageBackend(
    private val instanceId: String,
    private val configuration: JournalProviderConfiguration,
    private val journalController: JournalController,
    private val paths: JournalEntryPaths = JournalEntryPaths(),
    private val metadata: JournalMetadataStore = JournalMetadataStore(),
) : JournalStorageBackend {
    private data class Entry(
        val paths: JournalEntryPaths.EntryPaths,
        val request: JournalEntryRequest,
        val startedAt: ZonedDateTime,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private var recovered = false

    init {
        require(instanceId.isNotBlank()) { "Journal instance ID must not be blank" }
        require(!configuration.baseDirectory.isNullOrBlank()) { "Journal storage directory is not configured" }
    }

    override suspend fun createEntry(request: JournalEntryRequest): JournalEntryHandle {
        recoverOnce()
        val startedAt = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(request.capturedAtEpochMillis),
            ZoneId.systemDefault(),
        )
        val entryPaths = paths.preparePaths(File(checkNotNull(configuration.baseDirectory)), startedAt)
        val handle = journalEntryHandle()
        entries[handle.operationId] = Entry(entryPaths, request, startedAt)
        metadata.write(
            JournalEntryMetadata(
                entryId = entryPaths.stem,
                startedAt = startedAt.toString(),
                timezoneOffset = entryPaths.timezoneOffset,
                channel = MetadataChannelSnapshot(instanceId, request.saveVoice, request.saveText),
                capture = CaptureState(CaptureTaskState.recording),
            ),
            entryPaths.metadataFile,
        )
        return handle
    }

    override suspend fun storeCapture(
        entry: JournalEntryHandle,
        recording: OpaqueAudioRecording,
    ): JournalStoredCapture {
        val stored = entries.remove(entry.operationId) ?: error("Unknown Journal entry")
        val pcm = recordedPcmOf(recording) ?: error("Invalid opaque Journal recording")
        val writer = JournalWavWriter(stored.paths.captureFile, pcm.sampleRate)
        try {
            writer.writeChunk(pcm.samples)
        } finally {
            writer.finalize()
        }
        val capture = if (pcm.isEmpty) {
            CaptureState(CaptureTaskState.failed, error = "Empty capture")
        } else {
            CaptureState(
                state = CaptureTaskState.finished,
                path = stored.paths.captureFile.name,
                sampleRate = pcm.sampleRate,
                channels = 1,
                encoding = "pcm_s16le",
                durationMs = pcm.samples.size * 1_000L / pcm.sampleRate,
                bytes = pcm.samples.size.toLong() * 2L,
            )
        }
        metadata.write(
            JournalEntryMetadata(
                entryId = stored.paths.stem,
                startedAt = stored.startedAt.toString(),
                endedAt = ZonedDateTime.now().toString(),
                timezoneOffset = stored.paths.timezoneOffset,
                channel = MetadataChannelSnapshot(instanceId, stored.request.saveVoice, stored.request.saveText),
                capture = capture,
                encoding = if (capture.state == CaptureTaskState.finished && stored.request.saveVoice) {
                    DerivedTaskState(DerivedTaskStatus.pending)
                } else {
                    DerivedTaskState(DerivedTaskStatus.skipped)
                },
                transcription = if (capture.state == CaptureTaskState.finished && stored.request.saveText) {
                    DerivedTaskState(DerivedTaskStatus.pending)
                } else {
                    DerivedTaskState(DerivedTaskStatus.skipped)
                },
            ),
            stored.paths.metadataFile,
        )
        if (capture.state == CaptureTaskState.finished) {
            journalController.processCaptureFile(stored.paths)
        }
        return journalStoredCapture(entry.operationId)
    }

    override suspend fun derive(entry: JournalEntryHandle): JournalDerivation = journalDerivation(entry.operationId)

    private fun recoverOnce() {
        if (recovered) return
        synchronized(this) {
            if (!recovered) {
                journalController.runRecovery(File(checkNotNull(configuration.baseDirectory)))
                recovered = true
            }
        }
    }
}
