package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import dev.nilp0inter.subspace.audio.OggEncoder
import dev.nilp0inter.subspace.audio.ParakeetAssetExtractor
import dev.nilp0inter.subspace.audio.ParakeetJniTranscriber
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.SupertonicAssetExtractor
import dev.nilp0inter.subspace.audio.SupertonicJniSynthesizer
import dev.nilp0inter.subspace.audio.SystemAnnouncer
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.channel.KeyboardPttController
import dev.nilp0inter.subspace.model.KeyboardChannel
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardStatus
import io.sleepwalker.core.hid.LowLevelHidImpl
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.JournalPttController
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.SttStatus
import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TtsStatus
import dev.nilp0inter.subspace.model.TtsModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
internal class SpeechEngineController(
    private val service: PttForegroundService,
    private val scope: CoroutineScope,
    private val appState: () -> AppState,
    private val audioCoordinator: AudioSessionCoordinator,
    private val connectionManager: SubspaceConnectionManager
) : ChannelPttHandler {

    var sttController: SttController? = null
    var sttTranscriber: SttTranscriber? = null
    val sttReady = CompletableDeferred<SttTranscriber?>()
    var sttModelDir: java.io.File? = null
    var transcriptionService: TranscriptionService? = null
    var sttModelStatusJob: Job? = null
    
    var ttsController: TtsController? = null
    var ttsSynthesizer: TtsSynthesizer? = null
    var supertonicModelDir: java.io.File? = null
    var announcer: SystemAnnouncer? = null
    var ttsModelStatusJob: Job? = null
    
    var sttTtsController: SttTtsController? = null
    var journalPttController: JournalPttController? = null
    
    val sleepwalkerConnection = SleepwalkerBleConnection()
    var keyboardController: KeyboardPttController? = null

    fun onCreate() {
        val audioManager = service.getSystemService(AudioManager::class.java)

        scope.launch {
            sleepwalkerConnection.connectionState.collect { state ->
                service.updateMonitor { it.copy(keyboardConnectionState = state) }
                val currentKeyboard = appState().keyboard
                service.updateAppState {
                    it.copy(
                        keyboard = currentKeyboard.copy()
                    )
                }
                connectionManager.refreshReadiness()
            }
        }

        val initStartNanos = SystemClock.elapsedRealtimeNanos()
        initializeStt(audioManager)
        initializeTts(audioManager)
        android.util.Log.d(
            TAG,
            "onCreate off-main model init launched in " +
                "${(SystemClock.elapsedRealtimeNanos() - initStartNanos) / 1_000_000}ms",
        )
        initializeJournal(audioManager)
        
        updateActiveControllers()
    }

    fun onDestroy() {
        cancelAll()
        sttModelStatusJob?.cancel()
        ttsModelStatusJob?.cancel()
        sleepwalkerConnection.disconnect()
    }

    private fun initializeStt(audioManager: AudioManager) {
        scope.launch(Dispatchers.IO) {
            val transcriber = try {
                val nativeLibDir = service.applicationInfo.nativeLibraryDir
                val modelDir = ParakeetAssetExtractor.extract(service, PARAKEET_ASSET_VERSION)
                sttModelDir = modelDir
                ParakeetJniTranscriber(
                    nativeLibDir = nativeLibDir,
                    modelDir = modelDir.absolutePath,
                )
            } catch (err: Throwable) {
                android.util.Log.w(TAG, "STT transcriber unavailable: ${err.message}")
                null
            }
            sttReady.complete(transcriber)
            if (transcriber != null) {
                sttTranscriber = transcriber
                scope.launch {
                    val transcription = TranscriptionService(transcriber)
                    transcriptionService = transcription
                    sttController = SttController(
                        scope = scope,
                        sco = audioCoordinator.sco,
                        captureService = audioCoordinator.captureService,
                        source = audioCoordinator.voiceCommunicationSource,
                        output = audioCoordinator.pcmOutput,
                        transcriptionService = transcription,
                    )
                    sttModelStatusJob = scope.launch {
                        var lastStatus: SttModelStatus? = null
                        while (true) {
                            val status = transcriber.modelStatus
                            if (status != lastStatus) {
                                lastStatus = status
                                service.updateMonitor { it.copy(sttModelStatus = status) }
                            }
                            delay(STT_MODEL_POLL_MS)
                        }
                    }
                    scope.launch {
                        sttController?.status?.collect { status ->
                            if (audioCoordinator.isTerminalCarStatus(status)) audioCoordinator.forceReleaseActivePtt()
                            service.updateMonitor {
                                val newTranscript = (status as? SttStatus.Transcribed)?.text
                                    ?: it.sttTranscript
                                it.copy(sttStatus = status, sttTranscript = newTranscript)
                            }
                        }
                    }
                    keyboardController = KeyboardPttController(
                        scope = scope,
                        sco = audioCoordinator.sco,
                        captureService = audioCoordinator.captureService,
                        source = audioCoordinator.voiceCommunicationSource,
                        output = audioCoordinator.pcmOutput,
                        transcriptionService = transcription,
                        connection = sleepwalkerConnection,
                        hid = LowLevelHidImpl(),
                        hostProfileProvider = { appState().keyboard.hostProfile },
                    )
                    scope.launch {
                        keyboardController?.status?.collect { status ->
                            if (audioCoordinator.isTerminalCarStatus(status)) audioCoordinator.forceReleaseActivePtt()
                            service.updateMonitor { it.copy(keyboardStatus = status) }
                        }
                    }
                }
            }
        }
    }

    private fun initializeTts(audioManager: AudioManager) {
        scope.launch(Dispatchers.IO) {
            val synth = try {
                val nativeLibDir = service.applicationInfo.nativeLibraryDir
                val modelDir = SupertonicAssetExtractor.extract(service, SUPERTONIC_ASSET_VERSION)
                supertonicModelDir = modelDir
                SupertonicJniSynthesizer(
                    nativeLibDir = nativeLibDir,
                    modelDir = modelDir.absolutePath,
                )
            } catch (err: Throwable) {
                android.util.Log.w(TAG, "TTS synthesizer unavailable: ${err.message}")
                null
            }
            if (synth != null) {
                ttsSynthesizer = synth
                scope.launch {
                    announcer = SystemAnnouncer(synth)
                    val vocabulary = mapOf(
                        "sys.menu.channels" to "Channels",
                        "chan.${JournalChannel.ID}.name" to "Journal Channel",
                        "chan.${JournalChannel.ID}.selected" to "Journal Channel Selected",
                        "chan.${DebugChannel.ID}.name" to "Debug Channel",
                        "chan.${DebugChannel.ID}.selected" to "Debug Channel Selected",
                        "chan.${KeyboardChannel.ID}.name" to "Keyboard Channel",
                        "chan.${KeyboardChannel.ID}.selected" to "Keyboard Channel Selected"
                    )
                    val styleDir = supertonicModelDir ?: return@launch
                    val voiceStylePath = java.io.File(styleDir, "${appState().monitor.ttsVoiceStyle}.json").absolutePath
                    scope.launch {
                        announcer?.precompute(vocabulary, voiceStylePath, SCO_RATE)
                    }

                    ttsController = TtsController(
                        scope = scope,
                        sco = audioCoordinator.sco,
                        output = audioCoordinator.pcmOutput,
                        synthesizer = synth,
                    )
                    ttsModelStatusJob = scope.launch {
                        var lastStatus: TtsModelStatus? = null
                        while (true) {
                            val status = synth.modelStatus
                            if (status != lastStatus) {
                                lastStatus = status
                                service.updateMonitor { it.copy(ttsModelStatus = status) }
                            }
                            delay(TTS_MODEL_POLL_MS)
                        }
                    }
                    scope.launch {
                        ttsController?.status?.collect { status ->
                            service.updateMonitor { it.copy(ttsStatus = status) }
                        }
                    }
                    scope.launch {
                        val transcriber = sttReady.await()
                        if (transcriber != null) {
                            sttTtsController = SttTtsController(
                                scope = scope,
                                sco = audioCoordinator.sco,
                                captureService = audioCoordinator.captureService,
                                source = audioCoordinator.voiceCommunicationSource,
                                output = audioCoordinator.pcmOutput,
                                transcriber = transcriber,
                                synthesizer = synth,
                            )
                            scope.launch {
                                sttTtsController?.status?.collect { status ->
                                    if (audioCoordinator.isTerminalCarStatus(status)) audioCoordinator.forceReleaseActivePtt()
                                    val transcript = (status as? SttTtsStatus.Transcript)?.text
                                        ?: appState().monitor.sttTtsTranscript
                                    service.updateMonitor { it.copy(sttTtsStatus = status, sttTtsTranscript = transcript) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initializeJournal(audioManager: AudioManager) {
        scope.launch {
            val sttTranscriber = sttReady.await()
            val transcriber: PcmTranscriber = if (sttTranscriber != null) {
                TranscriptionService(sttTranscriber)
            } else {
                object : PcmTranscriber {
                    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
                        throw IllegalStateException("STT transcriber unavailable")
                    }
                }
            }
            val journalController = JournalController(
                scope = scope,
                encoder = OggEncoder(),
                transcriber = transcriber,
            )
            journalPttController = JournalPttController(
                scope = scope,
                sco = audioCoordinator.sco,
                output = audioCoordinator.pcmOutput,
                captureService = audioCoordinator.captureService,
                source = audioCoordinator.voiceCommunicationSource,
                journal = journalController,
                channelProvider = { appState().journal },
            )

            val journal = appState().journal
            val baseDir = journal.baseDirectory?.takeIf { it.isNotBlank() }
            if (baseDir != null) {
                journalController.runRecovery(java.io.File(baseDir))
            }

            var lastBaseDir: String? = baseDir
            service.appState.collect { state ->
                val currentDir = state.journal.baseDirectory?.takeIf { it.isNotBlank() }
                if (currentDir != null && currentDir != lastBaseDir) {
                    lastBaseDir = currentDir
                    journalController.runRecovery(java.io.File(currentDir))
                }
            }
        }
    }

    fun updateActiveControllers() {
        val state = appState()
        val activeChannelId = state.activeChannelId
        val mode = state.debugChannel.mode

        val isDebugActive = activeChannelId == DebugChannel.ID

        audioCoordinator.echo.setEnabled(isDebugActive && mode == DebugMode.ECHO)
        if (!(isDebugActive && mode == DebugMode.ECHO)) {
            audioCoordinator.echo.cancelAndRelease()
            service.updateMonitor { it.copy(echoStatus = EchoStatus.Idle) }
        }

        sttController?.setEnabled(isDebugActive && mode == DebugMode.STT)
        if (!(isDebugActive && mode == DebugMode.STT)) {
            sttController?.cancelAndRelease()
            service.updateMonitor { it.copy(sttStatus = SttStatus.Idle) }
        }

        ttsController?.setEnabled(isDebugActive && mode == DebugMode.TTS)
        if (!(isDebugActive && mode == DebugMode.TTS)) {
            ttsController?.cancelAndRelease()
            service.updateMonitor { it.copy(ttsStatus = TtsStatus.Idle) }
        }

        sttTtsController?.setEnabled(isDebugActive && mode == DebugMode.STT_TTS)
        if (!(isDebugActive && mode == DebugMode.STT_TTS)) {
            sttTtsController?.cancelAndRelease()
            service.updateMonitor { it.copy(sttTtsStatus = SttTtsStatus.Idle) }
        }
        if (activeChannelId != JournalChannel.ID) {
            journalPttController?.cancelAndRelease()
        }

        val isKeyboardActive = activeChannelId == KeyboardChannel.ID
        keyboardController?.setEnabled(isKeyboardActive)
        if (!isKeyboardActive) {
            keyboardController?.cancelAndRelease()
            service.updateMonitor { it.copy(keyboardStatus = KeyboardStatus.Idle) }
        }
    }

    fun setJournalDirectory(path: String) {
        val channel = service.channelRepository.loadJournal().copy(baseDirectory = path)
        saveJournal(channel)
    }

    fun setKeyboardHostProfile(profile: io.sleepwalker.core.keymap.HostProfile) {
        val currentKeyboard = appState().keyboard
        val newKeyboard = currentKeyboard.copy(hostProfile = profile)
        service.channelRepository.saveKeyboard(newKeyboard)
        service.updateAppState { it.copy(keyboard = newKeyboard) }
    }

    fun connectKeyboardBridge() {
        val adapter = connectionManager.bluetoothAdapter ?: return
        sleepwalkerConnection.connect(adapter, service)
    }

    fun disconnectKeyboardBridge() {
        sleepwalkerConnection.disconnect()
    }

    fun setJournalSaveVoice(enabled: Boolean) {
        val current = appState().journal
        if (!enabled && !current.saveText) return
        saveJournal(current.copy(saveVoice = enabled))
    }

    fun setJournalSaveText(enabled: Boolean) {
        val current = appState().journal
        if (!enabled && !current.saveVoice) return
        saveJournal(current.copy(saveText = enabled))
    }

    fun announce(key: String) {
        scope.launch {
            announcer?.announce(key, audioCoordinator.sco, audioCoordinator.pcmOutput)
        }
    }

    fun requestTtsSynthesis() {
        val tts = ttsController ?: return
        if (!tts.enabled) return
        val modelDir = supertonicModelDir ?: return
        val monitor = appState().monitor
        val voiceStylePath = voiceStyleFile(monitor.ttsVoiceStyle, modelDir).absolutePath
        tts.synthesize(
            text = monitor.ttsText,
            voiceStylePath = voiceStylePath,
            lang = monitor.ttsLang,
            totalSteps = monitor.ttsTotalSteps,
            speed = monitor.ttsSpeed,
            scoRate = SCO_RATE,
        )
    }

    private fun voiceStyleFile(style: String, modelDir: java.io.File): java.io.File =
        java.io.File(modelDir, "$style.json")

    private fun saveJournal(channel: JournalChannel) {
        service.channelRepository.saveJournal(channel)
        service.updateAppState { it.copy(journal = channel) }
    }

    override fun onPttPressed(channelId: String, route: ResolvedAudioRoute) {
        val appState = appState()
        when (channelId) {
            JournalChannel.ID -> journalPttController?.onPttPressed(route)
            KeyboardChannel.ID -> keyboardController?.onPttPressed(route)
            DebugChannel.ID -> {
                when (appState.debugChannel.mode) {
                    DebugMode.ECHO -> audioCoordinator.echo.onPttPressed(route)
                    DebugMode.STT -> sttController?.onPttPressed(route)
                    DebugMode.TTS -> {
                        val monitor = appState.monitor
                        val tts = ttsController
                        val modelDir = supertonicModelDir
                        if (tts != null && modelDir != null) {
                            tts.onPttPressed(
                                route = route,
                                text = monitor.ttsText,
                                voiceStylePath = voiceStyleFile(monitor.ttsVoiceStyle, modelDir).absolutePath,
                                lang = monitor.ttsLang,
                                totalSteps = monitor.ttsTotalSteps,
                                speed = monitor.ttsSpeed,
                                scoRate = SCO_RATE,
                            )
                        }
                    }
                    DebugMode.STT_TTS -> sttTtsController?.onPttPressed(route)
                }
            }
        }
    }

    override fun onPttReleased(channelId: String, route: ResolvedAudioRoute) {
        val appState = appState()
        when (channelId) {
            JournalChannel.ID -> journalPttController?.onPttReleased(route)
            KeyboardChannel.ID -> keyboardController?.onPttReleased(route)
            DebugChannel.ID -> {
                when (appState.debugChannel.mode) {
                    DebugMode.ECHO -> audioCoordinator.echo.onPttReleased(route)
                    DebugMode.STT -> sttController?.onPttReleased(route)
                    DebugMode.TTS -> ttsController?.onPttReleased()
                    DebugMode.STT_TTS -> {
                        val monitor = appState.monitor
                        val sttTts = sttTtsController
                        val modelDir = supertonicModelDir
                        if (sttTts != null && modelDir != null) {
                            sttTts.onPttReleased(
                                route,
                                voiceStylePath = voiceStyleFile(monitor.sttTtsVoiceStyle, modelDir).absolutePath,
                                lang = monitor.sttTtsLang,
                                totalSteps = monitor.sttTtsTotalSteps,
                                speed = monitor.sttTtsSpeed,
                                scoRate = SCO_RATE,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun cancelAndRelease(channelId: String) {
        when (channelId) {
            JournalChannel.ID -> journalPttController?.cancelAndRelease()
            KeyboardChannel.ID -> keyboardController?.cancelAndRelease()
            DebugChannel.ID -> {
                when (appState().debugChannel.mode) {
                    DebugMode.ECHO -> audioCoordinator.echo.cancelAndRelease()
                    DebugMode.STT -> sttController?.cancelAndRelease()
                    DebugMode.TTS -> ttsController?.cancelAndRelease()
                    DebugMode.STT_TTS -> sttTtsController?.cancelAndRelease()
                }
            }
        }
    }

    override fun cancelAll() {
        keyboardController?.cancelAndRelease()
        audioCoordinator.echo.cancelAndRelease("SPP disconnected")
        sttController?.cancelAndRelease()
        ttsController?.cancelAndRelease()
        sttTtsController?.cancelAndRelease()
        journalPttController?.cancelAndRelease()
    }

    companion object {
        private const val TAG = "SpeechEngineController"
        private const val PARAKEET_ASSET_VERSION = "int8-2026-06-23"
        private const val SUPERTONIC_ASSET_VERSION = "supertonic-3-2026-06-24"
        private const val STT_MODEL_POLL_MS = 500L
        private const val TTS_MODEL_POLL_MS = 500L
        private const val SCO_RATE = 16_000
    }
}
