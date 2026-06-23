package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttModelStatus

/**
 * JNI bridge to the native `subspace_parakeet` Rust crate.
 *
 * The native library is loaded lazily by [ParakeetJniTranscriber] and is
 * optional: when the library is absent (e.g. host JVM unit tests) the bridge
 * methods throw [UnsatisfiedLinkError] and callers must fall back to a fake.
 */
object ParakeetJniBridge {
    private const val NATIVE_LIB_NAME = "subspace_parakeet"

    private var loaded: Boolean = false
    private var loadAttempted: Boolean = false

    /** Attempt to load the native library. Returns true on success. */
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

    external fun nativeInit(nativeLibDir: String): Int
    external fun nativeStartLoad(modelDir: String): Int
    external fun nativeLoadStatus(): Int
    external fun nativeLoadError(): String?
    external fun nativeTranscribe(samples: FloatArray): String
}

/**
 * Transcriber backed by [ParakeetJniBridge]. Used at Android runtime.
 */
class ParakeetJniTranscriber(
    private val nativeLibDir: String,
    private val modelDir: String,
) : SttTranscriber {
    init {
        check(ParakeetJniBridge.ensureLoaded()) {
            "subspace_parakeet native library not available"
        }
        val initResult = ParakeetJniBridge.nativeInit(nativeLibDir)
        check(initResult == 0) { "nativeInit failed (code=$initResult)" }
        ParakeetJniBridge.nativeStartLoad(modelDir)
    }

    override val modelStatus: SttModelStatus
        get() = when (ParakeetJniBridge.nativeLoadStatus()) {
            0 -> SttModelStatus.Idle
            1 -> SttModelStatus.Loading
            2 -> SttModelStatus.Ready
            3 -> SttModelStatus.Failed
            else -> SttModelStatus.Failed
        }

    override val loadError: String?
        get() = ParakeetJniBridge.nativeLoadError()

    override fun transcribe(samples: FloatArray): TranscriptionOutcome {
        if (samples.isEmpty()) return TranscriptionOutcome.EmptyInput
        val json = ParakeetJniBridge.nativeTranscribe(samples)
        return parseOutcome(json)
    }

    private fun parseOutcome(json: String): TranscriptionOutcome {
        // Minimal JSON parser for the {"outcome":N,"text":"..."|"error":"..."}
        // shape emitted by the native side. Avoids pulling in a JSON dependency.
        val outcome = extractIntField(json, "outcome") ?: return TranscriptionOutcome.Failure(
            "invalid native response: $json",
        )
        return when (outcome) {
            0 -> TranscriptionOutcome.Success(extractStringField(json, "text").orEmpty())
            1 -> TranscriptionOutcome.ModelNotReady
            2 -> TranscriptionOutcome.Failure(extractStringField(json, "error").orEmpty())
            3 -> TranscriptionOutcome.EmptyInput
            else -> TranscriptionOutcome.Failure("unknown outcome code: $outcome")
        }
    }

    private fun extractIntField(json: String, field: String): Int? {
        val key = "\"$field\":"
        val idx = json.indexOf(key)
        if (idx < 0) return null
        var i = idx + key.length
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
        return json.substring(start, i).toIntOrNull()
    }

    private fun extractStringField(json: String, field: String): String? {
        val key = "\"$field\":\""
        val startIdx = json.indexOf(key)
        if (startIdx < 0) return null
        var i = startIdx + key.length
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return sb.toString()
                c == '\\' && i + 1 < json.length -> {
                    val next = json[i + 1]
                    when (next) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 5 < json.length) {
                                val cp = json.substring(i + 2, i + 6).toIntOrNull(16)
                                if (cp != null) sb.appendCodePoint(cp)
                                i += 4
                            }
                        }
                        else -> sb.append(next)
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i += 1
                }
            }
        }
        return sb.toString()
    }
}