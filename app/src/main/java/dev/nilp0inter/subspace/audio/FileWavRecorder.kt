package dev.nilp0inter.subspace.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileWavRecorder(
    private val scope: CoroutineScope,
    private val targetFile: File,
) {
    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var raf: RandomAccessFile? = null
    private var sampleRate: Int = 16_000

    val isActive: Boolean
        get() = audioRecord != null

    val captureFile: File
        get() = targetFile

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (audioRecord != null) return@withContext true

        val selectedRate = selectSampleRate() ?: return@withContext false
        val minBuffer = AudioRecord.getMinBufferSize(
            selectedRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return@withContext false

        val format = AudioFormat.Builder()
            .setSampleRate(selectedRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }.getOrNull() ?: return@withContext false

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext false
        }

        targetFile.parentFile?.mkdirs()
        val file = runCatching { RandomAccessFile(targetFile, "rw") }.getOrNull() ?: run {
            record.release()
            return@withContext false
        }
        writeWavHeader(file, selectedRate, dataSize = 0)
        raf = file
        sampleRate = selectedRate

        runCatching { record.startRecording() }.getOrElse {
            record.release()
            file.close()
            raf = null
            return@withContext false
        }

        audioRecord = record
        readJob = scope.launch(Dispatchers.IO) { readLoop(record, minBuffer / Short.SIZE_BYTES) }
        true
    }

    fun stop() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching { record.stop() }
        readJob?.cancel()
        readJob = null
        record.release()
        val file = raf ?: return
        raf = null
        runCatching {
            val dataSize = file.length() - HEADER_SIZE
            if (dataSize < 0) return@runCatching
            file.seek(4)
            writeInt32Le(file, (HEADER_SIZE + dataSize - 8).toInt())
            file.seek(40)
            writeInt32Le(file, dataSize.toInt())
            file.close()
        }
    }

    private fun selectSampleRate(): Int? = listOf(16_000, 8_000).firstOrNull { rate ->
        AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ) > 0
    }

    private fun readLoop(record: AudioRecord, bufferSizeShorts: Int) {
        val buffer = ShortArray(bufferSizeShorts.coerceAtLeast(1))
        val bufferBytes = ByteArray(bufferSizeShorts.coerceAtLeast(1) * 2)
        while (scope.isActive && audioRecord === record) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            val file = raf ?: break
            runCatching {
                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    bufferBytes[i * 2] = (v and 0xFF).toByte()
                    bufferBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                file.write(bufferBytes, 0, read * 2)
            }
        }
    }

    companion object {
        private const val HEADER_SIZE = 44

        private fun writeWavHeader(file: RandomAccessFile, sampleRate: Int, dataSize: Int) {
            file.writeBytes("RIFF")
            writeInt32Le(file, 36 + dataSize)
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            writeInt32Le(file, 16)
            writeInt16Le(file, 1)
            writeInt16Le(file, 1)
            writeInt32Le(file, sampleRate)
            writeInt32Le(file, sampleRate * 2)
            writeInt16Le(file, 2)
            writeInt16Le(file, 16)
            file.writeBytes("data")
            writeInt32Le(file, dataSize)
        }

        private fun writeInt16Le(file: RandomAccessFile, value: Int) {
            file.writeByte(value and 0xFF)
            file.writeByte((value shr 8) and 0xFF)
        }

        private fun writeInt32Le(file: RandomAccessFile, value: Int) {
            file.writeByte(value and 0xFF)
            file.writeByte((value shr 8) and 0xFF)
            file.writeByte((value shr 16) and 0xFF)
            file.writeByte((value shr 24) and 0xFF)
        }
    }
}
