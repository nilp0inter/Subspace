package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.SttModelStatus

/**
 * Unit-test fake transcriber. Records the last submitted samples and returns
 * a configurable outcome. Default behavior: model is ready, transcribe
 * echoes the recorded sample count as the text.
 */
class FakeSttTranscriber(
    override var modelStatus: SttModelStatus = SttModelStatus.Ready,
    override var loadError: String? = null,
    private var outcomeFactory: ((FloatArray) -> TranscriptionOutcome)? = null,
) : SttTranscriber {
    var lastSamples: FloatArray? = null
        private set
    var callCount: Int = 0
        private set

    fun setOutcome(outcome: TranscriptionOutcome) {
        outcomeFactory = { outcome }
    }

    fun setOutcomeFactory(factory: (FloatArray) -> TranscriptionOutcome) {
        outcomeFactory = factory
    }

    override fun transcribe(samples: FloatArray): TranscriptionOutcome {
        callCount += 1
        lastSamples = samples.copyOf()
        if (samples.isEmpty()) return TranscriptionOutcome.EmptyInput
        return when (modelStatus) {
            SttModelStatus.Ready -> outcomeFactory?.invoke(samples)
                ?: TranscriptionOutcome.Success("transcript-${samples.size}")
            SttModelStatus.Failed -> TranscriptionOutcome.Failure(loadError ?: "model failed")
            else -> TranscriptionOutcome.ModelNotReady
        }
    }
}