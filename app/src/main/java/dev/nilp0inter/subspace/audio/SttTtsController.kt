package dev.nilp0inter.subspace.audio
import android.util.Log

import dev.nilp0inter.subspace.model.SttTtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SttTtsController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val captureService: CaptureService,
    private val source: CaptureSource,
    private val output: PcmOutput,
    private val transcriber: SttTranscriber,
    private val synthesizer: TtsSynthesizer,
    private val transcriptionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val synthesisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _status = MutableStateFlow<SttTtsStatus>(SttTtsStatus.Idle)
    val status: StateFlow<SttTtsStatus> = _status.asStateFlow()

    var enabled: Boolean = false
        private set

    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var completionJob: Job? = null
    private var activeSession: CaptureSession? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null
    private var transcribeJob: Job? = null

    fun setEnabled(value: Boolean) {
        logD("SubspaceSttTts", "setEnabled value=$value was=$enabled activeSession=${activeSession != null} setupJobActive=${setupJob?.isActive}")
        enabled = value
        if (!value && activeSession == null && setupJob?.isActive != true) {
            _status.value = SttTtsStatus.Idle
        }
    }

    fun onPttPressed() {
        onPttPressed(ResolvedAudioRoute(sco, output, source))
    }

    fun onPttPressed(route: ResolvedAudioRoute) {
        pttDown = true
        logD("SubspaceSttTts", "onPttPressed this=${System.identityHashCode(this)} enabled=$enabled setupJobActive=${setupJob?.isActive} activeSession=${activeSession != null}")
        if (!enabled) return
        if (setupJob?.isActive == true || activeSession != null) return

        transcribeJob?.cancel()
        transcribeJob = null
        retainedAfterMaxDuration = null
        setupJob = scope.launch { startSession(route) }
        logD("SubspaceSttTts", "setupJob launched")
    }

    fun onPttReleased(
        voiceStylePath: String = "",
        lang: String = "",
        totalSteps: Int = 0,
        speed: Float = 1.0f,
        scoRate: Int = 16_000,
    ) {
        onPttReleased(ResolvedAudioRoute(sco, output, source), voiceStylePath, lang, totalSteps, speed, scoRate)
    }

    fun onPttReleased(
        route: ResolvedAudioRoute,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        pttDown = false
        scope.launch { finishSessionIfNeeded(route, voiceStylePath, lang, totalSteps, speed, scoRate) }
    }

    fun cancelAndRelease() {
        setupJob?.cancel()
        completionJob?.cancel()
        completionJob = null
        transcribeJob?.cancel()
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        retainedAfterMaxDuration = null
        scope.launch { output.releaseRoute() }
        _status.value = SttTtsStatus.Idle
    }

    fun onInputStarted(session: ChannelAudioInputSession) {
        pttDown = true
        transcribeJob?.cancel()
        transcribeJob = null
        retainedAfterMaxDuration = null
        if (enabled) _status.value = SttTtsStatus.Recording
    }

    suspend fun onInputReleased(
        recording: RecordedPcm,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ): ChannelInputResult {
        pttDown = false
        retainedAfterMaxDuration = null
        if (recording.isEmpty) {
            _status.value = SttTtsStatus.EmptyAudio
            return ChannelInputResult.None
        }
        _status.value = SttTtsStatus.Transcribing
        val samples = SttAudio.toParakeetInput(recording)
        return when (val transcriptOutcome = withContext(transcriptionDispatcher) { transcriber.transcribe(samples) }) {
            is TranscriptionOutcome.Success -> {
                val transcript = transcriptOutcome.text
                if (transcript.isBlank()) {
                    _status.value = SttTtsStatus.EmptyTranscript
                    ChannelInputResult.None
                } else {
                    _status.value = SttTtsStatus.Transcript(transcript)
                    _status.value = SttTtsStatus.Synthesizing
                    val synthRequest = SynthesisRequest(
                        text = transcript,
                        voiceStylePath = voiceStylePath,
                        lang = lang,
                        totalSteps = totalSteps,
                        speed = speed,
                    )
                    when (val synthOutcome = withContext(synthesisDispatcher) { synthesizer.synthesize(synthRequest) }) {
                        is SynthesisOutcome.Success -> {
                            if (synthOutcome.samples.isEmpty()) {
                                _status.value = SttTtsStatus.Error("Synthesis produced no audio")
                                ChannelInputResult.None
                            } else {
                                _status.value = SttTtsStatus.Playing
                                val playback = TtsAudio.toScoPlayback(synthOutcome.samples, scoRate)
                                if (playback.isEmpty) ChannelInputResult.None else ChannelInputResult.Playback(playback)
                            }
                        }
                        is SynthesisOutcome.ModelNotReady -> {
                            _status.value = SttTtsStatus.Error("TTS model not ready")
                            ChannelInputResult.None
                        }
                        is SynthesisOutcome.Failure -> {
                            _status.value = SttTtsStatus.Error(synthOutcome.reason)
                            ChannelInputResult.None
                        }
                        SynthesisOutcome.EmptyText -> {
                            _status.value = SttTtsStatus.EmptyTranscript
                            ChannelInputResult.None
                        }
                    }
                }
            }
            is TranscriptionOutcome.Failure -> {
                _status.value = SttTtsStatus.Error("Transcription failed: ${transcriptOutcome.reason}")
                ChannelInputResult.None
            }
            TranscriptionOutcome.ModelNotReady -> {
                _status.value = SttTtsStatus.Error("STT model not ready")
                ChannelInputResult.None
            }
            TranscriptionOutcome.EmptyInput -> {
                _status.value = SttTtsStatus.EmptyAudio
                ChannelInputResult.None
            }
        }
    }

    fun onInputPlaybackCompleted() {
        _status.value = SttTtsStatus.Idle
    }

    fun onInputCancelled(reason: String? = null) {
        transcribeJob?.cancel()
        retainedAfterMaxDuration = null
        _status.value = if (reason == null) SttTtsStatus.Cancelled else SttTtsStatus.Error(reason)
    }

    fun onInputFailed(reason: String) {
        _status.value = SttTtsStatus.Error(reason)
    }

    private suspend fun startSession(route: ResolvedAudioRoute) {
        logD("SubspaceSttTts", "startSession entered, pttDown=$pttDown route source=${route.source.sourceId}")
        runCatching {
            _status.value = SttTtsStatus.WaitingForAudio
            val result = captureService.startSession(
                source = route.source,
                sco = route.sco,
                output = route.output,
                shouldProceed = { logD("SubspaceSttTts", "shouldProceed pttDown=$pttDown"); pttDown },
            )
            logD("SubspaceSttTts", "captureService.startSession result=$result")
            when (result) {
                CaptureStartResult.SessionActive -> {
                    _status.value = SttTtsStatus.Error("Capture session already active")
                }
                CaptureStartResult.ScoUnavailable -> {
                    _status.value = SttTtsStatus.Error("SCO unavailable")
                }
                CaptureStartResult.Cancelled -> {
                    // Service already released SCO on this branch.
                    _status.value = SttTtsStatus.Cancelled
                }
                CaptureStartResult.RecordingFailed -> {
                    // Service already released SCO on this branch.
                    _status.value = SttTtsStatus.Error("Recording failed")
                }
                is CaptureStartResult.Started -> {
                    activeSession = result.session
                    _status.value = SttTtsStatus.Recording
                    observeCompletion(result.session)
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            _status.value = SttTtsStatus.Error(error.message ?: "STT↔TTS session failed")
            val session = activeSession
            activeSession = null
            if (session != null) captureService.cancelSession(session)
        }
    }

    private fun observeCompletion(session: CaptureSession) {
        completionJob = scope.launch {
            val completion = session.completion.await()
            if (completion is CaptureCompletion.MaxDuration &&
                pttDown &&
                activeSession === session
            ) {
                retainedAfterMaxDuration = completion.recordedPcm
                _status.value = SttTtsStatus.MaxDurationReached
            }
        }
    }

    private suspend fun finishSessionIfNeeded(
        route: ResolvedAudioRoute,
        voiceStylePath: String,
        lang: String,
        totalSteps: Int,
        speed: Float,
        scoRate: Int,
    ) {
        completionJob?.cancel()
        completionJob = null

        val session = activeSession
        val recording = if (session != null) {
            activeSession = null
            session.stop()
        } else {
            retainedAfterMaxDuration ?: RecordedPcm(shortArrayOf(), DEFAULT_RATE)
        }
        retainedAfterMaxDuration = null

        if (recording.isEmpty) {
            if (session == null) {
                if (_status.value != SttTtsStatus.Idle && _status.value !is SttTtsStatus.Error) {
                    _status.value = SttTtsStatus.Cancelled
                }
                setupJob?.cancel()
                setupJob = null
                scope.launch { route.output.releaseRoute() }
            } else {
                if (enabled || _status.value != SttTtsStatus.Idle) cancelSession(route)
                _status.value = SttTtsStatus.EmptyAudio
            }
            return
        }

        _status.value = SttTtsStatus.Transcribing
        transcribeJob = scope.launch {
            try {
                val samples = SttAudio.toParakeetInput(recording)
                val transcriptOutcome = withContext(transcriptionDispatcher) { transcriber.transcribe(samples) }
                when (transcriptOutcome) {
                    is TranscriptionOutcome.Success -> {
                        val transcript = transcriptOutcome.text
                        if (transcript.isBlank()) {
                            _status.value = SttTtsStatus.EmptyTranscript
                            return@launch
                        }
                        _status.value = SttTtsStatus.Transcript(transcript)
                        _status.value = SttTtsStatus.Synthesizing
                        val synthRequest = SynthesisRequest(
                            text = transcript,
                            voiceStylePath = voiceStylePath,
                            lang = lang,
                            totalSteps = totalSteps,
                            speed = speed,
                        )
                        val synthOutcome = withContext(synthesisDispatcher) { synthesizer.synthesize(synthRequest) }
                        when (synthOutcome) {
                            is SynthesisOutcome.Success -> {
                                if (synthOutcome.samples.isEmpty()) {
                                    _status.value = SttTtsStatus.Error("Synthesis produced no audio")
                                } else {
                                    _status.value = SttTtsStatus.Playing
                                    val playback = TtsAudio.toScoPlayback(synthOutcome.samples, scoRate)
                                    if (!playback.isEmpty) route.output.play(playback)
                                    _status.value = SttTtsStatus.Idle
                                }
                            }
                            is SynthesisOutcome.ModelNotReady -> {
                                _status.value = SttTtsStatus.Error("TTS model not ready")
                            }
                            is SynthesisOutcome.Failure -> {
                                _status.value = SttTtsStatus.Error(synthOutcome.reason)
                            }
                            SynthesisOutcome.EmptyText -> {
                                _status.value = SttTtsStatus.EmptyTranscript
                            }
                        }
                    }
                    is TranscriptionOutcome.Failure -> {
                        _status.value = SttTtsStatus.Error("Transcription failed: ${transcriptOutcome.reason}")
                    }
                    TranscriptionOutcome.ModelNotReady -> {
                        _status.value = SttTtsStatus.Error("STT model not ready")
                    }
                    TranscriptionOutcome.EmptyInput -> {
                        _status.value = SttTtsStatus.EmptyAudio
                    }
                }
            } finally {
                route.output.releaseRoute()
            }
        }
    }

    private fun cancelSession(route: ResolvedAudioRoute) {
        completionJob?.cancel()
        completionJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch { captureService.cancelSession(session) }
        }
        retainedAfterMaxDuration = null
        scope.launch { route.output.releaseRoute() }
        _status.value = SttTtsStatus.Cancelled
    }

    private companion object {
        const val DEFAULT_RATE = 16_000
    }
}

private fun logD(tag: String, msg: String) {
    try {
        android.util.Log.d(tag, msg)
    } catch (e: Throwable) {
        println("[$tag] $msg")
    }
}