package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.nilp0inter.subspace.MainActivity
import dev.nilp0inter.subspace.R
import dev.nilp0inter.subspace.audio.AndroidMicCaptureSource
import dev.nilp0inter.subspace.audio.AndroidPcmOutput
import dev.nilp0inter.subspace.audio.AndroidVoiceCommunicationCaptureSource
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.LocalPcmOutput
import dev.nilp0inter.subspace.audio.MediaResponsePlayer
import dev.nilp0inter.subspace.audio.OggEncoder
import dev.nilp0inter.subspace.audio.ModelDownloader
import dev.nilp0inter.subspace.audio.ParakeetJniTranscriber
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.ModelVerifier
import dev.nilp0inter.subspace.audio.SupertonicJniSynthesizer
import dev.nilp0inter.subspace.audio.SystemAnnouncer
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.TelecomCapturePcmOutput
import dev.nilp0inter.subspace.audio.TelecomCallScoRoute
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.audio.audioModeDebugString
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.audio.resolveLocalAudioRoute
import dev.nilp0inter.subspace.audio.resolveScoAudioRoute
import dev.nilp0inter.subspace.bluetooth.DeviceScanner
import dev.nilp0inter.subspace.bluetooth.SppClient
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.channel.KeyboardPttController
import dev.nilp0inter.subspace.model.KeyboardChannel
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardStatus
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.JsonKeymapDatabase
import io.sleepwalker.core.keymap.HostProfile
import dev.nilp0inter.subspace.channel.JournalController
import dev.nilp0inter.subspace.channel.JournalEntryDiscovery
import dev.nilp0inter.subspace.channel.JournalEntryPaths
import dev.nilp0inter.subspace.channel.JournalMetadataStore
import dev.nilp0inter.subspace.channel.JournalPttController
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelBrowseEntry
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.InputModeAvailability
import dev.nilp0inter.subspace.model.InputModeSelection
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.ChannelRepository
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.DevicePresence
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.HardwareMode
import dev.nilp0inter.subspace.model.HeadsetAudioState
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.PermissionState
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.model.RawButtonEvent
import dev.nilp0inter.subspace.model.SppState
import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.SttStatus
import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TtsStatus
import dev.nilp0inter.subspace.model.projectChannelBrowseEntries
import dev.nilp0inter.subspace.model.orderedChannelIds
import dev.nilp0inter.subspace.model.selectChannelByOffset
import dev.nilp0inter.subspace.protocol.ButtonParser
import dev.nilp0inter.subspace.protocol.ButtonStateMachine
import dev.nilp0inter.subspace.telecom.SubspacePhoneAccountRegistrar
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PttForegroundService : Service(), CarPttCommandListener, TelecomCarPttCoordinator.Listener, ChannelRouter {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    val isCapturing: StateFlow<Boolean> get() = captureService.isCapturing
    val level: StateFlow<Float> get() = captureService.level

    /**
     * Car-browse projection of the channel list. Derived purely from
     * [appState] via [projectChannelBrowseEntries]; the Android Auto Media
     * service collects this to populate `onLoadChildren` and drive
     * `notifyChildrenChanged` (see design D3).
     *
     * Pending counts are always 0 today (inbound backlog tracking is not yet
     * implemented; see design non-goal "Pending unheard backlog accuracy on the first
     * cut"). A future inbound-backlog tracker can supply the second argument
     * to [projectChannelBrowseEntries] without changing this flow's shape.
     */
    val channelBrowseEntries: Flow<List<ChannelBrowseEntry>>
        get() = _appState
            .map { projectChannelBrowseEntries(it) }
            .distinctUntilChanged()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var headsetProxy: BluetoothHeadset? = null
    private val headsetServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as? BluetoothHeadset
                serviceScope.launch { refreshReadiness() }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                serviceScope.launch { refreshReadiness() }
            }
        }
    }
    private lateinit var scanner: DeviceScanner
    private lateinit var channelRepository: ChannelRepository
    private lateinit var audioManager: AudioManager
    private lateinit var sco: ScoAudioController
    private lateinit var pcmOutput: AndroidPcmOutput
    private lateinit var telecomCaptureOutput: AndroidPcmOutput
    private lateinit var captureService: CaptureService
    private lateinit var voiceCommunicationSource: AndroidVoiceCommunicationCaptureSource
    private lateinit var echo: EchoController
    private var sttController: SttController? = null
    private var sttTranscriber: SttTranscriber? = null
    private val sttReady = CompletableDeferred<SttTranscriber?>()
    private var sttModelDir: java.io.File? = null
    private var transcriptionService: TranscriptionService? = null
    private var sttModelStatusJob: Job? = null
    private var ttsController: TtsController? = null
    private var ttsSynthesizer: TtsSynthesizer? = null
    private var supertonicModelDir: java.io.File? = null
    private var announcer: SystemAnnouncer? = null
    private var ttsModelStatusJob: Job? = null
    private var sttTtsController: SttTtsController? = null
    private var journalPttController: JournalPttController? = null
    lateinit var sleepwalkerConnection: SleepwalkerBleConnection
    private val keymapDatabase: JsonKeymapDatabase by lazy { JsonKeymapDatabase(resources) }
    private var keyboardProfilesCache: List<Pair<HostProfile, String>>? = null
    var keyboardController: KeyboardPttController? = null
    private val buttonStateMachine = ButtonStateMachine()

    private lateinit var localOutput: LocalPcmOutput
    private lateinit var micSource: AndroidMicCaptureSource
    private lateinit var telecomRegistrar: SubspacePhoneAccountRegistrar
    private lateinit var mediaResponsePlayer: MediaResponsePlayer
    private lateinit var carTelecomStarter: CarTelecomStarter
    private lateinit var pttDispatcher: PttDispatcher
    private lateinit var controllerRegistry: PttControllerRegistry
    private val inputModeController = InputModeController()
    private var idleTimerJob: Job? = null

    private lateinit var readinessProbe: ReadinessProbe
    private var targetDevice: BluetoothDevice? = null
    private var sppClient: SppClient? = null
    private var serialJob: Job? = null
    private var sppStateJob: Job? = null
    private val reconnectPolicy = ReconnectPolicy()
    private var reconnectJob: Job? = null
    private var readinessRefreshJob: Job? = null
    private var foreground = false


    @SuppressLint("MissingPermission")
    private fun logAudioRouteSnapshot(event: String) {
        Log.d(
            ROUTE_LOG_TAG,
            "SNAPSHOT event=$event mode=${inputModeController.mode} selectedBy=${inputModeController.selectedBy} " +
                "availability=${inputModeController.availability} audioMode=${audioManager.mode.audioModeDebugString()} " +
                "current=${audioManager.communicationDevice.routeDebugString()} " +
                "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",
        )
    }


    inner class LocalBinder : Binder() {
        fun service(): PttForegroundService = this@PttForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        scanner = DeviceScanner(applicationContext, bluetoothAdapter)
        readinessProbe = ReadinessProbe(this, scanner, bluetoothAdapter, { headsetProxy })
        bluetoothAdapter?.getProfileProxy(this, headsetServiceListener, BluetoothProfile.HEADSET)
        sleepwalkerConnection = SleepwalkerBleConnection()
        channelRepository = ChannelRepository(applicationContext)
        _appState.value = _appState.value.copy(
            journal = channelRepository.loadJournal(),
            debugChannel = channelRepository.loadDebugChannel(),
            keyboard = channelRepository.loadKeyboard({ sleepwalkerConnection.connectionState.value == KeyboardConnectionState.Connected }),
            activeChannelId = channelRepository.loadActiveChannelId(),
        )

        audioManager = getSystemService(AudioManager::class.java)
        sco = ScoAudioController(
            scope = serviceScope,
            audioManager = audioManager,
            rsmHfpConnected = ::isRsmHfpConnected,
            targetRsmName = ::targetRsmName,
            startTargetRsmHfpAudio = ::startTargetRsmHfpAudio,
            stopTargetRsmHfpAudio = ::stopTargetRsmHfpAudio,
            isTargetRsmHfpAudioConnected = ::isTargetRsmHfpAudioConnected,
        )
        pcmOutput = AndroidPcmOutput(audioManager, sco::selectedCommunicationDevice)
        telecomCaptureOutput = AndroidPcmOutput(audioManager)
        localOutput = LocalPcmOutput()
        micSource = AndroidMicCaptureSource()
        captureService = CaptureService(serviceScope)
        voiceCommunicationSource = AndroidVoiceCommunicationCaptureSource()
        telecomRegistrar = SubspacePhoneAccountRegistrar(this)
        telecomRegistrar.register()
        mediaResponsePlayer = MediaResponsePlayer(audioManager, localOutput)
        carTelecomStarter = CarTelecomStarter(
            context = this,
            serviceScope = serviceScope,
            sco = sco,
            audioManager = audioManager,
            headsetProxyProvider = { headsetProxy },
            targetRsm = ::targetRsm,
            inputModeController = inputModeController,
            telecomRegistrar = telecomRegistrar,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            publishInputMode = ::publishInputMode,
            cancelIdleTimer = ::cancelIdleTimer,
            startIdleTimer = ::startIdleTimer,
            isActivePttSession = { pttDispatcher.activePttSession != null },
            decidePttDispatch = { decidePttDispatch(_appState.value) },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
            updateCarMediaState = ::updateCarMediaState,
        )
        pttDispatcher = PttDispatcher(
            serviceScope = serviceScope,
            sco = sco,
            inputModeController = inputModeController,
            channelRouter = this,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            publishInputMode = ::publishInputMode,
            cancelIdleTimer = ::cancelIdleTimer,
            startIdleTimer = ::startIdleTimer,
            decidePttDispatch = ::decidePttDispatch,
            appStateProvider = { _appState.value },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
            updateCarMediaState = ::updateCarMediaState,
        )
        serviceScope.launch {
            sleepwalkerConnection.connectionState.collect { state ->
                updateMonitor { it.copy(keyboardConnectionState = state) }
                val currentKeyboard = _appState.value.keyboard
                _appState.value = _appState.value.copy(
                    keyboard = currentKeyboard.copy()
                )
                refreshReadiness()
            }
        }
        CarPttCommandBus.setListener(this)
        TelecomCarPttCoordinator.setListener(this)
        echo = EchoController(
            scope = serviceScope,
            sco = sco,
            captureService = captureService,
            source = voiceCommunicationSource,
            output = pcmOutput,
        )

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

        serviceScope.launch {
            sco.state.collect { state ->
                updateMonitor { it.copy(scoState = state) }
            }
        }
        serviceScope.launch {
            echo.status.collect { status ->
                if (isTerminalCarStatus(status)) pttDispatcher.forceReleaseActivePtt()
                updateMonitor { it.copy(echoStatus = status) }
            }
        }

        AndroidAutoPresenceBus.setListener { connected ->
            updateInputMode()
        }

        refreshReadiness()
        updateInputMode()
        controllerRegistry = PttControllerRegistry(
            echo = echo,
        )

        updateCarMediaState()
    }

    private fun initializeStt(audioManager: AudioManager) {
        serviceScope.launch(Dispatchers.IO) {
            val transcriber = try {
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val modelDir = ModelDownloader.ensure(this@PttForegroundService, ModelVerifier.PARAKEET_DIR)
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
                serviceScope.launch {
                    val transcription = TranscriptionService(transcriber)
                    transcriptionService = transcription
                    sttController = SttController(
                        scope = serviceScope,
                        sco = sco,
                        captureService = captureService,
                        source = voiceCommunicationSource,
                        output = pcmOutput,
                        transcriptionService = transcription,
                    )
                    Log.d(ROUTE_LOG_TAG, "STT_CONTROLLER_INIT sttController set")
                    sttModelStatusJob = serviceScope.launch {
                        var lastStatus: SttModelStatus? = null
                        while (true) {
                            val status = transcriber.modelStatus
                            if (status != lastStatus) {
                                lastStatus = status
                                updateMonitor { it.copy(sttModelStatus = status) }
                            }
                            kotlinx.coroutines.delay(STT_MODEL_POLL_MS)
                        }
                    }
                    serviceScope.launch {
                        sttController?.status?.collect { status ->
                            if (isTerminalCarStatus(status)) pttDispatcher.forceReleaseActivePtt()
                            updateMonitor {
                                val newTranscript = (status as? SttStatus.Transcribed)?.text
                                    ?: it.sttTranscript
                                it.copy(sttStatus = status, sttTranscript = newTranscript)
                            }
                        }
                    }
                    keyboardController = KeyboardPttController(
                        scope = serviceScope,
                        sco = sco,
                        captureService = captureService,
                        source = voiceCommunicationSource,
                        output = pcmOutput,
                        transcriptionService = transcription,
                        connection = sleepwalkerConnection,
                        hid = LowLevelHidImpl(),
                        keymapDatabase = keymapDatabase,
                        hostProfileProvider = { _appState.value.keyboard.hostProfile },
                    )
                    serviceScope.launch {
                        keyboardController?.status?.collect { status ->
                            if (isTerminalCarStatus(status)) pttDispatcher.forceReleaseActivePtt()
                            updateMonitor { it.copy(keyboardStatus = status) }
                        }
                    }
                    updateActiveControllers()
                }
            }
        }
    }

    private fun initializeTts(audioManager: AudioManager) {
        serviceScope.launch(Dispatchers.IO) {
            val synth = try {
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val modelDir = ModelDownloader.ensure(this@PttForegroundService, ModelVerifier.SUPERTONIC_DIR)
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
                serviceScope.launch {
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
                    val voiceStylePath = java.io.File(styleDir, "${_appState.value.monitor.ttsVoiceStyle}.json").absolutePath
                    serviceScope.launch {
                        announcer?.precompute(vocabulary, voiceStylePath, SCO_RATE)
                    }

                    ttsController = TtsController(
                        scope = serviceScope,
                        sco = sco,
                        output = pcmOutput,
                        synthesizer = synth,
                    )
                    ttsModelStatusJob = serviceScope.launch {
                        var lastStatus: dev.nilp0inter.subspace.model.TtsModelStatus? = null
                        while (true) {
                            val status = synth.modelStatus
                            if (status != lastStatus) {
                                lastStatus = status
                                updateMonitor { it.copy(ttsModelStatus = status) }
                            }
                            kotlinx.coroutines.delay(TTS_MODEL_POLL_MS)
                        }
                    }
                    serviceScope.launch {
                        ttsController?.status?.collect { status ->
                            updateMonitor { it.copy(ttsStatus = status) }
                        }
                    }
                    updateActiveControllers()
                    serviceScope.launch {
                        val transcriber = sttReady.await()
                        if (transcriber != null) {
                            sttTtsController = SttTtsController(
                                scope = serviceScope,
                                sco = sco,
                                captureService = captureService,
                                source = voiceCommunicationSource,
                                output = pcmOutput,
                                transcriber = transcriber,
                                synthesizer = synth,
                            )
                            serviceScope.launch {
                                sttTtsController?.status?.collect { status ->
                                    if (isTerminalCarStatus(status)) pttDispatcher.forceReleaseActivePtt()
                                    val transcript = (status as? SttTtsStatus.Transcript)?.text
                                        ?: _appState.value.monitor.sttTtsTranscript
                                    updateMonitor { it.copy(sttTtsStatus = status, sttTtsTranscript = transcript) }
                                }
                            }
                            updateActiveControllers()
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_MONITORING) {
            ensureForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pttDispatcher.forceReleaseActivePtt()
        keyboardController?.cancelAndRelease()
        sleepwalkerConnection.disconnect()
        CarPttCommandBus.setListener(null)
        AndroidAutoPresenceBus.setListener(null)
        TelecomCarPttCoordinator.setListener(null)
        TelecomCarPttCoordinator.forceAbort()
        idleTimerJob?.cancel()
        reconnectPolicy.clearMonitoring()
        reconnectJob?.cancel()
        stopReadinessRefreshLoop()
        serialJob?.cancel()
        sppStateJob?.cancel()
        sppClient?.disconnect()
        sttModelStatusJob?.cancel()
        ttsModelStatusJob?.cancel()
        echo.cancelAndRelease()
        sttController?.cancelAndRelease()
        ttsController?.cancelAndRelease()
        sttTtsController?.cancelAndRelease()
        journalPttController?.cancelAndRelease()
        serviceScope.cancel()
        stopForegroundIfNeeded()
        headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        headsetProxy = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun targetRsm(): BluetoothDevice? =
        runCatching { scanner.bondedTarget() }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun targetRsmName(): String? = targetRsm()?.name

    @SuppressLint("MissingPermission")
    private fun isRsmHfpConnected(): Boolean = readinessProbe.isRsmHfpConnected()

    @SuppressLint("MissingPermission")
    private fun startTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        val connectionState = runCatching { proxy.getConnectionState(rsm) }.getOrDefault(-1)
        Log.d(
            ROUTE_LOG_TAG,
            "RSM_HFP_START_REQUEST target='${rsm.name}' connectionState=$connectionState " +
                "audioBefore=${runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)} " +
                "current=${audioManager.communicationDevice.routeDebugString()} " +
                "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",
        )
        return runCatching { proxy.startVoiceRecognition(rsm) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun stopTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching { proxy.stopVoiceRecognition(rsm) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun isTargetRsmHfpAudioConnected(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)
    }



    private fun initializeJournal(audioManager: AudioManager) {
        serviceScope.launch {
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
                scope = serviceScope,
                encoder = OggEncoder(),
                transcriber = transcriber,
            )
            journalPttController = JournalPttController(
                scope = serviceScope,
                sco = sco,
                output = pcmOutput,
                captureService = captureService,
                source = voiceCommunicationSource,
                journal = journalController,
                channelProvider = { _appState.value.journal },
            )

            val journal = _appState.value.journal
            val baseDir = journal.baseDirectory?.takeIf { it.isNotBlank() }
            if (baseDir != null) {
                journalController.runRecovery(java.io.File(baseDir))
            }

            var lastBaseDir: String? = baseDir
            _appState.collect { state ->
                val currentDir = state.journal.baseDirectory?.takeIf { it.isNotBlank() }
                if (currentDir != null && currentDir != lastBaseDir) {
                    lastBaseDir = currentDir
                    journalController.runRecovery(java.io.File(currentDir))
                }
            }
        }
    }

    private fun updateActiveControllers() {
        val state = _appState.value
        val activeChannelId = state.activeChannelId
        val mode = state.debugChannel.mode

        val isDebugActive = activeChannelId == DebugChannel.ID

        echo.setEnabled(isDebugActive && mode == DebugMode.ECHO)
        if (!(isDebugActive && mode == DebugMode.ECHO)) {
            echo.cancelAndRelease()
            updateMonitor { it.copy(echoStatus = EchoStatus.Idle) }
        }

        Log.d(ROUTE_LOG_TAG, "UPDATE_CONTROLLERS isDebugActive=$isDebugActive mode=$mode sttController=${sttController != null} setting=${isDebugActive && mode == DebugMode.STT}")
        sttController?.setEnabled(isDebugActive && mode == DebugMode.STT)
        if (!(isDebugActive && mode == DebugMode.STT)) {
            sttController?.cancelAndRelease()
            updateMonitor { it.copy(sttStatus = SttStatus.Idle) }
        }

        ttsController?.setEnabled(isDebugActive && mode == DebugMode.TTS)
        if (!(isDebugActive && mode == DebugMode.TTS)) {
            ttsController?.cancelAndRelease()
            updateMonitor { it.copy(ttsStatus = TtsStatus.Idle) }
        }

        sttTtsController?.setEnabled(isDebugActive && mode == DebugMode.STT_TTS)
        if (!(isDebugActive && mode == DebugMode.STT_TTS)) {
            sttTtsController?.cancelAndRelease()
            updateMonitor { it.copy(sttTtsStatus = SttTtsStatus.Idle) }
        }
        if (activeChannelId != JournalChannel.ID) {
            journalPttController?.cancelAndRelease()
        }

        val isKeyboardActive = activeChannelId == KeyboardChannel.ID
        keyboardController?.setEnabled(isKeyboardActive)
        if (!isKeyboardActive) {
            keyboardController?.cancelAndRelease()
            updateMonitor { it.copy(keyboardStatus = KeyboardStatus.Idle) }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshReadiness() {
        val previous = _appState.value.connection
        val snapshot = readinessProbe.refresh(previous.devicePresence, targetDevice)
        if (snapshot.bondedDevice != null) targetDevice = snapshot.bondedDevice
        updateConnection {
            it.copy(
                permissions = snapshot.permissions,
                missingPermissions = snapshot.missingPermissions,
                bluetoothEnabled = snapshot.bluetoothEnabled,
                devicePresence = snapshot.devicePresence,
                headsetAudio = snapshot.headsetAudio,
            )
        }
        updateInputMode()
    }

    @SuppressLint("MissingPermission")
    fun scanForDevice() {
        serviceScope.launch {
            refreshReadiness()
            if (_appState.value.connection.permissions != PermissionState.Granted) return@launch
            if (!_appState.value.connection.bluetoothEnabled) return@launch

            updateConnection { it.copy(devicePresence = DevicePresence.Scanning) }
            val found = runCatching { scanner.scanForTarget() }.getOrNull()
            targetDevice = found
            updateConnection {
                it.copy(
                    devicePresence = when {
                        found == null -> DevicePresence.NotFound
                        found.bondState == BluetoothDevice.BOND_BONDED -> DevicePresence.Bonded
                        else -> DevicePresence.Found
                    },
                )
            }
            refreshReadiness()
        }
    }

    fun pairTarget() {
        serviceScope.launch {
            val device = targetDevice ?: runCatching { scanner.scanForTarget() }.getOrNull()
            if (device == null) {
                updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                return@launch
            }

            targetDevice = device
            updateConnection { it.copy(devicePresence = DevicePresence.Pairing) }
            val bonded = runCatching { scanner.createBondAndWait(device) }.getOrDefault(false)
            updateConnection {
                it.copy(devicePresence = if (bonded) DevicePresence.Bonded else DevicePresence.PairingFailed)
            }
            refreshReadiness()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectSerial() {
        val adapter = bluetoothAdapter ?: return
        if (serialJob?.isActive == true) return
        reconnectPolicy.startMonitoring()
        reconnectJob?.cancel()

        serviceScope.launch {
            refreshReadiness()
            if (_appState.value.connection.permissions != PermissionState.Granted) return@launch
            if (!_appState.value.connection.bluetoothEnabled) return@launch

            val device = resolveManualSerialTarget()
            if (device == null) {
                updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                return@launch
            }
            startSerialSession(adapter, device, automatic = false)
        }
    }

    fun disconnectSerial() {
        pttDispatcher.forceReleaseActivePtt()
        keyboardController?.cancelAndRelease()
        sleepwalkerConnection.disconnect()
        reconnectPolicy.clearMonitoring()
        reconnectJob?.cancel()
        stopReadinessRefreshLoop()
        serialJob?.cancel()
        sppStateJob?.cancel()
        sppClient?.disconnect()
        sppClient = null
        updateConnection { it.copy(spp = SppState.Disconnected) }
        echo.cancelAndRelease()
        sttController?.cancelAndRelease()
        ttsController?.cancelAndRelease()
        sttTtsController?.cancelAndRelease()
        journalPttController?.cancelAndRelease()
        stopForegroundIfNeeded()
        stopSelf()
        refreshReadiness()
    }

    fun setJournalDirectory(path: String) {
        val channel = channelRepository.loadJournal().copy(baseDirectory = path)
        saveJournal(channel)
    }

    fun setKeyboardHostProfile(profile: io.sleepwalker.core.keymap.HostProfile) {
        val currentKeyboard = _appState.value.keyboard
        val newKeyboard = currentKeyboard.copy(hostProfile = profile)
        channelRepository.saveKeyboard(newKeyboard)
        _appState.value = _appState.value.copy(keyboard = newKeyboard)
        refreshReadiness()
    }

    fun getKeymapProfiles(): List<Pair<HostProfile, String>> {
        keyboardProfilesCache?.let { return it }
        val profiles = keymapDatabase.profiles.sortedBy { it.hostOs + it.layout + (it.variant ?: "") }
        val result = profiles.map { profile ->
            val displayName = buildString {
                append(profile.layout)
                profile.variant?.let { append(" ($it)") }
                append(" [${profile.hostOs}]")
            }
            profile to displayName
        }
        keyboardProfilesCache = result
        return result
    }

    fun connectKeyboardBridge() {
        val adapter = bluetoothAdapter ?: return
        sleepwalkerConnection.connect(adapter, this)
    }

    fun disconnectKeyboardBridge() {
        sleepwalkerConnection.disconnect()
    }

    fun setJournalSaveVoice(enabled: Boolean) {
        val current = _appState.value.journal
        if (!enabled && !current.saveText) return
        saveJournal(current.copy(saveVoice = enabled))
    }

    fun setJournalSaveText(enabled: Boolean) {
        val current = _appState.value.journal
        if (!enabled && !current.saveVoice) return
        saveJournal(current.copy(saveText = enabled))
    }

    fun setActiveChannelId(id: String) {
        val current = _appState.value
        if (current.activeChannelId == id) return

        channelRepository.saveActiveChannelId(id)
        _appState.update { it.copy(activeChannelId = id) }
        updateActiveControllers()
        updateCarMediaState()
    }

    /**
     * Advance the active channel by [offset] positions in the stable channel
     * ordering (`Channel.orderIndex`), saturating at the bounds — no
     * wraparound (design D5 / spec `car-contextual-skip-controls` "Previous
     * saturates at the first channel rather than wrap"). Powered by
     * [selectChannelByOffset] so the phone hardware control mode and the car
     * steering wheel share the exact same selection path.
     */
    fun setActiveChannelOffset(offset: Int) {
        val orderedIds = orderedChannelIds(_appState.value)
        val newId = selectChannelByOffset(orderedIds, _appState.value.activeChannelId, offset)
            ?: return
        if (newId == _appState.value.activeChannelId) return
        channelRepository.saveActiveChannelId(newId)
        _appState.update { it.copy(activeChannelId = newId) }
        updateActiveControllers()
    }

    /**
     * **Future wiring**: skip the currently-playing inbound message on the
     * active channel and advance to the queued one (spec
     * `car-contextual-skip-controls` "Next skips the current inbound message
     * while Finalizing"). No inbound backlog tracking exists today
     * (`pending unheard message state` is not yet implemented), so
     * this method no-ops safely pending the message-backlog capability. When
     * that capability ships, this method should: mark the current inbound
     * message Heard, advance the active-channel inbox pointer, and (if no
     * queued message) fall back to `Ready`. The car [CarMediaStateBus] will
     * emit the resulting state and the now-playing card will reflect it.
     */
    fun skipCurrentMessage() {
    }

    /**
     * **Future wiring**: replay the last heard inbound message on the active
     * channel (spec `car-contextual-skip-controls` "Previous replays the last
     * heard message while Finalizing"). No `last-heard message state` exists
     * today; this method no-ops safely until that capability ships.
     */
    fun replayLastHeard() {
    }

    fun phonePttPressed(channelId: String) {
        Log.d(ROUTE_LOG_TAG, "PHONE_PTT_PRESSED channel=$channelId")
        logAudioRouteSnapshot("phone-ptt-pressed")
        setActiveChannelId(channelId)
        pttDispatcher.dispatchPttPressed(PttSource.Phone)
    }

    fun phonePttReleased(channelId: String) {
        pttDispatcher.dispatchPttReleased(PttSource.Phone)
    }

    fun setInputMode(mode: InputMode): Boolean = setInputMode(mode, InputModeSelection.User)

    fun setInputMode(mode: InputMode, by: InputModeSelection): Boolean {
        val changed = inputModeController.setInputMode(mode, by)
        if (changed) publishInputMode()
        return changed
    }

    private fun updateInputMode() {
        val readyForMonitor = _appState.value.readyForMonitor
        val aaConnected = AndroidAutoPresenceBus.isConnected()
        inputModeController.updateInputs(readyForMonitor, aaConnected)
        publishInputMode()
    }

    private fun publishInputMode() {
        _appState.value = _appState.value.copy(
            inputMode = inputModeController.mode,
            inputModeSelectedBy = inputModeController.selectedBy,
            inputModeAvailability = inputModeController.availability,
        )
        updateCarMediaState()
    }


    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            cleanupOnTheRoadSession()
        }
    }

    private fun cancelIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }

    private fun cleanupOnTheRoadSession() {
        updateCarMediaState()
    }

    override fun onCarPttStart() {
        carTelecomStarter.startTelecomCarPtt()
    }

    override fun onCarPttRelease() {
        TelecomCarPttCoordinator.forceAbort()
    }

    override fun onTelecomCaptureStart() {
        pttDispatcher.dispatchPttPressed(PttSource.CarTelecom)
        updateCarMediaState()
    }

    override fun onTelecomCaptureStop() {
        pttDispatcher.dispatchPttReleased(PttSource.CarTelecom)
        updateCarMediaState()
    }
    override fun onTelecomRouteTimeout() {
        carTelecomStarter.playCarErrorBeep()
        carTelecomStarter.notifyTelecomDisconnected()
        updateCarMediaState()
    }
    override fun onTelecomConnectionEnded() {
        carTelecomStarter.notifyTelecomDisconnected()
        startIdleTimer()
        updateCarMediaState()
    }

    fun setDebugChannelMode(mode: DebugMode) {
        saveDebugChannel(_appState.value.debugChannel.copy(mode = mode))
        updateActiveControllers()
    }

    private fun saveJournal(channel: JournalChannel) {
        channelRepository.saveJournal(channel)
        _appState.value = _appState.value.copy(journal = channel)
    }

    private fun saveDebugChannel(channel: DebugChannel) {
        channelRepository.saveDebugChannel(channel)
        _appState.value = _appState.value.copy(debugChannel = channel)
    }

    fun setTtsText(text: String) {
        updateMonitor { it.copy(ttsText = text) }
    }

    fun setTtsVoiceStyle(style: String) {
        updateMonitor { it.copy(ttsVoiceStyle = style) }
    }

    fun setTtsLang(lang: String) {
        updateMonitor { it.copy(ttsLang = lang) }
    }

    fun setTtsTotalSteps(steps: Int) {
        updateMonitor { it.copy(ttsTotalSteps = steps) }
    }

    fun setTtsSpeed(speed: Float) {
        updateMonitor { it.copy(ttsSpeed = speed) }
    }

    fun setSttTtsVoiceStyle(style: String) {
        updateMonitor { it.copy(sttTtsVoiceStyle = style) }
    }

    fun setSttTtsLang(lang: String) {
        updateMonitor { it.copy(sttTtsLang = lang) }
    }

    fun setSttTtsTotalSteps(steps: Int) {
        updateMonitor { it.copy(sttTtsTotalSteps = steps) }
    }

    fun setSttTtsSpeed(speed: Float) {
        updateMonitor { it.copy(sttTtsSpeed = speed) }
    }

    fun requestTtsSynthesis() {
        val tts = ttsController ?: return
        if (!tts.enabled) return
        val modelDir = supertonicModelDir ?: return
        val monitor = _appState.value.monitor
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

    private fun cycleActiveChannel(next: Boolean) {
        val offset = if (next) 1 else -1
        val previousId = _appState.value.activeChannelId
        setActiveChannelOffset(offset)
        val newId = _appState.value.activeChannelId
        if (newId != previousId) {
            serviceScope.launch {
                announcer?.announce("chan.$newId.name", sco, pcmOutput)
            }
        }
    }

    override fun onCarSetActiveChannel(id: String) {
        setActiveChannelId(id)
    }

    override fun onCarSetActiveChannelOffset(offset: Int) {
        setActiveChannelOffset(offset)
    }

    override fun onCarSkipMessage() {
        skipCurrentMessage()
    }

    override fun onCarReplayMessage() {
        replayLastHeard()
    }

    private fun handleRawButtonEvent(event: RawButtonEvent) {
        val previousMode = _appState.value.monitor.hardwareMode
        val snapshot = buttonStateMachine.apply(event, SystemClock.elapsedRealtime())
        updateMonitor {
            it.copy(
                hardwareMode = snapshot.hardwareMode,
                buttons = snapshot.buttons,
            )
        }

        when (event) {
            RawButtonEvent.GroupPressed -> {
                if (previousMode != HardwareMode.Control && snapshot.hardwareMode == HardwareMode.Control) {
                    serviceScope.launch {
                        announcer?.announce("sys.menu.channels", sco, pcmOutput)
                    }
                }
            }
            RawButtonEvent.VolumeUpClicked -> {
                if (snapshot.hardwareMode == HardwareMode.Control) {
                    cycleActiveChannel(next = true)
                }
                scheduleVolumeExpiry()
            }
            RawButtonEvent.VolumeDownClicked -> {
                if (snapshot.hardwareMode == HardwareMode.Control) {
                    cycleActiveChannel(next = false)
                }
                scheduleVolumeExpiry()
            }
            RawButtonEvent.PttPressed -> {
                if (previousMode == HardwareMode.Control) {
                    serviceScope.launch {
                        announcer?.announce("chan.${_appState.value.activeChannelId}.selected", sco, pcmOutput)
                    }
                } else {
                    pttDispatcher.dispatchPttPressed(PttSource.Rsm)
                }
            }
            RawButtonEvent.PttReleased -> pttDispatcher.dispatchPttReleased(PttSource.Rsm)
            RawButtonEvent.SosPressed -> {
                if (_appState.value.activeChannelId == KeyboardChannel.ID) {
                    keyboardController?.sendEnter()
                }
            }
            else -> Unit
        }
    }



    private fun resolvePttAudioRoute(mode: InputMode): ResolvedAudioRoute =
        dev.nilp0inter.subspace.service.resolvePttAudioRoute(
            mode = mode,
            sco = sco,
            telecomCaptureOutput = telecomCaptureOutput,
            mediaResponsePlayer = mediaResponsePlayer,
            voiceCommunicationSource = voiceCommunicationSource,
            localOutput = localOutput,
            micSource = micSource,
            pcmOutput = pcmOutput,
            awaitTelecomDisconnected = { withTimeoutOrNull(POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS) { carTelecomStarter.telecomDisconnected.await() } },
            releaseTelecomCaptureRoute = {
                dev.nilp0inter.subspace.service.releaseTelecomCaptureRoute(
                    audioManager, carTelecomStarter::stopPrimedCarHfp, ::logAudioRouteSnapshot,
                )
            },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
        )

    private fun releaseTelecomCaptureRoute() =
        dev.nilp0inter.subspace.service.releaseTelecomCaptureRoute(
            audioManager, carTelecomStarter::stopPrimedCarHfp, ::logAudioRouteSnapshot,
        )



    private fun updateCarMediaState() {
        val activeReady = decidePttDispatch(_appState.value) is PttDispatchDecision.Dispatch
        val onTheRoadAvailable = inputModeController.availability.onTheRoad
        val state = when {
            pttDispatcher.activePttSession != null -> CarMediaPttState.Recording
            idleTimerJob?.isActive == true -> CarMediaPttState.Finalizing
            activeReady && onTheRoadAvailable -> CarMediaPttState.Ready
            onTheRoadAvailable -> CarMediaPttState.Ready
            else -> CarMediaPttState.NotReady
        }
        CarMediaStateBus.update(state)
    }

    private fun isTerminalCarStatus(status: EchoStatus): Boolean = pttDispatcher.isTerminalCarSource() &&
        (status is EchoStatus.Error || status == EchoStatus.MaxDurationReached)

    private fun isTerminalCarStatus(status: SttStatus): Boolean = pttDispatcher.isTerminalCarSource() &&
        (status is SttStatus.Error || status == SttStatus.MaxDurationReached)

    private fun isTerminalCarStatus(status: SttTtsStatus): Boolean = pttDispatcher.isTerminalCarSource() &&
        (status is SttTtsStatus.Error || status == SttTtsStatus.MaxDurationReached)

    private fun isTerminalCarStatus(status: KeyboardStatus): Boolean = pttDispatcher.isTerminalCarSource() &&
        (status is KeyboardStatus.Error || status == KeyboardStatus.MaxDurationReached)


    @SuppressLint("MissingPermission")
    private fun resolveManualSerialTarget(): BluetoothDevice? {
        val device = findBondedTarget() ?: targetDevice
        if (device?.bondState != BluetoothDevice.BOND_BONDED) return null
        targetDevice = device
        return device
    }

    @SuppressLint("MissingPermission")
    private fun findBondedTarget(): BluetoothDevice? {
        if (!RequiredPermissions.hasBluetoothConnect(this)) return null
        if (bluetoothAdapter?.isEnabled != true) return null
        return runCatching { scanner.bondedTarget() }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun reconnectPrerequisites(
        bondedTarget: BluetoothDevice? = findBondedTarget(),
    ): ReconnectPrerequisites = ReconnectPrerequisites(
        permissionsGranted = _appState.value.connection.permissions == PermissionState.Granted,
        bluetoothEnabled = _appState.value.connection.bluetoothEnabled,
        bondedTargetAvailable = bondedTarget?.bondState == BluetoothDevice.BOND_BONDED,
    )

    private fun startSerialSession(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        automatic: Boolean,
    ): Boolean {
        if (serialJob?.isActive == true) return false

        ensureForeground()
        sppStateJob?.cancel()
        sppClient?.disconnect()

        var connected = false
        val client = SppClient(adapter, ButtonParser())
        sppClient = client

        sppStateJob = serviceScope.launch {
            client.state.collect { state ->
                if (state == SppState.Connected) {
                    connected = true
                    if (automatic) {
                        reconnectPolicy.finishAttempt(
                            success = true,
                            nowMillis = SystemClock.elapsedRealtime(),
                            prerequisites = reconnectPrerequisites(device),
                        )
                    }
                }
                updateConnection {
                    it.copy(
                        spp = state,
                        sppError = if (state == SppState.Failed) "Serial connection failed" else null,
                    )
                }
                refreshReadiness()
            }
        }

        serialJob = serviceScope.launch {
            client.events(device).collect { event -> handleRawButtonEvent(event) }
            handleSerialSessionEnded(automatic = automatic, connected = connected)
        }
        return true
    }

    private fun handleSerialSessionEnded(automatic: Boolean, connected: Boolean) {
        pttDispatcher.forceReleaseActivePtt()
        keyboardController?.cancelAndRelease()
        echo.cancelAndRelease("SPP disconnected")
        sttController?.cancelAndRelease()
        ttsController?.cancelAndRelease()
        sttTtsController?.cancelAndRelease()
        journalPttController?.cancelAndRelease()
        refreshReadiness()

        if (!reconnectPolicy.monitoringRequested) {
            stopForegroundIfNeeded()
            stopSelf()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val prerequisites = reconnectPrerequisites()
        val decision = if (automatic && !connected) {
            reconnectPolicy.finishAttempt(success = false, nowMillis = now, prerequisites = prerequisites)
        } else {
            reconnectPolicy.cancelAttempt()
            reconnectPolicy.scheduleAfterUnexpectedLoss(nowMillis = now, prerequisites = prerequisites)
        }
        handleReconnectDecision(decision)
    }

    private fun handleReconnectDecision(decision: ReconnectDecision) {
        when (decision) {
            ReconnectDecision.AlreadyInProgress,
            ReconnectDecision.NoAction,
            ReconnectDecision.StartAttempt,
            -> Unit

            is ReconnectDecision.Schedule -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Wait -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Blocked -> {
                if (decision.reason == ReconnectBlockReason.TargetUnavailable) {
                    updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                }
                refreshReadiness()
                stopForegroundIfNeeded()
                stopSelf()
            }
        }
    }

    private fun scheduleReconnectAt(attemptAtMillis: Long) {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val waitMs = (attemptAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs)
            beginAutomaticReconnectAttempt()
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginAutomaticReconnectAttempt() {
        refreshReadiness()
        val adapter = bluetoothAdapter ?: return
        val device = findBondedTarget()
        if (device != null) targetDevice = device

        when (val decision = reconnectPolicy.beginAttempt(SystemClock.elapsedRealtime(), reconnectPrerequisites(device))) {
            ReconnectDecision.StartAttempt -> {
                if (device == null) {
                    handleReconnectDecision(ReconnectDecision.Blocked(ReconnectBlockReason.TargetUnavailable))
                    return
                }
                if (!startSerialSession(adapter, device, automatic = true)) {
                    reconnectPolicy.cancelAttempt()
                }
            }

            ReconnectDecision.AlreadyInProgress,
            ReconnectDecision.NoAction,
            -> Unit

            is ReconnectDecision.Schedule -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Wait -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Blocked -> handleReconnectDecision(decision)
        }
    }

    private fun scheduleVolumeExpiry() {
        serviceScope.launch {
            delay(300)
            val snapshot = buttonStateMachine.expireClicks(SystemClock.elapsedRealtime())
            updateMonitor {
                it.copy(
                    hardwareMode = snapshot.hardwareMode,
                    buttons = snapshot.buttons,
                )
            }
        }
    }

    private fun updateConnection(transform: (ConnectionState) -> ConnectionState) {
        _appState.value = _appState.value.copy(connection = transform(_appState.value.connection))
        updateInputMode()
        updateCarMediaState()
        syncReadinessRefreshLoop()
    }

    private fun updateMonitor(transform: (MonitorState) -> MonitorState) {
        _appState.value = _appState.value.copy(monitor = transform(_appState.value.monitor))
    }

    private fun ensureForeground() {
        if (foreground) return
        createNotificationChannel()
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
            syncReadinessRefreshLoop()
        }.onFailure {
            foreground = false
            stopSelf()
        }
    }

    private fun stopForegroundIfNeeded() {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foreground = false
        syncReadinessRefreshLoop()
    }

    private fun syncReadinessRefreshLoop() {
        when (
            ReadinessRefreshLoopPolicy.decide(
                refreshAllowed = foreground && reconnectPolicy.monitoringRequested,
                readyForMonitor = _appState.value.readyForMonitor,
                refreshLoopActive = readinessRefreshJob?.isActive == true,
            )
        ) {
            ReadinessRefreshLoopDecision.KeepCurrentLoop -> Unit
            ReadinessRefreshLoopDecision.StartRefreshLoop -> startReadinessRefreshLoop()
            ReadinessRefreshLoopDecision.StopRefreshLoop -> stopReadinessRefreshLoop()
        }
    }

    private fun startReadinessRefreshLoop() {
        if (readinessRefreshJob?.isActive == true) return
        readinessRefreshJob = serviceScope.launch {
            while (true) {
                delay(READINESS_REFRESH_INTERVAL_MS)
                if (!foreground || _appState.value.readyForMonitor) {
                    syncReadinessRefreshLoop()
                    return@launch
                }
                refreshReadiness()
            }
        }
    }

    private fun stopReadinessRefreshLoop() {
        readinessRefreshJob?.cancel()
        readinessRefreshJob = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_subspace)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }


    // -- ChannelRouter implementation --------------------------------------------------

    override fun onPttPressed(channelId: String, route: ResolvedAudioRoute) {
        Log.d(ROUTE_LOG_TAG, "CHANNEL_PTT_PRESSED channel=$channelId mode=${_appState.value.debugChannel.mode} " +
            "stt=${sttController != null}(e=${sttController?.enabled}) " +
            "sttTts=${sttTtsController != null}(e=${sttTtsController?.enabled}) " +
            "tts=${ttsController != null}(e=${ttsController?.enabled})")
        when (channelId) {
            JournalChannel.ID -> journalPttController?.onPttPressed(route)
            KeyboardChannel.ID -> keyboardController?.onPttPressed(route)
            DebugChannel.ID -> {
                when (_appState.value.debugChannel.mode) {
                    DebugMode.ECHO -> echo.onPttPressed(route)
                    DebugMode.STT -> sttController?.onPttPressed(route)
                    DebugMode.TTS -> {
                        val monitor = _appState.value.monitor
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
        when (channelId) {
            JournalChannel.ID -> journalPttController?.onPttReleased(route)
            KeyboardChannel.ID -> keyboardController?.onPttReleased(route)
            DebugChannel.ID -> {
                when (_appState.value.debugChannel.mode) {
                    DebugMode.ECHO -> echo.onPttReleased(route)
                    DebugMode.STT -> sttController?.onPttReleased(route)
                    DebugMode.TTS -> ttsController?.onPttReleased()
                    DebugMode.STT_TTS -> {
                        val monitor = _appState.value.monitor
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
                when (_appState.value.debugChannel.mode) {
                    DebugMode.ECHO -> echo.cancelAndRelease()
                    DebugMode.STT -> sttController?.cancelAndRelease()
                    DebugMode.TTS -> ttsController?.cancelAndRelease()
                    DebugMode.STT_TTS -> sttTtsController?.cancelAndRelease()
                }
            }
        }
    }
    companion object {
        const val ACTION_START_MONITORING = "dev.nilp0inter.subspace.START_MONITORING"

        const val NOTIFICATION_CHANNEL_ID = "subspace_device_link"
        const val NOTIFICATION_ID = 41

        private const val TAG = "SubspacePttService"
        private const val STT_MODEL_POLL_MS = 500L
        private const val TTS_MODEL_POLL_MS = 500L
        private const val READINESS_REFRESH_INTERVAL_MS = 5_000L
        private const val SCO_RATE = 16_000
        private const val POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS = 3_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}
