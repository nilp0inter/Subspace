package dev.nilp0inter.subspace.channel.capability

import android.os.SystemClock
import android.util.Log

import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.SynthesisOutcome
import dev.nilp0inter.subspace.audio.SynthesisRequest
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Host-owned wrappers for legacy audio values. They are internal, so providers and
 * runtimes can hold only the opaque interfaces declared in this package.
 */
internal class RecordedPcmAudioRecording(
    internal val recording: RecordedPcm,
    override val operationId: String = UUID.randomUUID().toString(),
) : OpaqueAudioRecording

internal class SynthesizedAudioArtifact(
    internal val samples: FloatArray,
    override val operationId: String = UUID.randomUUID().toString(),
) : OpaqueSynthesizedAudio

internal class AudioOperationArtifact(
    internal val recording: RecordedPcm,
    override val operationId: String = UUID.randomUUID().toString(),
) : OpaqueAudioOperation

internal fun opaqueAudioRecording(recording: RecordedPcm): OpaqueAudioRecording =
    RecordedPcmAudioRecording(recording)

/** Composition-only escape hatch; it is unavailable to provider/runtime contracts. */
internal fun recordedPcmOf(recording: OpaqueAudioRecording): RecordedPcm? =
    (recording as? RecordedPcmAudioRecording)?.recording

/** Composition-only resolver for deferred host-owned playback. */
internal fun recordedPcmOf(operation: OpaqueAudioOperation): RecordedPcm? =
    (operation as? AudioOperationArtifact)?.recording

