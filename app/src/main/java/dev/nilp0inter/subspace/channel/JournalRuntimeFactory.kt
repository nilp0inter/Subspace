package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.WavPcmReader
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelKind
import dev.nilp0inter.subspace.model.JournalConfig
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelRuntime
import dev.nilp0inter.subspace.service.ChannelRuntimeFactory
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime

class JournalRuntimeFactory(
    private val scope: CoroutineScope,
    private val journalControllerProvider: () -> JournalController?,
    private val pathGenerator: JournalEntryPaths = JournalEntryPaths(),
    private val metadataStore: JournalMetadataStore = JournalMetadataStore(),
) : ChannelRuntimeFactory {
    override fun create(definition: ChannelDefinition): ChannelRuntime {
        return JournalRuntime(scope, definition, journalControllerProvider, pathGenerator, metadataStore)
    }
}

class JournalRuntime(
    parentScope: CoroutineScope,
    override var definition: ChannelDefinition,
    private val journalControllerProvider: () -> JournalController?,
    private val pathGenerator: JournalEntryPaths,
    private val metadataStore: JournalMetadataStore,
) : ChannelRuntime {

    private val runtimeScope = CoroutineScope(parentScope.coroutineContext + Job())
    
    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            kind = definition.kind,
            enabled = definition.enabled,
            isReady = evaluateReadiness(definition),
            executionStatus = ChannelExecutionStatus.IDLE
        )
    )
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override val id: String
        get() = definition.id

    private fun evaluateReadiness(def: ChannelDefinition): Boolean {
        val config = def.config as? JournalConfig ?: return false
        return !config.baseDirectory.isNullOrBlank() && (config.saveVoice || config.saveText)
    }

    override fun updateDefinition(definition: ChannelDefinition) {
        this.definition = definition
        _snapshot.value = _snapshot.value.copy(
            name = definition.name,
            enabled = definition.enabled,
            isReady = evaluateReadiness(definition)
        )
    }

    override fun prepareInput(): ChannelInputAcceptance {
        val config = definition.config as? JournalConfig
            ?: return ChannelInputAcceptance.Unavailable("Invalid configuration")
        if (!evaluateReadiness(definition)) {
            return ChannelInputAcceptance.Refused("Journal not ready or output directory not configured")
        }
        val baseDir = config.baseDirectory!!
        val startedAt = ZonedDateTime.now()
        val paths = pathGenerator.preparePaths(File(baseDir), startedAt)
        return ChannelInputAcceptance.Accepted(JournalInputTarget(paths, startedAt, config))
    }

    override fun close() {
        runtimeScope.cancel()
    }

    private inner class JournalInputTarget(
        private val paths: JournalEntryPaths.EntryPaths,
        private val startedAt: ZonedDateTime,
        private val config: JournalConfig
    ) : ChannelInputTarget {
        private var framesJob: Job? = null
        private var wavWriter: dev.nilp0inter.subspace.audio.JournalWavWriter? = null

        override fun onInputStarted(session: ChannelAudioInputSession) {
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
            val writer = dev.nilp0inter.subspace.audio.JournalWavWriter(paths.captureFile, session.sampleRate)
            wavWriter = writer
            
            // Write initial metadata
            metadataStore.write(
                JournalEntryMetadata(
                    entryId = paths.stem,
                    startedAt = startedAt.toString(),
                    timezoneOffset = paths.timezoneOffset,
                    channel = MetadataChannelSnapshot(
                        id = definition.id,
                        saveVoice = config.saveVoice,
                        saveText = config.saveText,
                    ),
                    capture = CaptureState(state = CaptureTaskState.recording),
                ),
                paths.metadataFile,
            )

            framesJob = runtimeScope.launch {
                session.frames.collect { chunk ->
                    writer.writeChunk(chunk)
                }
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
            val writer = wavWriter
            val fJob = framesJob
            wavWriter = null
            framesJob = null
            
            withContext(Dispatchers.IO) {
                fJob?.cancelAndJoin()
                writer?.finalize()
                
                val captureFile = paths.captureFile
                val wavInfo = WavPcmReader.read(captureFile)
                if (wavInfo != null && wavInfo.samples.isNotEmpty()) {
                    metadataStore.write(
                        JournalEntryMetadata(
                            entryId = paths.stem,
                            startedAt = readStartedAt() ?: startedAt.toString(),
                            endedAt = ZonedDateTime.now().toString(),
                            timezoneOffset = paths.timezoneOffset,
                            channel = MetadataChannelSnapshot(
                                id = definition.id,
                                saveVoice = config.saveVoice,
                                saveText = config.saveText,
                            ),
                            capture = CaptureState(
                                state = CaptureTaskState.finished,
                                path = captureFile.name,
                                sampleRate = wavInfo.sampleRate,
                                channels = wavInfo.channelCount,
                                encoding = "pcm_s16le",
                                durationMs = wavInfo.durationMs,
                                bytes = wavInfo.dataSize,
                            ),
                            encoding = if (config.saveVoice)
                                DerivedTaskState(state = DerivedTaskStatus.pending)
                            else
                                DerivedTaskState(state = DerivedTaskStatus.skipped),
                            transcription = if (config.saveText)
                                DerivedTaskState(state = DerivedTaskStatus.pending)
                            else
                                DerivedTaskState(state = DerivedTaskStatus.skipped),
                        ),
                        paths.metadataFile,
                    )
                } else {
                    metadataStore.write(
                        JournalEntryMetadata(
                            entryId = paths.stem,
                            startedAt = readStartedAt() ?: startedAt.toString(),
                            endedAt = ZonedDateTime.now().toString(),
                            timezoneOffset = paths.timezoneOffset,
                            channel = MetadataChannelSnapshot(
                                id = definition.id,
                                saveVoice = config.saveVoice,
                                saveText = config.saveText,
                            ),
                            capture = CaptureState(
                                state = CaptureTaskState.failed,
                                error = if (wavInfo == null) "WAV read failed" else "Empty capture",
                            ),
                            encoding = DerivedTaskState(state = DerivedTaskStatus.skipped),
                            transcription = DerivedTaskState(state = DerivedTaskStatus.skipped),
                        ),
                        paths.metadataFile,
                    )
                }
                
                val jc = journalControllerProvider() ?: throw IllegalStateException("JournalController not initialized")
                jc.processCaptureFile(paths).join()
            }
            
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
            return ChannelInputResult.None
        }

        override fun onInputCancelled(reason: String) {
            cleanup()
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
        }

        override fun onInputFailed(reason: String) {
            cleanup()
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
        }

        private fun cleanup() {
            framesJob?.cancel()
            framesJob = null
            wavWriter?.finalize()
            wavWriter = null
        }

        private fun readStartedAt(): String? {
            return try {
                metadataStore.read(paths.metadataFile)?.startedAt
            } catch (e: Exception) {
                null
            }
        }
    }
}
