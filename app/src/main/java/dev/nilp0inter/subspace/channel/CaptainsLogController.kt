package dev.nilp0inter.subspace.channel

import android.util.Log
import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.model.CaptainsLogChannel
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CaptainsLogController(
    private val scope: CoroutineScope,
    private val encoder: AudioEncoder,
    private val transcriber: PcmTranscriber,
    private val directoryManager: LogDirectoryManager = LogDirectoryManager(),
    private val logWriter: MarkdownLogWriter = MarkdownLogWriter(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun handleCapture(channel: CaptainsLogChannel, pcm: ShortArray, sampleRate: Int) {
        val baseDirectory = channel.baseDirectory?.takeIf { it.isNotBlank() } ?: return
        if (!channel.enabled) return
        scope.launch(Dispatchers.Default) {
            processCapture(channel, File(baseDirectory), pcm, sampleRate)
        }
    }

    suspend fun processCapture(channel: CaptainsLogChannel, baseDirectory: File, pcm: ShortArray, sampleRate: Int) {
        val paths = directoryManager.preparePaths(baseDirectory, LocalDateTime.now(clock))
        val recording = if (channel.saveVoice) {
            encoder.encode(pcm, paths.recordingFile, sampleRate)
        } else {
            null
        }

        if (channel.saveText) {
            val transcript = runCatching { transcriber.transcribe(pcm, sampleRate) }
                .getOrElse { error ->
                    runCatching { Log.e(TAG, "Captain's Log transcription failed", error) }
                    "[Transcription failed: ${error.message ?: "unknown error"}]"
                }
            val recordingLink = when {
                recording?.isSuccess == true -> paths.relativeRecordingLink
                recording?.isFailure == true -> "Recording failed."
                else -> null
            }
            logWriter.appendEntry(
                markdownFile = paths.markdownFile,
                dateLabel = paths.dateLabel,
                timeLabel = paths.timeLabel,
                bodyText = transcript,
                recordingLink = recordingLink?.takeIf { it.endsWith(".ogg") },
            )
            if (recording?.isFailure == true) {
                paths.markdownFile.appendText("Recording failed.\n\n")
            }
        }
    }

    companion object {
        private const val TAG = "SubspaceCaptainsLog"
    }
}
