package dev.nilp0inter.subspace.channel

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownJournalWriterTest {
    @Test
    fun firstEntryCreatesHeaderAndLink() {
        val file = tempLogFile()

        MarkdownJournalWriter().appendEntry(
            markdownFile = file,
            dateLabel = "2026-06-24",
            timeLabel = "14-30-00",
            bodyText = "Hello world.",
            recordingLink = "recordings/journal-2026-06-24_14-30-00.ogg",
        )

        assertEquals(
            "# Journal 2026-06-24\n\n" +
                "## Entry 14-30-00\n\n" +
                "Hello world.\n\n" +
                "[Source recording](recordings/journal-2026-06-24_14-30-00.ogg)\n\n",
            file.readText(),
        )
    }

    @Test
    fun subsequentEntryAppendsWithoutDuplicatingHeader() {
        val file = tempLogFile()
        val writer = MarkdownJournalWriter()

        writer.appendEntry(file, "2026-06-24", "14-30-00", "First.")
        writer.appendEntry(file, "2026-06-24", "15-00-00", "Second.")

        assertEquals(
            "# Journal 2026-06-24\n\n" +
                "## Entry 14-30-00\n\n" +
                "First.\n\n" +
                "## Entry 15-00-00\n\n" +
                "Second.\n\n",
            file.readText(),
        )
    }

    @Test
    fun entryWithoutLinkOmitsSourceRecordingLine() {
        val file = tempLogFile()

        MarkdownJournalWriter().appendEntry(file, "2026-06-24", "14-30-00", "Text only.")

        assertEquals(
            "# Journal 2026-06-24\n\n" +
                "## Entry 14-30-00\n\n" +
                "Text only.\n\n",
            file.readText(),
        )
    }

    private fun tempLogFile(): File {
        val directory = createTempDirectory(prefix = "subspace-journal-writer-").toFile()
        return File(directory, "log.md")
    }
}
