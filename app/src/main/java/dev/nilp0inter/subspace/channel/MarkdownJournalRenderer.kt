package dev.nilp0inter.subspace.channel

import java.io.File

class MarkdownJournalRenderer(
    private val metadataStore: JournalMetadataStore = JournalMetadataStore(),
) {
    fun render(dayDirectory: File, entries: List<JournalEntryMetadata>) {
        val dateLabel = dayDirectory.name
        val markdownFile = File(dayDirectory, "journal-day-$dateLabel.md")

        if (entries.isEmpty()) {
            markdownFile.delete()
            return
        }

        val tmpFile = File(markdownFile.parentFile, "${markdownFile.name}.tmp")
        tmpFile.bufferedWriter().use { writer ->
            writer.write("# Journal $dateLabel")
            writer.newLine()
            writer.newLine()

            for (entry in entries) {
                val timeLabel = extractTimeLabel(entry.startedAt)
                writer.write("## Entry $timeLabel")
                writer.newLine()
                writer.newLine()

                val transcriptText = when (entry.transcription?.state) {
                    DerivedTaskStatus.finished -> entry.transcription?.text ?: ""
                    DerivedTaskStatus.failed -> "[Transcription failed: ${entry.transcription?.error ?: "unknown error"}]"
                    DerivedTaskStatus.skipped -> ""
                    DerivedTaskStatus.pending, DerivedTaskStatus.running -> "[Transcription pending]"
                    null -> ""
                }
                if (transcriptText.isNotBlank()) {
                    writer.write(transcriptText)
                    writer.newLine()
                    writer.newLine()
                }

                if (entry.encoding?.state == DerivedTaskStatus.finished && entry.channel.saveVoice) {
                    val link = entry.encoding?.path
                    if (link != null) {
                        writer.write("[Source recording](entries/${entry.entryId}/$link)")
                        writer.newLine()
                        writer.newLine()
                    }
                }
            }
        }
        tmpFile.renameTo(markdownFile)
    }

    private fun extractTimeLabel(isoTimestamp: String): String {
        val afterT = isoTimestamp.substringAfter("T")
        val timePart = afterT.substringBefore("+").substringBeforeLast("-").substringBefore("Z")
            .takeWhile { it != '.' }
        if (timePart.length >= 8) {
            return "${timePart.substring(0, 2)}-${timePart.substring(3, 5)}-${timePart.substring(6, 8)}"
        }
        return timePart
    }
}
