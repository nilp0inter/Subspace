package dev.nilp0inter.subspace.audio

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AudioEncoder {
    suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int = 16_000): Result<File>
}

class OggEncoder(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioEncoder {
    override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> =
        withContext(dispatcher) {
            runCatching {
                require(OggJniBridge.ensureLoaded()) { "subspace_ogg native library not available" }
                outputFile.parentFile?.mkdirs()
                outputFile.delete()
                val result = OggJniBridge.nativeEncode(pcm, sampleRate, outputFile.absolutePath)
                check(result == 0) { "native OGG encoder failed (code=$result)" }
                check(outputFile.isFile && outputFile.length() > 0L) { "OGG encoder produced no output" }
                outputFile
            }.onFailure { error ->
                runCatching { Log.e(TAG, "OGG encoding failed", error) }
                outputFile.delete()
            }
        }

    companion object {
        private const val TAG = "SubspaceOggEncoder"
    }
}

object OggJniBridge {
    private const val NATIVE_LIB_NAME = "subspace_ogg"

    private var loaded: Boolean = false
    private var loadAttempted: Boolean = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loadAttempted) return loaded
        loadAttempted = true
        loaded = try {
            System.loadLibrary(NATIVE_LIB_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
        return loaded
    }

    external fun nativeEncode(samples: ShortArray, sampleRate: Int, outputPath: String): Int
}
