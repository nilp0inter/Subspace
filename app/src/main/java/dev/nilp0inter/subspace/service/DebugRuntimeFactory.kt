package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.SpeechSynthesisRequest
import dev.nilp0inter.subspace.channel.capability.SpeechVoice
import dev.nilp0inter.subspace.channel.capability.opaqueAudioRecording
import dev.nilp0inter.subspace.channel.preparationFor
import dev.nilp0inter.subspace.channel.useCapability
import dev.nilp0inter.subspace.model.BuiltInChannelDescriptors
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.DebugProviderConfiguration
import dev.nilp0inter.subspace.model.DebugProviderConfigurationCodec
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider-backed Debug runtime construction without controller, model-path, or service access. */
class DebugBuiltInProvider : ChannelImplementationProvider {
    override val descriptor = BuiltInChannelDescriptors.debug

    override suspend fun constructRuntime(
        request: ChannelRuntimeConstructionRequest,
    ): ChannelRuntimeConstructionResult {
        val configuration = DebugProviderConfigurationCodec.decode(request.configuration.payload)
            .getOrElse { error ->
                return ChannelRuntimeConstructionResult.Failure(
                    ChannelProviderError.RuntimeConstructionFailed(
                        descriptor.implementationId,
                        error.message ?: "Invalid Debug configuration",
                    ),
                )
            }
        return ChannelRuntimeConstructionResult.Success(
            DebugRuntime(
                definition = request.definition,
                configuration = configuration,
                capabilities = request.capabilities,
                initialPreparation = initialPreparation(configuration, request.capabilities),
            ),
        )
    }

    private suspend fun initialPreparation(
        configuration: DebugProviderConfiguration,
        capabilities: ChannelCapabilityScope,
    ): ChannelPreparationAvailability = when (configuration.mode) {
        DebugMode.ECHO -> ChannelPreparationAvailability.Available
        DebugMode.STT -> capabilities.preparationFor(CapabilityKey.Transcription, recoverable = false)
        DebugMode.TTS -> requireAvailable(
            capabilities.preparationFor(CapabilityKey.Synthesis, recoverable = false),
            capabilities.preparationFor(CapabilityKey.AudioOperation, recoverable = false),
        )
        DebugMode.STT_TTS -> requireAvailable(
            capabilities.preparationFor(CapabilityKey.Transcription, recoverable = false),
            capabilities.preparationFor(CapabilityKey.Synthesis, recoverable = false),
            capabilities.preparationFor(CapabilityKey.AudioOperation, recoverable = false),
        )
    }

    private fun requireAvailable(
        vararg states: ChannelPreparationAvailability,
    ): ChannelPreparationAvailability = states.firstOrNull {
        it !is ChannelPreparationAvailability.Available
    } ?: ChannelPreparationAvailability.Available
}

/**
 * Debug modes exercise generic input and semantic capability contracts. ECHO returns the host
 * recording for host-owned playback; STT and TTS modes neither access controllers nor paths.
 */
