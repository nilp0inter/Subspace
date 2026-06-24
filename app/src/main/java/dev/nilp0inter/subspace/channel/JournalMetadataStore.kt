package dev.nilp0inter.subspace.channel

import org.json.JSONObject
import java.io.File

class JournalMetadataStore {

    fun write(metadata: JournalEntryMetadata, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(serialize(metadata).toString(2))
        tmp.renameTo(file)
    }

    fun read(file: File): JournalEntryMetadata? =
        runCatching { parse(JSONObject(file.readText())) }.getOrNull()

    fun serialize(metadata: JournalEntryMetadata): JSONObject {
        val capture = JSONObject().apply {
            put("state", metadata.capture.state.name)
            metadata.capture.path?.let { put("path", it) }
            metadata.capture.sampleRate?.let { put("sampleRate", it) }
            metadata.capture.channels?.let { put("channels", it) }
            metadata.capture.encoding?.let { put("encoding", it) }
            metadata.capture.durationMs?.let { put("durationMs", it) }
            metadata.capture.bytes?.let { put("bytes", it) }
            metadata.capture.error?.let { put("error", it) }
        }
        val channel = JSONObject().apply {
            put("id", metadata.channel.id)
            put("saveVoice", metadata.channel.saveVoice)
            put("saveText", metadata.channel.saveText)
        }
        val root = JSONObject().apply {
            put("schemaVersion", metadata.schemaVersion)
            put("entryId", metadata.entryId)
            put("startedAt", metadata.startedAt)
            metadata.endedAt?.let { put("endedAt", it) }
            put("timezoneOffset", metadata.timezoneOffset)
            put("channel", channel)
            put("capture", capture)
            metadata.encoding?.let { put("encoding", serializeTask(it)) }
            metadata.transcription?.let { put("transcription", serializeTask(it)) }
            metadata.deletedAt?.let { put("deletedAt", it) }
        }
        return root
    }

    private fun serializeTask(task: DerivedTaskState): JSONObject = JSONObject().apply {
        put("state", task.state.name)
        task.path?.let { put("path", it) }
        task.text?.let { put("text", it) }
        task.error?.let { put("error", it) }
    }

    private fun parse(json: JSONObject): JournalEntryMetadata {
        val capture = json.getJSONObject("capture")
        val channel = json.getJSONObject("channel")
        return JournalEntryMetadata(
            schemaVersion = json.optInt("schemaVersion", 1),
            entryId = json.getString("entryId"),
            startedAt = json.getString("startedAt"),
            endedAt = optStringOrNull(json, "endedAt"),
            timezoneOffset = json.getString("timezoneOffset"),
            channel = MetadataChannelSnapshot(
                id = channel.getString("id"),
                saveVoice = channel.getBoolean("saveVoice"),
                saveText = channel.getBoolean("saveText"),
            ),
            capture = CaptureState(
                state = CaptureTaskState.valueOf(capture.getString("state")),
                path = optStringOrNull(capture, "path"),
                sampleRate = if (capture.has("sampleRate")) capture.getInt("sampleRate") else null,
                channels = if (capture.has("channels")) capture.getInt("channels") else null,
                encoding = optStringOrNull(capture, "encoding"),
                durationMs = if (capture.has("durationMs")) capture.getLong("durationMs") else null,
                bytes = if (capture.has("bytes")) capture.getLong("bytes") else null,
                error = optStringOrNull(capture, "error"),
            ),
            encoding = if (json.has("encoding")) parseTask(json.getJSONObject("encoding")) else null,
            transcription = if (json.has("transcription")) parseTask(json.getJSONObject("transcription")) else null,
            deletedAt = optStringOrNull(json, "deletedAt"),
        )
    }

    private fun parseTask(json: JSONObject): DerivedTaskState = DerivedTaskState(
        state = DerivedTaskStatus.valueOf(json.getString("state")),
        path = optStringOrNull(json, "path"),
        text = optStringOrNull(json, "text"),
        error = optStringOrNull(json, "error"),
    )

    private fun optStringOrNull(json: JSONObject, key: String): String? =
        if (json.has(key) && !json.isNull(key)) json.getString(key) else null
}
