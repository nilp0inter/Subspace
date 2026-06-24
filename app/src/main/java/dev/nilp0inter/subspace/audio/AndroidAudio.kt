package dev.nilp0inter.subspace.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class AndroidPcmOutput(
    private val audioManager: AudioManager,
) : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) {
        ensureScoActive()
        val sampleRate = 16_000
        var samples = generateSinePcm16(
            frequencyHz = 880.0,
            durationMs = 150,
            sampleRate = sampleRate,
            amplitude = 0.35,
        )
        if (coldStart) {
            val silenceCount = sampleRate * 100 / 1_000
            val silence = ShortArray(silenceCount)
            samples = silence + samples
        }
        playStaticPcm(
            samples = samples,
            sampleRate = sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
            preferredDevice = audioManager.communicationDevice,
        )
    }

    override suspend fun playErrorBeep(coldStart: Boolean) {
        ensureScoActive()
        val sampleRate = 16_000
        val tone1 = generateSinePcm16(
            frequencyHz = 400.0,
            durationMs = 150,
            sampleRate = sampleRate,
            amplitude = 0.35,
        )
        val tone2 = generateSinePcm16(
            frequencyHz = 300.0,
            durationMs = 150,
            sampleRate = sampleRate,
            amplitude = 0.35,
        )
        var samples = tone1 + tone2
        if (coldStart) {
            val silenceCount = sampleRate * 100 / 1_000
            val silence = ShortArray(silenceCount)
            samples = silence + samples
        }
        playStaticPcm(
            samples = samples,
            sampleRate = sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
            preferredDevice = audioManager.communicationDevice,
        )
    }

    override suspend fun play(recording: RecordedPcm) {
        ensureScoActive()
        if (recording.isEmpty) return
        playStaticPcm(
            samples = recording.samples,
            sampleRate = recording.sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        )
    }

    private fun ensureScoActive() {
        check(audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            "Bluetooth SCO route is not active"
        }
    }

    private suspend fun playStaticPcm(
        samples: ShortArray,
        sampleRate: Int,
        contentType: Int,
        preferredDevice: AudioDeviceInfo? = null,
    ) = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(contentType)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (preferredDevice != null) {
            track.setPreferredDevice(preferredDevice)
        }

        try {
            track.write(samples, 0, samples.size)
            track.play()
            val durationMs = samples.size * 1_000L / sampleRate
            delay(durationMs + 50)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun generateSinePcm16(
        frequencyHz: Double,
        durationMs: Int,
        sampleRate: Int,
        amplitude: Double,
    ): ShortArray {
        val count = sampleRate * durationMs / 1_000
        return ShortArray(count) { index ->
            val phase = 2.0 * PI * frequencyHz * index / sampleRate
            (sin(phase) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }
    }
}

class InMemoryRecorder(
    private val scope: CoroutineScope,
) : AudioRecorder {
    private val lock = Any()
    private val pcm = mutableListOf<Short>()
    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var sampleRate: Int = 16_000

    override val isActive: Boolean
        get() = audioRecord != null

    @SuppressLint("MissingPermission")
    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
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

        synchronized(lock) { pcm.clear() }
        sampleRate = selectedRate
        runCatching { record.startRecording() }.getOrElse {
            record.release()
            return@withContext false
        }

        audioRecord = record
        readJob = scope.launch(Dispatchers.IO) { readLoop(record, minBuffer / Short.SIZE_BYTES) }
        true
    }

    override fun stopIfActiveOrEmpty(): RecordedPcm {
        val record = audioRecord ?: return RecordedPcm(shortArrayOf(), sampleRate)
        audioRecord = null
        runCatching { record.stop() }
        readJob?.cancel()
        readJob = null
        record.release()

        val captured = synchronized(lock) { pcm.toShortArray() }
        return RecordedPcm(captured, sampleRate)
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
        val maxSamples = sampleRate * 60
        while (scope.isActive && audioRecord === record) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            synchronized(lock) {
                val remaining = maxSamples - pcm.size
                val toCopy = minOf(read, remaining)
                for (index in 0 until toCopy) pcm += buffer[index]
            }
        }
    }
}
