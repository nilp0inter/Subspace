package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttModelStatus

/**
 * Port for on-device speech-to-text transcription of normalized 16 kHz mono
 * `f32` samples.
 *
 * Implementations:
 * - [FakeSttTranscriber] for unit tests;
 * - [ParakeetJniTranscriber] for Android runtime, backed by the native
 *   `subspace_parakeet` crate.
 *
 * Implementations MUST NOT perform network I/O or persist captured audio.
 */
interface SttTranscriber {
    /** Current model readiness status. Safe to poll from any thread. */
    val modelStatus: SttModelStatus

    /** Most recent model-load failure message, if any. */
    val loadError: String?

    /**
     * Transcribe [samples]. Blocks until the model is ready (or loading fails)
     * and inference completes. Callers MUST invoke this off the main thread.
     */
    fun transcribe(samples: FloatArray): TranscriptionOutcome
}

sealed interface TranscriptionOutcome {
    data class Success(val text: String) : TranscriptionOutcome
    data object ModelNotReady : TranscriptionOutcome
    data class Failure(val reason: String) : TranscriptionOutcome
    data object EmptyInput : TranscriptionOutcome
}