class DebugRuntime(
    val definition: dev.nilp0inter.subspace.model.ChannelDefinition,
    private val configuration: DebugProviderConfiguration,
    private val capabilities: ChannelCapabilityScope,
    initialPreparation: ChannelPreparationAvailability,
) : ChannelRuntime {
    private val closed = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            implementationId = definition.implementationId,
            enabled = definition.enabled,
            preparation = if (definition.enabled) initialPreparation else disabledPreparation(),
            executionStatus = ChannelExecutionStatus.IDLE,
            summary = configuration.mode.name,
        ),
    )
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override val id: String
        get() = definition.id

    override suspend fun prepareInput(): ChannelInputAcceptance {
        if (closed.get()) return ChannelInputAcceptance.Unavailable("Debug runtime is closed")
        if (!definition.enabled) return ChannelInputAcceptance.Refused("Debug channel is disabled")
        val preparation = currentPreparation()
        _snapshot.value = _snapshot.value.copy(preparation = preparation)
        return if (preparation is ChannelPreparationAvailability.Available) {
            ChannelInputAcceptance.Accepted(DebugInputTarget())
        } else {
            ChannelInputAcceptance.Refused(preparationReason(preparation))
        }
    }

    override suspend fun refreshReadiness() {
        if (!closed.get()) {
            _snapshot.value = _snapshot.value.copy(
                preparation = if (definition.enabled) currentPreparation() else disabledPreparation(),
            )
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            _snapshot.value = _snapshot.value.copy(
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
            )
        }
    }

    private suspend fun currentPreparation(): ChannelPreparationAvailability = when (configuration.mode) {
        DebugMode.ECHO -> ChannelPreparationAvailability.Available
        DebugMode.STT -> capabilities.preparationFor(CapabilityKey.Transcription, recoverable = false)
        DebugMode.TTS -> firstUnavailable(
            capabilities.preparationFor(CapabilityKey.Synthesis, recoverable = false),
            capabilities.preparationFor(CapabilityKey.AudioOperation, recoverable = false),
        )
        DebugMode.STT_TTS -> firstUnavailable(
            capabilities.preparationFor(CapabilityKey.Transcription, recoverable = false),
            capabilities.preparationFor(CapabilityKey.Synthesis, recoverable = false),
            capabilities.preparationFor(CapabilityKey.AudioOperation, recoverable = false),
        )
    }

    private fun firstUnavailable(
        vararg states: ChannelPreparationAvailability,
    ): ChannelPreparationAvailability = states.firstOrNull {
        it !is ChannelPreparationAvailability.Available
    } ?: ChannelPreparationAvailability.Available

    private inner class DebugInputTarget : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
            return when (configuration.mode) {
                DebugMode.ECHO -> ChannelInputResult.Playback(recording)
                DebugMode.STT -> completeWithoutPlayback(processStt(recording))
                DebugMode.TTS -> completeWithPlayback(synthesizeAndQueue(DEFAULT_DEBUG_TEXT))
                DebugMode.STT_TTS -> when (val transcription = processTranscription(recording)) {
                    is CapabilityOperationResult.Success -> completeWithPlayback(
                        synthesizeAndQueue(transcription.value.text),
                    )
                    else -> completeWithoutPlayback(transcription)
                }
            }
        }

        override fun onInputPlaybackCompleted() {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.SUCCESS)
            }
        }

        override fun onInputCancelled(reason: String) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
            }
        }

        override fun onInputFailed(reason: String) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
            }
        }
    }

    private suspend fun processStt(recording: RecordedPcm): CapabilityOperationResult<Unit> =
        processTranscription(recording).mapToUnit()

    private suspend fun processTranscription(
        recording: RecordedPcm,
    ): CapabilityOperationResult<dev.nilp0inter.subspace.channel.capability.Transcription> =
        capabilities.useCapability(CapabilityKey.Transcription) { transcription ->
            transcription.transcribe(opaqueAudioRecording(recording))
        }

    private suspend fun synthesizeAndQueue(text: String): CapabilityOperationResult<OpaqueAudioOperation> {
        val synthesized = capabilities.useCapability(CapabilityKey.Synthesis) { synthesis ->
            synthesis.synthesize(
                SpeechSynthesisRequest(
                    text = text,
                    languageTag = DEFAULT_LANGUAGE_TAG,
                    voice = SpeechVoice(DEFAULT_VOICE_ID),
                ),
            )
        }
        return when (synthesized) {
            is CapabilityOperationResult.Success -> capabilities.useCapability(CapabilityKey.AudioOperation) { audio ->
                audio.createPlaybackResult(synthesized.value)
            }
            else -> synthesized.mapFailure()
        }
    }

    private fun completeWithoutPlayback(result: CapabilityOperationResult<*>): ChannelInputResult {
        publishCompletion(result)
        return ChannelInputResult.None
    }

    private fun completeWithPlayback(
        result: CapabilityOperationResult<OpaqueAudioOperation>,
    ): ChannelInputResult = when (result) {
        is CapabilityOperationResult.Success -> ChannelInputResult.PlaybackOperation(result.value)
        else -> {
            publishCompletion(result)
            ChannelInputResult.None
        }
    }

    private fun publishCompletion(result: CapabilityOperationResult<*>) {
        if (!closed.get()) {
            _snapshot.value = _snapshot.value.copy(
                executionStatus = if (result is CapabilityOperationResult.Success) {
                    ChannelExecutionStatus.SUCCESS
                } else {
                    ChannelExecutionStatus.FAILED
                },
            )
        }
    }

    private fun preparationReason(preparation: ChannelPreparationAvailability): String = when (preparation) {
        ChannelPreparationAvailability.Available -> "Debug channel is ready"
        is ChannelPreparationAvailability.Recoverable -> "Debug channel is preparing"
        is ChannelPreparationAvailability.Unavailable -> when (val reason = preparation.reason) {
            is ChannelPreparationReason.RuntimeFailed -> reason.message
            else -> "Debug channel dependencies are unavailable"
        }
    }

    private companion object {
        const val DEFAULT_DEBUG_TEXT = "Debug synthesis test"
        const val DEFAULT_LANGUAGE_TAG = "en"
        const val DEFAULT_VOICE_ID = "default"
    }
}

private fun <T> CapabilityOperationResult<T>.mapToUnit(): CapabilityOperationResult<Unit> = when (this) {
    is CapabilityOperationResult.Success -> CapabilityOperationResult.Success(Unit)
    is CapabilityOperationResult.Unavailable -> CapabilityOperationResult.Unavailable(reason)
    is CapabilityOperationResult.Denied -> CapabilityOperationResult.Denied(reason)
    CapabilityOperationResult.Closed -> CapabilityOperationResult.Closed
    CapabilityOperationResult.Cancelled -> CapabilityOperationResult.Cancelled
    is CapabilityOperationResult.Failed -> CapabilityOperationResult.Failed(reason)
}

private fun <T> CapabilityOperationResult<*>.mapFailure(): CapabilityOperationResult<T> = when (this) {
    is CapabilityOperationResult.Success -> error("Successful capability result cannot be mapped as failure")
    is CapabilityOperationResult.Unavailable -> CapabilityOperationResult.Unavailable(reason)
    is CapabilityOperationResult.Denied -> CapabilityOperationResult.Denied(reason)
    CapabilityOperationResult.Closed -> CapabilityOperationResult.Closed
    CapabilityOperationResult.Cancelled -> CapabilityOperationResult.Cancelled
    is CapabilityOperationResult.Failed -> CapabilityOperationResult.Failed(reason)
}

private fun disabledPreparation(): ChannelPreparationAvailability.Unavailable =
    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled)
