package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.DebugConfig
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.MonitorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class DebugRuntimeFactory(
    private val echoController: EchoController,
    private val sttControllerProvider: () -> SttController?,
    private val ttsControllerProvider: () -> TtsController?,
    private val sttTtsControllerProvider: () -> SttTtsController?,
    private val modelDirProvider: () -> File?,
    private val monitorStateProvider: () -> MonitorState
) : ChannelRuntimeFactory {
    override fun create(definition: ChannelDefinition): ChannelRuntime {
        return DebugRuntime(
            definition,
            echoController,
            sttControllerProvider,
            ttsControllerProvider,
            sttTtsControllerProvider,
            modelDirProvider,
            monitorStateProvider
        )
    }
}

class DebugRuntime(
    override var definition: ChannelDefinition,
    private val echoController: EchoController,
    private val sttControllerProvider: () -> SttController?,
    private val ttsControllerProvider: () -> TtsController?,
    private val sttTtsControllerProvider: () -> SttTtsController?,
    private val modelDirProvider: () -> File?,
    private val monitorStateProvider: () -> MonitorState
) : ChannelRuntime {

    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            kind = definition.kind,
            enabled = definition.enabled,
            isReady = evaluateReadiness(definition),
            executionStatus = ChannelExecutionStatus.IDLE,
            summary = (definition.config as? DebugConfig)?.mode?.name
        )
    )
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override val id: String
        get() = definition.id

    private fun evaluateReadiness(def: ChannelDefinition): Boolean {
        if (!def.enabled) return false
        val config = def.config as? DebugConfig ?: return false
        return when (config.mode) {
            DebugMode.ECHO -> true
            DebugMode.STT -> sttControllerProvider() != null
            DebugMode.TTS -> ttsControllerProvider() != null && modelDirProvider() != null
            DebugMode.STT_TTS -> sttTtsControllerProvider() != null && modelDirProvider() != null
        }
    }

    override fun updateDefinition(definition: ChannelDefinition) {
        this.definition = definition
        _snapshot.value = _snapshot.value.copy(
            name = definition.name,
            enabled = definition.enabled,
            isReady = evaluateReadiness(definition),
            summary = (definition.config as? DebugConfig)?.mode?.name
        )
    }

    override fun refreshReadiness() {
        _snapshot.value = _snapshot.value.copy(
            isReady = evaluateReadiness(definition)
        )
    }
    override fun prepareInput(): ChannelInputAcceptance {
        val config = definition.config as? DebugConfig
            ?: return ChannelInputAcceptance.Unavailable("Invalid configuration")
        if (!evaluateReadiness(definition)) {
            return ChannelInputAcceptance.Refused("Debug channel mode dependencies unavailable")
        }

        val monitor = monitorStateProvider()
        return when (config.mode) {
            DebugMode.ECHO -> {
                if (!echoController.enabled) {
                    ChannelInputAcceptance.Refused("Echo debug channel disabled")
                } else {
                    ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                        override fun onInputStarted(session: ChannelAudioInputSession) {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
                            echoController.onInputStarted(session)
                        }

                        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
                            val result = echoController.onInputReleased(recording)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                            return result
                        }

                        override fun onInputPlaybackCompleted() {
                            echoController.onInputPlaybackCompleted()
                        }

                        override fun onInputCancelled(reason: String) {
                            echoController.onInputCancelled(reason)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }

                        override fun onInputFailed(reason: String) {
                            echoController.onInputFailed(reason)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }
                    })
                }
            }
            DebugMode.STT -> {
                val stt = sttControllerProvider()
                    ?: return ChannelInputAcceptance.Unavailable("STT controller unavailable")
                if (!stt.enabled) {
                    ChannelInputAcceptance.Refused("STT debug channel disabled")
                } else {
                    ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                        override fun onInputStarted(session: ChannelAudioInputSession) {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
                            stt.onInputStarted(session)
                        }

                        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
                            val result = stt.onInputReleased(recording)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                            return result
                        }

                        override fun onInputCancelled(reason: String) {
                            stt.onInputCancelled(reason)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }

                        override fun onInputFailed(reason: String) {
                            stt.onInputFailed(reason)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }
                    })
                }
            }
            DebugMode.TTS -> {
                val tts = ttsControllerProvider()
                    ?: return ChannelInputAcceptance.Unavailable("TTS controller unavailable")
                val modelDir = modelDirProvider()
                    ?: return ChannelInputAcceptance.Unavailable("TTS model directory unavailable")
                if (!tts.enabled) {
                    ChannelInputAcceptance.Refused("TTS debug channel disabled")
                } else {
                    val text = monitor.ttsText
                    val voiceStylePath = File(modelDir, "${monitor.ttsVoiceStyle}.json").absolutePath
                    val lang = monitor.ttsLang
                    val totalSteps = monitor.ttsTotalSteps
                    val speed = monitor.ttsSpeed
                    ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                        override fun onInputStarted(session: ChannelAudioInputSession) {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
                        }

                        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
                            val result = tts.onInputReleased(text, voiceStylePath, lang, totalSteps, speed, SCO_RATE)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                            return result
                        }

                        override fun onInputPlaybackCompleted() {
                            tts.onInputPlaybackCompleted()
                        }

                        override fun onInputCancelled(reason: String) {
                            tts.cancelAndRelease()
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }

                        override fun onInputFailed(reason: String) {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }
                    })
                }
            }
            DebugMode.STT_TTS -> {
                val sttTts = sttTtsControllerProvider()
                    ?: return ChannelInputAcceptance.Unavailable("STT/TTS controller unavailable")
                val modelDir = modelDirProvider()
                    ?: return ChannelInputAcceptance.Unavailable("STT/TTS model directory unavailable")
                if (!sttTts.enabled) {
                    ChannelInputAcceptance.Refused("STT/TTS debug channel disabled")
                } else {
                    val voiceStylePath = File(modelDir, "${monitor.sttTtsVoiceStyle}.json").absolutePath
                    val lang = monitor.sttTtsLang
                    val totalSteps = monitor.sttTtsTotalSteps
                    val speed = monitor.sttTtsSpeed
                    ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                        override fun onInputStarted(session: ChannelAudioInputSession) {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
                            sttTts.onInputStarted(session)
                        }

                        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
                            val result = sttTts.onInputReleased(recording, voiceStylePath, lang, totalSteps, speed, SCO_RATE)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                            return result
                        }

                        override fun onInputPlaybackCompleted() {
                            sttTts.onInputPlaybackCompleted()
                        }

                        override fun onInputCancelled(reason: String) {
                            sttTts.onInputCancelled(reason)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }

                        override fun onInputFailed(reason: String) {
                            sttTts.onInputFailed(reason)
                            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
                        }
                    })
                }
            }
        }
    }

    override fun close() {
        // No coroutine scope is held/managed directly by DebugRuntime
    }

    companion object {
        private const val SCO_RATE = 16_000
    }
}
