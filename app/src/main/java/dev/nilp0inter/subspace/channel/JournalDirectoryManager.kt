package dev.nilp0inter.subspace.channel

import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class JournalDirectoryManager(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun pathsFor(baseDirectory: File, timestamp: LocalDateTime = LocalDateTime.now(clock)): LogPaths {
        val day = timestamp.format(DATE)
        val time = timestamp.format(TIME)
        val dayDirectory = File(baseDirectory, "${timestamp.format(YEAR)}/${timestamp.format(MONTH)}/$day")
        val recordingsDirectory = File(dayDirectory, "recordings")
        val recordingName = "journal-${day}_$time.ogg"
        return LogPaths(
            dayDirectory = dayDirectory,
            recordingsDirectory = recordingsDirectory,
            recordingFile = File(recordingsDirectory, recordingName),
            markdownFile = File(dayDirectory, "journal-$day.md"),
            relativeRecordingLink = "recordings/$recordingName",
            dateLabel = day,
            timeLabel = time,
        )
    }

    fun preparePaths(baseDirectory: File, timestamp: LocalDateTime = LocalDateTime.now(clock)): LogPaths {
        val paths = pathsFor(baseDirectory, timestamp)
        paths.recordingsDirectory.mkdirs()
        return paths
    }

    companion object {
        private val YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")
        private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss")
    }
}

data class LogPaths(
    val dayDirectory: File,
    val recordingsDirectory: File,
    val recordingFile: File,
    val markdownFile: File,
    val relativeRecordingLink: String,
    val dateLabel: String,
    val timeLabel: String,
)
