package dev.nilp0inter.subspace.channel

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class JournalEntryPaths(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    data class EntryPaths(
        val baseDirectory: File,
        val dayDirectory: File,
        val entryDirectory: File,
        val stem: String,
        val metadataFile: File,
        val captureFile: File,
        val recordingFile: File,
        val markdownFile: File,
        val dateLabel: String,
        val timezoneOffset: String,
    )

    fun pathsFor(baseDirectory: File, startedAt: ZonedDateTime = ZonedDateTime.now(zoneId)): EntryPaths {
        val offset = startedAt.format(OFFSET_FORMATTER)
        val day = startedAt.format(DAY_FORMATTER)
        val stemBase = startedAt.format(STEM_FORMATTER)
        val stem = "$stemBase-$offset"
        val dayDir = File(baseDirectory, "${startedAt.format(YEAR_FORMATTER)}/${startedAt.format(MONTH_FORMATTER)}/$day")
        val entryDir = File(dayDir, "entries/$stem")
        return EntryPaths(
            baseDirectory = baseDirectory,
            dayDirectory = dayDir,
            entryDirectory = entryDir,
            stem = stem,
            metadataFile = File(entryDir, "$stem.metadata.json"),
            captureFile = File(entryDir, "$stem.capture.wav"),
            recordingFile = File(entryDir, "$stem.recording.ogg"),
            markdownFile = File(dayDir, "journal-day-$day.md"),
            dateLabel = day,
            timezoneOffset = offset,
        )
    }

    fun preparePaths(baseDirectory: File, startedAt: ZonedDateTime = ZonedDateTime.now(zoneId)): EntryPaths {
        val paths = pathsFor(baseDirectory, startedAt)
        paths.entryDirectory.mkdirs()
        return paths
    }

    companion object {
        private val YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")
        private val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val STEM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("'journal-entry-'yyyy-MM-dd'_'HH-mm-ss-SSS")
        private val OFFSET_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("xx")
    }
}
