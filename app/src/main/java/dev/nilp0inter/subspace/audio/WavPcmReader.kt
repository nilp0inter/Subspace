package dev.nilp0inter.subspace.audio

import java.io.File
import java.io.RandomAccessFile

data class WavInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val dataSize: Long,
    val durationMs: Long,
    val samples: ShortArray,
)

object WavPcmReader {
    fun read(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val riff = raf.readBytes(4).decodeToString()
            check(riff == "RIFF") { "Not a RIFF file" }
            readInt32Le(raf)
            val wave = raf.readBytes(4).decodeToString()
            check(wave == "WAVE") { "Not a WAVE file" }

            var fmtChunkFound = false
            var sampleRate = 0
            var channelCount = 0
            var bitsPerSample = 0
            var dataSize = 0L
            var dataOffset = 0L

            while (raf.filePointer < raf.length()) {
                val chunkId = raf.readBytes(4).decodeToString()
                val chunkSize = readInt32Le(raf).toLong() and 0xFFFFFFFFL
                when (chunkId) {
                    "fmt " -> {
                        val audioFormat = readInt16Le(raf)
                        check(audioFormat == 1) { "Only PCM format supported" }
                        channelCount = readInt16Le(raf)
                        sampleRate = readInt32Le(raf)
                        readInt32Le(raf)
                        readInt16Le(raf)
                        bitsPerSample = readInt16Le(raf)
                        fmtChunkFound = true
                        val remaining = chunkSize - 16
                        if (remaining > 0) raf.skipBytes(remaining.toInt())
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = chunkSize
                        break
                    }
                    else -> {
                        raf.skipBytes(chunkSize.toInt())
                    }
                }
            }
            check(fmtChunkFound) { "No fmt chunk found" }
            check(dataSize > 0) { "No data chunk found" }
            check(bitsPerSample == 16) { "Only 16-bit PCM supported" }

            val samplesCount = dataSize / (bitsPerSample / 8)
            val samples = ShortArray(samplesCount.toInt())
            raf.seek(dataOffset)
            val byteBuf = ByteArray((samplesCount * 2).toInt())
            raf.readFully(byteBuf)
            for (i in samples.indices) {
                val lo = byteBuf[i * 2].toInt() and 0xFF
                val hi = byteBuf[i * 2 + 1].toInt() shl 8
                samples[i] = (lo or hi).toShort()
            }

            val bytesPerSample = (bitsPerSample / 8)
            val totalSamples = dataSize / bytesPerSample
            val durationMs = (totalSamples * 1000) / sampleRate
            WavInfo(
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitsPerSample = bitsPerSample,
                dataSize = dataSize,
                durationMs = durationMs,
                samples = samples,
            )
        }
    }.getOrNull()

    private fun readInt16Le(raf: RandomAccessFile): Int {
        val lo = raf.readUnsignedByte()
        val hi = raf.readUnsignedByte()
        return (hi shl 8) or lo
    }

    private fun readInt32Le(raf: RandomAccessFile): Int {
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        val b2 = raf.readUnsignedByte()
        val b3 = raf.readUnsignedByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun RandomAccessFile.readBytes(count: Int): ByteArray {
        val buf = ByteArray(count)
        readFully(buf)
        return buf
    }
}
