package dev.nilp0inter.subspace.audio

/**
 * Audio conversion helpers for the TTS playback path.
 *
 * Supertonic outputs 44.1 kHz mono `f32` PCM. The headset SCO route runs at 8
 * or 16 kHz PCM16. Convert `f32` to PCM16, then resample 44.1 kHz → target SCO
 * rate using linear interpolation before writing to [PcmOutput].
 */
object TtsAudio {
    /**
     * Convert `f32` samples in `[-1.0, 1.0]` to signed PCM16. Values outside the
     * unit range are clamped to `[-32768, 32767]`.
     */
    fun f32ToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            out[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return out
    }

    /**
     * Linear resampling of `f32` samples from [inputRate] to [outputRate].
     * Uses linear interpolation between adjacent samples; adequate for a
     * diagnostic test surface and cheap enough for on-device use.
     *
     * If [inputRate] == [outputRate] the input is returned unchanged. If the
     * input is empty, an empty array is returned.
     */
    fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        if (inputRate == outputRate || input.isEmpty()) return input
        val ratio = outputRate.toDouble() / inputRate.toDouble()
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val left = input[srcIdx.coerceAtMost(input.size - 1)]
            val right = input[(srcIdx + 1).coerceAtMost(input.size - 1)]
            out[i] = (left + (right - left) * frac).toFloat()
        }
        return out
    }

    /**
     * Convenience: convert Supertonic 44.1 kHz `f32` output to PCM16 at the SCO
     * output [targetRate] (typically 8_000 or 16_000), returning a
     * [RecordedPcm] suitable for [PcmOutput.play]. Empty input yields an empty
     * [RecordedPcm].
     */
    fun toScoPlayback(samples: FloatArray, targetRate: Int): RecordedPcm {
        if (samples.isEmpty()) return RecordedPcm(ShortArray(0), targetRate)
        val resampled = resample(samples, SUPERTONIC_SAMPLE_RATE, targetRate)
        return RecordedPcm(f32ToPcm16(resampled), targetRate)
    }

    const val SUPERTONIC_SAMPLE_RATE: Int = 44_100
}