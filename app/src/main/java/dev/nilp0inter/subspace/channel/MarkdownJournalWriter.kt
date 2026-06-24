package dev.nilp0inter.subspace.channel

import java.io.File

class MarkdownJournalWriter {
    fun appendEntry(
        markdownFile: File,
        dateLabel: String,
        timeLabel: String,
        bodyText: String,
        recordingLink: String? = null,
    ) {
        markdownFile.parentFile?.mkdirs()
        val isNewFile = !markdownFile.exists()
        markdownFile.appendText(buildString {
            if (isNewFile) append("# Journal $dateLabel\n\n")
            append("## Entry $timeLabel\n\n")
            append(bodyText)
            append("\n\n")
            if (recordingLink != null) {
                append("[Source recording]($recordingLink)\n\n")
            }
        })
    }
}
