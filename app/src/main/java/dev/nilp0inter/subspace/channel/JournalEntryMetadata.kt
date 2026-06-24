package dev.nilp0inter.subspace.channel

data class JournalEntryMetadata(
    val schemaVersion: Int = 1,
    val entryId: String,
    val startedAt: String,
    val endedAt: String? = null,
    val timezoneOffset: String,
    val channel: MetadataChannelSnapshot,
    val capture: CaptureState,
    val encoding: DerivedTaskState? = null,
    val transcription: DerivedTaskState? = null,
    val deletedAt: String? = null,
)

data class MetadataChannelSnapshot(
    val id: String,
    val saveVoice: Boolean,
    val saveText: Boolean,
)

data class CaptureState(
    val state: CaptureTaskState,
    val path: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val encoding: String? = null,
    val durationMs: Long? = null,
    val bytes: Long? = null,
    val error: String? = null,
)

data class DerivedTaskState(
    val state: DerivedTaskStatus,
    val path: String? = null,
    val text: String? = null,
    val error: String? = null,
)

enum class CaptureTaskState {
    recording,
    finished,
    deleted,
    failed,
    abandoned,
}

enum class DerivedTaskStatus {
    pending,
    running,
    finished,
    failed,
    skipped,
}
