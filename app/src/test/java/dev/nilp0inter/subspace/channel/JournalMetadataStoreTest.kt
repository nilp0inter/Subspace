package dev.nilp0inter.subspace.channel

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JournalMetadataStoreTest {
    @Test
    fun roundTripPreservesAllFields() {
        val dir = createTempDirectory(prefix = "meta-").toFile()
        val file = File(dir, "test.metadata.json")
        val store = JournalMetadataStore()

        val original = JournalEntryMetadata(
            entryId = "journal-entry-2026-06-25_14-30-00-123-0300",
            startedAt = "2026-06-25T14:30:00.123-03:00",
            endedAt = "2026-06-25T14:31:12.456-03:00",
            timezoneOffset = "-0300",
            channel = MetadataChannelSnapshot(
                id = "captains-log",
                saveVoice = true,
                saveText = true,
            ),
            capture = CaptureState(
                state = CaptureTaskState.finished,
                path = "journal-entry-2026-06-25_14-30-00-123-0300.capture.wav",
                sampleRate = 16000,
                channels = 1,
                encoding = "pcm_s16le",
                durationMs = 72456,
                bytes = 2318592,
            ),
            encoding = DerivedTaskState(
                state = DerivedTaskStatus.finished,
                path = "journal-entry-2026-06-25_14-30-00-123-0300.recording.ogg",
            ),
            transcription = DerivedTaskState(
                state = DerivedTaskStatus.finished,
                text = "Transcribed text.",
            ),
        )
        store.write(original, file)
        assertTrue(file.isFile)

        val parsed = store.read(file)
        assertNotNull(parsed)
        assertEquals(original.entryId, parsed!!.entryId)
        assertEquals(original.startedAt, parsed.startedAt)
        assertEquals(original.endedAt, parsed.endedAt)
        assertEquals(original.timezoneOffset, parsed.timezoneOffset)
        assertEquals(original.channel.saveVoice, parsed.channel.saveVoice)
        assertEquals(original.channel.saveText, parsed.channel.saveText)
        assertEquals(original.capture.state, parsed.capture.state)
        assertEquals(original.capture.path, parsed.capture.path)
        assertEquals(original.capture.sampleRate, parsed.capture.sampleRate)
        assertEquals(original.capture.durationMs, parsed.capture.durationMs)
        assertEquals(original.capture.bytes, parsed.capture.bytes)
        assertEquals(original.encoding?.state, parsed.encoding?.state)
        assertEquals(original.encoding?.path, parsed.encoding?.path)
        assertEquals(original.transcription?.state, parsed.transcription?.state)
        assertEquals(original.transcription?.text, parsed.transcription?.text)
        assertNull(parsed.deletedAt)
    }

    @Test
    fun stateTransitionsRoundTrip() {
        val dir = createTempDirectory(prefix = "meta-").toFile()
        val file = File(dir, "test.metadata.json")
        val store = JournalMetadataStore()

        val states = listOf(
            CaptureTaskState.recording,
            CaptureTaskState.finished,
            CaptureTaskState.failed,
        )
        for (state in states) {
            val meta = JournalEntryMetadata(
                entryId = "test",
                startedAt = "2026-06-25T14:30:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = state),
            )
            store.write(meta, file)
            assertEquals(state, store.read(file)?.capture?.state)
        }

        val derivedStates = listOf(
            DerivedTaskStatus.pending,
            DerivedTaskStatus.running,
            DerivedTaskStatus.finished,
            DerivedTaskStatus.failed,
            DerivedTaskStatus.skipped,
        )
        for (state in derivedStates) {
            val meta = JournalEntryMetadata(
                entryId = "test",
                startedAt = "2026-06-25T14:30:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = state),
            )
            store.write(meta, file)
            assertEquals(state, store.read(file)?.encoding?.state)
        }
    }

    @Test
    fun deletedAtRoundTrip() {
        val dir = createTempDirectory(prefix = "meta-").toFile()
        val file = File(dir, "test.metadata.json")
        val store = JournalMetadataStore()

        val meta = JournalEntryMetadata(
            entryId = "test",
            startedAt = "2026-06-25T14:30:00Z",
            timezoneOffset = "+0000",
            channel = MetadataChannelSnapshot("captains-log", true, true),
            capture = CaptureState(state = CaptureTaskState.finished),
            deletedAt = "2026-06-26T10:00:00Z",
        )
        store.write(meta, file)
        assertEquals("2026-06-26T10:00:00Z", store.read(file)?.deletedAt)
    }

    @Test
    fun errorFieldRoundTrip() {
        val dir = createTempDirectory(prefix = "meta-").toFile()
        val file = File(dir, "test.metadata.json")
        val store = JournalMetadataStore()

        val meta = JournalEntryMetadata(
            entryId = "test",
            startedAt = "2026-06-25T14:30:00Z",
            timezoneOffset = "+0000",
            channel = MetadataChannelSnapshot("captains-log", true, true),
            capture = CaptureState(state = CaptureTaskState.failed, error = "mic not available"),
            encoding = DerivedTaskState(state = DerivedTaskStatus.failed, error = "ogg codec crash"),
            transcription = DerivedTaskState(state = DerivedTaskStatus.failed, error = "stt timeout"),
        )
        store.write(meta, file)
        val parsed = store.read(file)!!
        assertEquals("mic not available", parsed.capture.error)
        assertEquals("ogg codec crash", parsed.encoding?.error)
        assertEquals("stt timeout", parsed.transcription?.error)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