/** Bridges the legacy transcriber without admitting raw sample arrays to a runtime port. */
internal class TranscriptionCapabilityAdapter(
    private val transcriber: PcmTranscriber,
) : TranscriptionCapability {
    override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> {
        val recorded = (recording as? RecordedPcmAudioRecording)?.recording
            ?: return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        if (recorded.isEmpty) return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(CHANNEL_EFFECT_TAG, "TRANSCRIPTION_START operation=${recording.operationId} samples=${recorded.samples.size} rate=${recorded.sampleRate}")
        return try {
            val text = transcriber.transcribe(recorded.samples, recorded.sampleRate)
            Log.i(
                CHANNEL_EFFECT_TAG,
                "TRANSCRIPTION_SUCCESS operation=${recording.operationId} text_length=${text.length} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            CapabilityOperationResult.Success(Transcription(text))
        } catch (_: CancellationException) {
            Log.i(CHANNEL_EFFECT_TAG, "TRANSCRIPTION_CANCELLED operation=${recording.operationId}")
            CapabilityOperationResult.Cancelled
        } catch (error: Exception) {
            Log.i(
                CHANNEL_EFFECT_TAG,
                "TRANSCRIPTION_FAILED operation=${recording.operationId} type=${error::class.simpleName} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }
}

/**
 * Host-side resolver for a logical voice identity. It deliberately keeps filesystem
 * paths on the adapter side of the boundary.
 */
internal fun interface SpeechSynthesisParametersResolver {
    fun resolve(voice: SpeechVoice): SpeechSynthesisParameters?
}

internal data class SpeechSynthesisParameters(
    val voiceStylePath: String,
    val totalSteps: Int,
)

/** Bridges the existing synthesizer while retaining generated samples as an opaque artifact. */
internal class SynthesisCapabilityAdapter(
    private val synthesizer: TtsSynthesizer,
    private val parameters: SpeechSynthesisParametersResolver,
) : SynthesisCapability {
    override suspend fun synthesize(request: SpeechSynthesisRequest): CapabilityOperationResult<OpaqueSynthesizedAudio> {
        if (request.text.isBlank()) return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        val resolved = parameters.resolve(request.voice)
            ?: return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(
            CHANNEL_EFFECT_TAG,
            "SYNTHESIS_START language=${request.languageTag} text_length=${request.text.length} steps=${resolved.totalSteps}",
        )
        return try {
            when (
                val outcome = synthesizer.synthesize(
                    SynthesisRequest(
                        text = request.text,
                        voiceStylePath = resolved.voiceStylePath,
                        lang = request.languageTag,
                        totalSteps = resolved.totalSteps,
                        speed = request.speed,
                    ),
                )
            ) {
                is SynthesisOutcome.Success -> {
                    Log.i(
                        CHANNEL_EFFECT_TAG,
                        "SYNTHESIS_SUCCESS samples=${outcome.samples.size} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                    CapabilityOperationResult.Success(SynthesizedAudioArtifact(outcome.samples))
                }
                SynthesisOutcome.ModelNotReady -> {
                    Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_UNAVAILABLE reason=model_not_ready")
                    CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.MODEL_NOT_READY)
                }
                SynthesisOutcome.EmptyText -> {
                    Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_FAILED reason=empty_text")
                    CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
                }
                is SynthesisOutcome.Failure -> {
                    Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_FAILED reason=host_failure")
                    CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
                }
            }
        } catch (_: CancellationException) {
            Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_CANCELLED")
            CapabilityOperationResult.Cancelled
        } catch (error: Exception) {
            Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_FAILED type=${error::class.simpleName}")
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }
}

/**
 * Host composition supplies the playback bridge. The bridge receives samples only in
 * this internal adapter; runtime contracts receive an [OpaqueAudioOperation] instead.
 */
internal fun interface PlaybackResultFactory {
    suspend fun create(samples: FloatArray): OpaqueAudioOperation
}

internal class AudioOperationCapabilityAdapter(
    private val playbackResults: PlaybackResultFactory,
) : AudioOperationCapability {
    override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio): CapabilityOperationResult<OpaqueAudioOperation> {
        val synthesized = audio as? SynthesizedAudioArtifact
            ?: return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        return try {
            CapabilityOperationResult.Success(playbackResults.create(synthesized.samples))
        } catch (_: CancellationException) {
            CapabilityOperationResult.Cancelled
        } catch (_: Exception) {
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }
}

/** Internal host backend used to adapt existing Journal persistence and derivation code. */
internal interface JournalStorageBackend {
    suspend fun createEntry(request: JournalEntryRequest): JournalEntryHandle
    suspend fun storeCapture(entry: JournalEntryHandle, recording: OpaqueAudioRecording): JournalStoredCapture
    suspend fun derive(entry: JournalEntryHandle): JournalDerivation
}

/** Opaque host handle implementations keyed only by a host operation ID. */
internal data class HostJournalEntryHandle(
    override val operationId: String = UUID.randomUUID().toString(),
) : JournalEntryHandle

internal data class HostJournalStoredCapture(
    override val operationId: String = UUID.randomUUID().toString(),
) : JournalStoredCapture

internal data class HostJournalDerivation(
    override val operationId: String = UUID.randomUUID().toString(),
) : JournalDerivation

internal fun journalEntryHandle(operationId: String = UUID.randomUUID().toString()): JournalEntryHandle =
    HostJournalEntryHandle(operationId)

internal fun journalStoredCapture(operationId: String = UUID.randomUUID().toString()): JournalStoredCapture =
    HostJournalStoredCapture(operationId)

internal fun journalDerivation(operationId: String = UUID.randomUUID().toString()): JournalDerivation =
    HostJournalDerivation(operationId)

internal class JournalStorageCapabilityAdapter(
    private val backend: JournalStorageBackend,
) : JournalStorageCapability {
    override suspend fun createEntry(request: JournalEntryRequest): CapabilityOperationResult<JournalEntryHandle> =
        invokeBackend { backend.createEntry(request) }

    override suspend fun storeCapture(
        entry: JournalEntryHandle,
        recording: OpaqueAudioRecording,
    ): CapabilityOperationResult<JournalStoredCapture> = invokeBackend { backend.storeCapture(entry, recording) }

    override suspend fun derive(entry: JournalEntryHandle): CapabilityOperationResult<JournalDerivation> =
        invokeBackend { backend.derive(entry) }

    private suspend fun <T> invokeBackend(block: suspend () -> T): CapabilityOperationResult<T> = try {
        CapabilityOperationResult.Success(block())
    } catch (_: CancellationException) {
        CapabilityOperationResult.Cancelled
    } catch (_: Exception) {
        CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
    }
}

private const val CHANNEL_EFFECT_TAG = "SubspaceChannel"
