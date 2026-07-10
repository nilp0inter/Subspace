package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureSession
import dev.nilp0inter.subspace.audio.CaptureSource
import dev.nilp0inter.subspace.audio.CaptureStartResult
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.JournalWavWriter
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.WavPcmReader
import dev.nilp0inter.subspace.model.JournalChannel
import java.io.File
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
        // framesJob is joined inside finishSession on the normal path; for
        // the cancel-and-release teardown path we cancel it here and let
        // the writer's thread-safe finalize be the safety net.
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
        scope.launch { output.releaseRoute() }
    }

    fun prepareInput(): ChannelInputAcceptance {
        val channel = channelProvider()
        if (!channel.isReady) return ChannelInputAcceptance.Refused("Journal channel not ready")
        val baseDirectory = channel.baseDirectory?.takeIf { it.isNotBlank() }
            ?: return ChannelInputAcceptance.Refused("Journal base directory unavailable")
        val startedAt = ZonedDateTime.now()
        val paths = pathGenerator.preparePaths(File(baseDirectory), startedAt)
        return ChannelInputAcceptance.Accepted(JournalInputTarget(paths, startedAt, channel))
    }

    fun onInputStarted(session: ChannelAudioInputSession) {
        when (val acceptance = prepareInput()) {
            is ChannelInputAcceptance.Accepted -> acceptance.target.onInputStarted(session)
            is ChannelInputAcceptance.Refused -> onInputFailed()
            is ChannelInputAcceptance.Unavailable -> onInputFailed()
        }
    }

    suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
        val paths = activeEntryPaths ?: return ChannelInputResult.None
        val channel = activeChannel ?: return ChannelInputResult.None
        val writer = activeWavWriter ?: return ChannelInputResult.None
        val fJob = framesJob
        activeWavWriter = null
        activeEntryPaths = null
        activeChannel = null
        framesJob = null
        withContext(Dispatchers.IO) {
            fJob?.cancelAndJoin()
            writer.finalize()
            writeTerminalMetadata(paths, channel)
            journal.processCaptureFile(paths).join()
        }
        return ChannelInputResult.None
    }

    fun onInputCancelled() {
        framesJob?.cancel()
        framesJob = null
        activeWavWriter?.finalize()
        activeWavWriter = null
        activeEntryPaths = null
        activeChannel = null
    }

    fun onInputFailed() {
        onInputCancelled()
    }

    private inner class JournalInputTarget(
        private val paths: JournalEntryPaths.EntryPaths,
        private val startedAt: ZonedDateTime,
        private val channel: JournalChannel,
    ) : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) {
            val writer = JournalWavWriter(paths.captureFile, session.sampleRate)
            framesJob = scope.launch {
                session.frames.collect { chunk ->
                    writer.writeChunk(chunk)
                }
            }
            activeWavWriter = writer
            activeEntryPaths = paths
            activeChannel = channel
            metadataStore.write(
                JournalEntryMetadata(
                    entryId = paths.stem,
                    startedAt = startedAt.toString(),
                    timezoneOffset = paths.timezoneOffset,
                    channel = MetadataChannelSnapshot(
                        id = channel.id,
                        saveVoice = channel.saveVoice,
                        saveText = channel.saveText,
                    ),
                    capture = CaptureState(state = CaptureTaskState.recording),
                ),
                paths.metadataFile,
            )
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult =
            this@JournalPttController.onInputReleased(recording)

        override fun onInputCancelled(reason: String) {
            this@JournalPttController.onInputCancelled()
        }

        override fun onInputFailed(reason: String) {
            this@JournalPttController.onInputFailed()
        }
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
                    // No SCO acquired by this start; nothing to release here.
                }
                CaptureStartResult.ScoUnavailable -> {
                    // No SCO acquired; nothing to release.
                }
                CaptureStartResult.Cancelled -> {
                    // Service already released SCO on this branch.
                }
                CaptureStartResult.RecordingFailed -> {
                    // Service already released SCO on this branch.
                }
                is CaptureStartResult.RecordingSilenced -> {
                    // Service already released SCO on this branch.
                }
                is CaptureStartResult.Started -> {
                    val session = result.session
                    val writer = JournalWavWriter(paths.captureFile, session.sampleRate)
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
            scope.launch { route.output.releaseRoute() }
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
        val fJob = framesJob
        activeSession = null
        activeWavWriter = null
        activeEntryPaths = null
        activeChannel = null
        framesJob = null

        scope.launch {
            // Preserve current behavior: a no-op play call for the non-telecom
            // path before releasing the route. Telecom's releaseRoute() already
            // does the right thing (await disconnect), so both paths now go
            // through route.output.releaseRoute().
            route.output.play(RecordedPcm(shortArrayOf(), session.sampleRate))

            withContext(Dispatchers.IO) {
                // Join the frames collector before finalizing so the
                // collector has fully unwound before the file is closed
                // (D6 layer 2). The thread-safe writer is the safety net.
                fJob?.cancelAndJoin()
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
            route.output.releaseRoute()
        }
    }


    private fun writeTerminalMetadata(
        paths: JournalEntryPaths.EntryPaths,
        channel: JournalChannel,
    ) {
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
    }
    private fun readStartedAt(store: JournalMetadataStore, file: File): String? =
        store.read(file)?.startedAt
}