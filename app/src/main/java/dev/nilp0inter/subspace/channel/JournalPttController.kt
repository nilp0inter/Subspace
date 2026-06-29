package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureSession
import dev.nilp0inter.subspace.audio.CaptureSource
import dev.nilp0inter.subspace.audio.CaptureStartResult
import dev.nilp0inter.subspace.audio.JournalWavWriter
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.TelecomCapturePcmOutput
import dev.nilp0inter.subspace.audio.WavPcmReader
import dev.nilp0inter.subspace.model.JournalChannel
import java.io.File
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JournalPttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val output: PcmOutput,
    private val captureService: CaptureService,
    private val source: CaptureSource,
    private val journal: JournalController,
    private val channelProvider: () -> JournalChannel,
    private val pathGenerator: JournalEntryPaths = JournalEntryPaths(),
    private val metadataStore: JournalMetadataStore = JournalMetadataStore(),
) {
    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var framesJob: Job? = null
    private var activeSession: CaptureSession? = null
    private var activeWavWriter: JournalWavWriter? = null
    private var activeEntryPaths: JournalEntryPaths.EntryPaths? = null
    private var activeChannel: JournalChannel? = null

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (setupJob?.isActive == true || activeSession != null) return
        setupJob = scope.launch { startSession(route) }
    }

    fun onPttReleased() {
        onPttReleased(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        finishSession(route)
    }

    fun cancelAndRelease() {
        setupJob?.cancel()
        framesJob?.cancel()
        framesJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        activeWavWriter?.finalize()
        activeWavWriter = null
        activeEntryPaths = null
        activeChannel = null
        sco.release()
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        runCatching {
            val channel = channelProvider()
            if (!channel.isReady) return@runCatching
            val baseDirectory = channel.baseDirectory?.takeIf { it.isNotBlank() }
                ?: return@runCatching

            val startedAt = ZonedDateTime.now()
            val paths = pathGenerator.preparePaths(File(baseDirectory), startedAt)

            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { pttDown },
            )
            when (result) {
                CaptureStartResult.SessionActive -> {
                    route.sco.release()
                }
                CaptureStartResult.ScoUnavailable -> {
                    route.sco.release()
                }
                CaptureStartResult.Cancelled -> {
                    route.sco.release()
                }
                CaptureStartResult.RecordingFailed -> {
                    route.sco.release()
                }
                is CaptureStartResult.Started -> {
                    val session = result.session
                    val writer = JournalWavWriter(paths.captureFile, SAMPLE_RATE)
                    framesJob = scope.launch {
                        session.frames.collect { chunk ->
                            writer.writeChunk(chunk)
                        }
                    }
                    activeSession = session
                    activeWavWriter = writer
                    activeEntryPaths = paths
                    activeChannel = channel

                    val metadata = JournalEntryMetadata(
                        entryId = paths.stem,
                        startedAt = startedAt.toString(),
                        timezoneOffset = paths.timezoneOffset,
                        channel = MetadataChannelSnapshot(
                            id = channel.id,
                            saveVoice = channel.saveVoice,
                            saveText = channel.saveText,
                        ),
                        capture = CaptureState(state = CaptureTaskState.recording),
                    )
                    metadataStore.write(metadata, paths.metadataFile)
                }
            }
        }.onFailure {
            framesJob?.cancel()
            framesJob = null
            val session = activeSession
            activeSession = null
            if (session != null) {
                scope.launch { captureService.cancelSession(session) }
            }
            activeWavWriter?.finalize()
            activeWavWriter = null
            activeEntryPaths = null
            activeChannel = null
            route.sco.release()
        }
    }

    private fun finishSession() {
        finishSession(ResolvedAudioRoute(sco, output, source))
    }

    private fun finishSession(route: ResolvedAudioRoute) {
        val paths = activeEntryPaths ?: return
        val channel = activeChannel ?: return
        val session = activeSession ?: return
        val writer = activeWavWriter ?: return
        activeSession = null
        activeWavWriter = null
        activeEntryPaths = null
        activeChannel = null

        scope.launch {
            val telecomOutput = route.output as? TelecomCapturePcmOutput
            if (telecomOutput != null) {
                telecomOutput.releaseRoute()
            } else {
                route.output.play(RecordedPcm(shortArrayOf(), 16_000))
            }

            withContext(Dispatchers.IO) {
                framesJob?.cancel()
                framesJob = null
                session.stop()
                writer.finalize()
                val captureFile = paths.captureFile
                val wavInfo = WavPcmReader.read(captureFile)
                if (wavInfo != null && wavInfo.samples.isNotEmpty()) {
                    metadataStore.write(
                        JournalEntryMetadata(
                            entryId = paths.stem,
                            startedAt = readStartedAt(metadataStore, paths.metadataFile) ?: ZonedDateTime.now().toString(),
                            endedAt = ZonedDateTime.now().toString(),
                            timezoneOffset = paths.timezoneOffset,
                            channel = MetadataChannelSnapshot(
                                id = channel.id,
                                saveVoice = channel.saveVoice,
                                saveText = channel.saveText,
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
                            encoding = if (channel.saveVoice)
                                DerivedTaskState(state = DerivedTaskStatus.pending)
                            else
                                DerivedTaskState(state = DerivedTaskStatus.skipped),
                            transcription = if (channel.saveText)
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
                            startedAt = readStartedAt(metadataStore, paths.metadataFile) ?: ZonedDateTime.now().toString(),
                            endedAt = ZonedDateTime.now().toString(),
                            timezoneOffset = paths.timezoneOffset,
                            channel = MetadataChannelSnapshot(
                                id = channel.id,
                                saveVoice = channel.saveVoice,
                                saveText = channel.saveText,
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
                journal.processCaptureFile(paths)
            }
        }
    }

    private fun readStartedAt(store: JournalMetadataStore, file: File): String? =
        store.read(file)?.startedAt

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}