package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.FileWavRecorder
import dev.nilp0inter.subspace.audio.NoopRecorder
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.WavPcmReader
import dev.nilp0inter.subspace.model.JournalChannel
import java.io.File
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JournalPttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val output: PcmOutput,
    private val journal: JournalController,
    private val channelProvider: () -> JournalChannel,
    private val pathGenerator: JournalEntryPaths = JournalEntryPaths(),
    private val metadataStore: JournalMetadataStore = JournalMetadataStore(),
) {
    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var activeRecorder: FileWavRecorder? = null
    private var activeEntryPaths: JournalEntryPaths.EntryPaths? = null
    private var activeChannel: JournalChannel? = null

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, NoopRecorder()))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        if (setupJob?.isActive == true || activeRecorder?.isActive == true) return
        setupJob = scope.launch { startSession(route) }
    }

    fun onPttReleased() {
        onPttReleased(ResolvedAudioRoute(sco, output, NoopRecorder()))
    }

    fun onPttReleased(route: ResolvedAudioRoute) {
        pttDown = false
        finishSession(route)
    }

    fun cancelAndRelease() {
        setupJob?.cancel()
        activeRecorder?.stop()
        activeRecorder = null
        activeEntryPaths = null
        activeChannel = null
        sco.release()
    }

    private suspend fun startSession() {
        startSession(ResolvedAudioRoute(sco, output, NoopRecorder()))
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        runCatching {
            val channel = channelProvider()
            if (!channel.isReady) return@runCatching
            val baseDirectory = channel.baseDirectory?.takeIf { it.isNotBlank() }
                ?: return@runCatching

            if (!route.sco.acquire()) return@runCatching
            if (!pttDown) {
                route.sco.release()
                return@runCatching
            }

            val startedAt = ZonedDateTime.now()
            val paths = pathGenerator.preparePaths(File(baseDirectory), startedAt)
            if (!pttDown) {
                route.sco.release()
                return@runCatching
            }

            route.output.playReadyBeep(route.sco.coldStart)
            if (!pttDown) {
                route.sco.release()
                return@runCatching
            }

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

            val recorder = FileWavRecorder(scope, paths.captureFile)
            if (!recorder.start()) {
                route.sco.release()
                return@runCatching
            }

            activeRecorder = recorder
            activeEntryPaths = paths
            activeChannel = channel
        }.onFailure {
            activeRecorder?.stop()
            activeRecorder = null
            activeEntryPaths = null
            activeChannel = null
            route.sco.release()
        }
    }

    private fun finishSession() {
        finishSession(ResolvedAudioRoute(sco, output, NoopRecorder()))
    }

    private fun finishSession(route: ResolvedAudioRoute) {
        val paths = activeEntryPaths ?: return
        val channel = activeChannel ?: return
        val recorder = activeRecorder ?: return
        activeRecorder = null
        activeEntryPaths = null
        activeChannel = null

        scope.launch {
            recorder.stop()
            val captureFile = recorder.captureFile
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
            route.sco.release()
            journal.processCaptureFile(paths)
        }
    }

    private fun readStartedAt(store: JournalMetadataStore, file: File): String? =
        store.read(file)?.startedAt
}
