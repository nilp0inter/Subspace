package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.TtsModelStatus

/**
 * JNI bridge to the native `subspace_supertonic` Rust crate.
 *
 * The native library is loaded lazily by [SupertonicJniSynthesizer] and is
 * optional: when the library is absent (e.g. host JVM unit tests) the bridge
 * methods throw [UnsatisfiedLinkError] and callers must fall back to a fake.
 */
object SupertonicJniBridge {
    private const val NATIVE_LIB_NAME = "subspace_supertonic"

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
    external fun nativeSynthesize(
        text: String,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
    ): String
}

/**
 * Synthesizer backed by [SupertonicJniBridge]. Used at Android runtime.
 */
class SupertonicJniSynthesizer(
    private val nativeLibDir: String,
    private val modelDir: String,
) : TtsSynthesizer {
    init {
        check(SupertonicJniBridge.ensureLoaded()) {
            "subspace_supertonic native library not available"
        }
        val initResult = SupertonicJniBridge.nativeInit(nativeLibDir)
        check(initResult == 0) { "nativeInit failed (code=$initResult)" }
        SupertonicJniBridge.nativeStartLoad(modelDir)
    }

    override val modelStatus: TtsModelStatus
        get() = when (SupertonicJniBridge.nativeLoadStatus()) {
            0 -> TtsModelStatus.Idle
            1 -> TtsModelStatus.Loading
            2 -> TtsModelStatus.Ready
            3 -> TtsModelStatus.Failed
            else -> TtsModelStatus.Failed
        }

    override val loadError: String?
        get() = SupertonicJniBridge.nativeLoadError()

    override fun synthesize(request: SynthesisRequest): SynthesisOutcome {
        if (request.text.isBlank()) return SynthesisOutcome.EmptyText
        val json = SupertonicJniBridge.nativeSynthesize(
            request.text,
            request.voiceStylePath,
            request.lang,
            request.totalSteps,
            request.speed,
        )
        return parseOutcome(json)
    }

    private fun parseOutcome(json: String): SynthesisOutcome {
        val outcome = extractIntField(json, "outcome")
            ?: return SynthesisOutcome.Failure("invalid native response: $json")
        return when (outcome) {
            0 -> SynthesisOutcome.Success(extractSamples(json))
            1 -> SynthesisOutcome.ModelNotReady
            2 -> SynthesisOutcome.Failure(extractStringField(json, "error").orEmpty())
            3 -> SynthesisOutcome.EmptyText
            else -> SynthesisOutcome.Failure("unknown outcome code: $outcome")
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

    private fun extractSamples(json: String): FloatArray {
        val key = "\"samples\":["
        val startIdx = json.indexOf(key)
        if (startIdx < 0) return FloatArray(0)
        var i = startIdx + key.length
        val values = mutableListOf<Float>()
        while (i < json.length) {
            val c = json[i]
            if (c == ']') break
            if (c == ',' || c.isWhitespace()) {
                i += 1
                continue
            }
            val start = i
            while (i < json.length && (json[i].isDigit() || json[i] == '.' || json[i] == '-' || json[i] == 'e' || json[i] == 'E' || json[i] == '+')) {
                i += 1
            }
            if (i > start) {
                values.add(json.substring(start, i).toFloatOrNull() ?: 0.0f)
            } else {
                i += 1
            }
        }
        return values.toFloatArray()
    }
}