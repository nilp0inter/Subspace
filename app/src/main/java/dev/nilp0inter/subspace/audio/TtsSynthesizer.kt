package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.TtsModelStatus

/**
 * Port for on-device text-to-speech synthesis.
 *
 * Implementations:
 * - [FakeTtsSynthesizer] for unit tests;
 * - [SupertonicJniSynthesizer] for Android runtime, backed by the native
 *   `subspace_supertonic` crate.
 *
 * Implementations MUST NOT perform network I/O or persist text or synthesized
 * audio.
 */
interface TtsSynthesizer {
    /** Current model readiness status. Safe to poll from any thread. */
    val modelStatus: TtsModelStatus

    /** Most recent model-load failure message, if any. */
    val loadError: String?

    /**
     * Synthesize speech from [request]. Blocks until the model is ready (or
     * loading fails) and synthesis completes. Callers MUST invoke this off
     * the main thread.
     */
    fun synthesize(request: SynthesisRequest): SynthesisOutcome
}

/**
 * Parameters for a synthesis call.
 *
 * [voiceStylePath] is the filesystem path to the voice style JSON (e.g.
 * `M1.json`). [totalSteps] is the number of denoising steps (quality).
 * [speed] is the speech speed factor (higher = faster).
 */
data class SynthesisRequest(
    val text: String,
    val voiceStylePath: String,
    val lang: String,
    val totalSteps: Int,
    val speed: Float,
)

sealed interface SynthesisOutcome {
    data class Success(val samples: FloatArray) : SynthesisOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && samples.contentEquals(other.samples))
        override fun hashCode(): Int = samples.contentHashCode()
    }
    data object ModelNotReady : SynthesisOutcome
    data class Failure(val reason: String) : SynthesisOutcome
    data object EmptyText : SynthesisOutcome
}