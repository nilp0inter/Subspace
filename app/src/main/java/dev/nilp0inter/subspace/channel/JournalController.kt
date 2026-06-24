package dev.nilp0inter.subspace.channel

import android.util.Log
import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.WavInfo
import dev.nilp0inter.subspace.audio.WavPcmReader
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JournalController(
    private val scope: CoroutineScope,
    private val encoder: AudioEncoder,
    private val transcriber: PcmTranscriber,
    private val metadataStore: JournalMetadataStore = JournalMetadataStore(),
    private val markdownRenderer: MarkdownJournalRenderer = MarkdownJournalRenderer(),
    private val entryDiscovery: JournalEntryDiscovery = JournalEntryDiscovery(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun processCaptureFile(paths: JournalEntryPaths.EntryPaths): Job {
        return scope.launch(dispatcher) {
            deriveArtifacts(paths)
        }
    }

    fun recoverEntry(paths: JournalEntryPaths.EntryPaths, metadata: JournalEntryMetadata) {
        scope.launch(Dispatchers.Default) {
            deriveArtifacts(paths)
        }
    }

    private suspend fun deriveArtifacts(paths: JournalEntryPaths.EntryPaths) {
        val metadata = metadataStore.read(paths.metadataFile) ?: return
        if (metadata.capture.state != CaptureTaskState.finished) return

        val wavInfo = WavPcmReader.read(paths.captureFile) ?: run {
            try { Log.e(TAG, "Cannot read capture WAV: ${paths.captureFile}") } catch (_: RuntimeException) {}
            return
        }

        val newEncoding = deriveEncoding(metadata, paths, wavInfo)
        val newTranscription = deriveTranscription(metadata, paths)
        val updated = metadata.copy(
            encoding = newEncoding ?: metadata.encoding,
            transcription = newTranscription ?: metadata.transcription,
        )
        metadataStore.write(updated, paths.metadataFile)

        if (newEncoding?.state == DerivedTaskStatus.finished
            && newTranscription?.state == DerivedTaskStatus.finished) {
            paths.captureFile.delete()
            val deleted = updated.copy(capture = updated.capture.copy(state = CaptureTaskState.deleted))
            metadataStore.write(deleted, paths.metadataFile)
        }

        regenerateDayMarkdown(paths.dayDirectory)
    }

    private suspend fun deriveEncoding(
        metadata: JournalEntryMetadata,
        paths: JournalEntryPaths.EntryPaths,
        wavInfo: WavInfo,
    ): DerivedTaskState? {
        val encoding = metadata.encoding ?: return null
        if (encoding.state != DerivedTaskStatus.pending) return null
        if (!metadata.channel.saveVoice) return null

        val store = metadataStore
        store.write(metadata.copy(encoding = encoding.copy(state = DerivedTaskStatus.running)), paths.metadataFile)

        val result = encoder.encode(wavInfo.samples, paths.recordingFile, wavInfo.sampleRate)
        return if (result.isSuccess) {
            DerivedTaskState(
                state = DerivedTaskStatus.finished,
                path = paths.recordingFile.name,
            )
        } else {
            paths.recordingFile.delete()
            DerivedTaskState(
                state = DerivedTaskStatus.failed,
                error = result.exceptionOrNull()?.message ?: "encoding failed",
            )
        }
    }

    private suspend fun deriveTranscription(
        metadata: JournalEntryMetadata,
        paths: JournalEntryPaths.EntryPaths,
    ): DerivedTaskState? {
        val transcription = metadata.transcription ?: return null
        if (transcription.state != DerivedTaskStatus.pending) return null
        if (!metadata.channel.saveText) return null

        val store = metadataStore
        store.write(metadata.copy(transcription = transcription.copy(state = DerivedTaskStatus.running)), paths.metadataFile)

        val wavInfo = WavPcmReader.read(paths.captureFile) ?: return DerivedTaskState(
            state = DerivedTaskStatus.failed,
            error = "Cannot read capture WAV",
        )

        return try {
            val text = transcriber.transcribe(wavInfo.samples, wavInfo.sampleRate)
            DerivedTaskState(
                state = DerivedTaskStatus.finished,
                text = text,
            )
        } catch (e: Exception) {
            try { Log.e(TAG, "Journal transcription failed", e) } catch (_: RuntimeException) {}
            DerivedTaskState(
                state = DerivedTaskStatus.failed,
                error = e.message ?: "transcription failed",
            )
        }
    }

    fun regenerateDayMarkdown(dayDirectory: File) {
        val entries = entryDiscovery.findMetadataForDay(dayDirectory)
            .filterNot { (_, m) -> m.deletedAt != null }
            .sortedBy { (_, m) -> m.startedAt }
        markdownRenderer.render(dayDirectory, entries.map { it.second })
    }

    fun regenerateAllMarkdown(baseDirectory: File) {
        val allEntries = entryDiscovery.findAllMetadataFiles(baseDirectory)
        val byDay = allEntries
            .filterNot { (_, m) -> m.deletedAt != null }
            .groupBy { (_, m) ->
                val dateStr = m.startedAt.substringBefore("T")
                File(baseDirectory, "${dateStr.substring(0..3)}/${dateStr.substring(0..6)}-${dateStr.substring(5..6)}/$dateStr")
            }
        for ((dayDir, entries) in byDay) {
            markdownRenderer.render(dayDir, entries.map { it.second }.sortedBy { it.startedAt })
        }
    }

    fun runRecovery(baseDirectory: File): Job {
        return scope.launch(dispatcher) {
            val entries = entryDiscovery.findAllMetadataFiles(baseDirectory)
            val changedDays = mutableSetOf<File>()

            for ((file, metadata) in entries) {
                val updated = reconcileStates(metadata, baseDirectory)
                if (updated != metadata) {
                    metadataStore.write(updated, file)
                    val dateStr = metadata.startedAt.substringBefore("T")
                    val dayDir = File(baseDirectory,
                        "${dateStr.substring(0..3)}/${dateStr.substring(0..6)}-${dateStr.substring(5..6)}/$dateStr")
                    changedDays.add(dayDir)
                }
            }

            for (dayDir in changedDays) {
                regenerateDayMarkdown(dayDir)
            }
        }
    }

    private fun reconcileStates(metadata: JournalEntryMetadata, baseDirectory: File): JournalEntryMetadata {
        var m = metadata

        if (m.capture.state == CaptureTaskState.recording) {
            val captureFile = m.capture.path?.let { path ->
                val entryDir = deriveRelativeEntryDir(m)
                entryDir?.let { File(baseDirectory, "${it}/${path}") }
            }
            m = if (captureFile != null && captureFile.isFile && captureFile.length() > 44L) {
                m.copy(capture = m.capture.copy(state = CaptureTaskState.failed, error = "abandoned"))
            } else {
                m.copy(capture = m.capture.copy(state = CaptureTaskState.abandoned))
            }
        }

        if (m.encoding?.state == DerivedTaskStatus.running) {
            m = m.copy(encoding = m.encoding.copy(state = DerivedTaskStatus.pending))
        }

        if (m.transcription?.state == DerivedTaskStatus.running) {
            m = m.copy(transcription = m.transcription.copy(state = DerivedTaskStatus.pending))
        }

        return m
    }

    private fun deriveRelativeEntryDir(metadata: JournalEntryMetadata): String? {
        val dateStr = metadata.startedAt.substringBefore("T")
        val parts = dateStr.split("-")
        if (parts.size != 3) return null
        val stem = metadata.entryId
        return "${parts[0]}/${parts[0]}-${parts[1]}/${dateStr}/entries/$stem"
    }

    companion object {
        private const val TAG = "SubspaceJournal"
    }
}
