package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.nilp0inter.subspace.MainActivity
import dev.nilp0inter.subspace.R
import dev.nilp0inter.subspace.audio.AndroidMicCaptureSource
import dev.nilp0inter.subspace.audio.AndroidPcmOutput
import dev.nilp0inter.subspace.audio.AndroidVoiceCommunicationCaptureSource
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.LocalPcmOutput
import dev.nilp0inter.subspace.audio.MediaResponsePlayer
import dev.nilp0inter.subspace.audio.NoopScoRoute
import dev.nilp0inter.subspace.audio.OggEncoder
import dev.nilp0inter.subspace.audio.ParakeetAssetExtractor
import dev.nilp0inter.subspace.audio.ParakeetJniTranscriber
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.SupertonicAssetExtractor
import dev.nilp0inter.subspace.audio.SupertonicJniSynthesizer
import dev.nilp0inter.subspace.audio.SystemAnnouncer
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.TelecomCapturePcmOutput
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.audio.resolveAudioRoute
import dev.nilp0inter.subspace.bluetooth.DeviceScanner
import dev.nilp0inter.subspace.bluetooth.SppClient
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

class PttForegroundService : Service(), CarPttCommandListener, TelecomCarPttCoordinator.Listener {
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
    private lateinit var scanner: DeviceScanner
    private lateinit var channelRepository: ChannelRepository
    private lateinit var sco: ScoAudioController
    private lateinit var pcmOutput: AndroidPcmOutput
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
    private val buttonStateMachine = ButtonStateMachine()

    private lateinit var localOutput: LocalPcmOutput
    private lateinit var micSource: AndroidMicCaptureSource
    private lateinit var telecomRegistrar: SubspacePhoneAccountRegistrar
    private lateinit var mediaResponsePlayer: MediaResponsePlayer
    private var telecomDisconnected = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var activePttSession: PttSession? = null
    private val inputModeController = InputModeController()
    private var idleTimerJob: Job? = null

    private var targetDevice: BluetoothDevice? = null
    private var sppClient: SppClient? = null
    private var serialJob: Job? = null
    private var sppStateJob: Job? = null
    private val reconnectPolicy = ReconnectPolicy()
    private var reconnectJob: Job? = null
    private var foreground = false

    private data class PttSession(
        val source: PttSource,
        val channelId: String,
        val route: ResolvedAudioRoute,
    )

    inner class LocalBinder : Binder() {
        fun service(): PttForegroundService = this@PttForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        scanner = DeviceScanner(applicationContext, bluetoothAdapter)
        channelRepository = ChannelRepository(applicationContext)
        _appState.value = _appState.value.copy(
            journal = channelRepository.loadJournal(),
            debugChannel = channelRepository.loadDebugChannel(),
            activeChannelId = channelRepository.loadActiveChannelId(),
        )

        val audioManager = getSystemService(AudioManager::class.java)
        sco = ScoAudioController(serviceScope, audioManager)
        pcmOutput = AndroidPcmOutput(audioManager)
        localOutput = LocalPcmOutput()
        micSource = AndroidMicCaptureSource()
        captureService = CaptureService(serviceScope)
        voiceCommunicationSource = AndroidVoiceCommunicationCaptureSource()
        telecomRegistrar = SubspacePhoneAccountRegistrar(this)
        telecomRegistrar.register()
        mediaResponsePlayer = MediaResponsePlayer(audioManager, localOutput)
        CarPttCommandBus.setListener(this)
        TelecomCarPttCoordinator.setListener(this)
        echo = EchoController(
            scope = serviceScope,
            sco = sco,
            captureService = captureService,
            source = voiceCommunicationSource,
            output = AndroidPcmOutput(audioManager),
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
                if (isTerminalCarStatus(status)) forceReleaseActivePtt()
                updateMonitor { it.copy(echoStatus = status) }
            }
        }

        AndroidAutoPresenceBus.setListener { connected ->
            updateInputMode()
        }

        refreshReadiness()
        updateInputMode()
        updateCarMediaState()
    }

    private fun initializeStt(audioManager: AudioManager) {
        serviceScope.launch(Dispatchers.IO) {
            val transcriber = try {
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val modelDir = ParakeetAssetExtractor.extract(this@PttForegroundService, PARAKEET_ASSET_VERSION)
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
                        output = AndroidPcmOutput(audioManager),
                        transcriptionService = transcription,
                    )
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
                            if (isTerminalCarStatus(status)) forceReleaseActivePtt()
                            updateMonitor {
                                val newTranscript = (status as? SttStatus.Transcribed)?.text
                                    ?: it.sttTranscript
                                it.copy(sttStatus = status, sttTranscript = newTranscript)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initializeTts(audioManager: AudioManager) {
        serviceScope.launch(Dispatchers.IO) {
            val synth = try {
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val modelDir = SupertonicAssetExtractor.extract(this@PttForegroundService, SUPERTONIC_ASSET_VERSION)
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
                        "chan.${DebugChannel.ID}.selected" to "Debug Channel Selected"
                    )
                    val styleDir = supertonicModelDir ?: return@launch
                    val voiceStylePath = java.io.File(styleDir, "${_appState.value.monitor.ttsVoiceStyle}.json").absolutePath
                    serviceScope.launch {
                        announcer?.precompute(vocabulary, voiceStylePath, SCO_RATE)
                    }

                    ttsController = TtsController(
                        scope = serviceScope,
                        sco = sco,
                        output = AndroidPcmOutput(audioManager),
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
                    serviceScope.launch {
                        val transcriber = sttReady.await()
                        if (transcriber != null) {
                            sttTtsController = SttTtsController(
                                scope = serviceScope,
                                sco = sco,
                                captureService = captureService,
                                source = voiceCommunicationSource,
                                output = AndroidPcmOutput(audioManager),
                                transcriber = transcriber,
                                synthesizer = synth,
                            )
                            serviceScope.launch {
                                sttTtsController?.status?.collect { status ->
                                    if (isTerminalCarStatus(status)) forceReleaseActivePtt()
                                    val transcript = (status as? SttTtsStatus.Transcript)?.text
                                        ?: _appState.value.monitor.sttTtsTranscript
                                    updateMonitor { it.copy(sttTtsStatus = status, sttTtsTranscript = transcript) }
                                }
                            }
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
        forceReleaseActivePtt()
        CarPttCommandBus.setListener(null)
        AndroidAutoPresenceBus.setListener(null)
        TelecomCarPttCoordinator.setListener(null)
        TelecomCarPttCoordinator.forceAbort()
        idleTimerJob?.cancel()
        reconnectPolicy.clearMonitoring()
        reconnectJob?.cancel()
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
        super.onDestroy()
    }

    private fun initializeJournal(audioManager: AudioManager) {
        val transcriber = transcriptionService ?: object : PcmTranscriber {
            override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
                throw IllegalStateException("STT transcriber unavailable")
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
            output = AndroidPcmOutput(audioManager),
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

        serviceScope.launch {
            var lastBaseDir: String? = null
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
    }

    @SuppressLint("MissingPermission")
    fun refreshReadiness() {
        val missing = RequiredPermissions.missing(this)
        val permissions = if (missing.isEmpty()) PermissionState.Granted else PermissionState.Missing
        val canInspectBluetooth = RequiredPermissions.hasBluetoothConnect(this)
        val bluetoothEnabled = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

        val bonded = if (canInspectBluetooth && bluetoothEnabled) {
            runCatching { scanner.bondedTarget() }.getOrNull()
        } else {
            null
        }
        if (bonded != null) targetDevice = bonded

        val previousConnection = _appState.value.connection
        val devicePresence = when {
            bonded != null -> DevicePresence.Bonded
            targetDevice?.bondState == BluetoothDevice.BOND_BONDED -> DevicePresence.Bonded
            previousConnection.devicePresence in setOf(
                DevicePresence.Scanning,
                DevicePresence.Found,
                DevicePresence.Pairing,
                DevicePresence.PairingFailed,
            ) -> previousConnection.devicePresence
            else -> DevicePresence.NotFound
        }

        val headsetAudio = if (permissions == PermissionState.Granted && bluetoothEnabled) {
            val available = runCatching { sco.hasAvailableScoDevice() }.getOrDefault(false)
            if (available) HeadsetAudioState.Available else HeadsetAudioState.Unavailable
        } else {
            HeadsetAudioState.Unavailable
        }

        updateConnection {
            it.copy(
                permissions = permissions,
                missingPermissions = missing,
                bluetoothEnabled = bluetoothEnabled,
                devicePresence = devicePresence,
                headsetAudio = headsetAudio,
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
        forceReleaseActivePtt()
        reconnectPolicy.clearMonitoring()
        reconnectJob?.cancel()
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
        setActiveChannelId(channelId)
        dispatchPttPressed(PttSource.Phone)
    }

    fun phonePttReleased(channelId: String) {
        dispatchPttReleased(PttSource.Phone)
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

    private fun forceReleaseActivePtt() {
        val session = activePttSession ?: return
        activePttSession = null
        cancelIdleTimer()
        when (session.channelId) {
            JournalChannel.ID -> journalPttController?.cancelAndRelease()
            DebugChannel.ID -> {
                when (_appState.value.debugChannel.mode) {
                    DebugMode.ECHO -> echo.cancelAndRelease()
                    DebugMode.STT -> sttController?.cancelAndRelease()
                    DebugMode.TTS -> ttsController?.cancelAndRelease()
                    DebugMode.STT_TTS -> sttTtsController?.cancelAndRelease()
                }
            }
        }
        TelecomCarPttCoordinator.forceAbort()
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
        startTelecomCarPtt()
    }

    override fun onCarPttRelease() {
        TelecomCarPttCoordinator.forceAbort()
    }

    override fun onTelecomCaptureStart() {
        dispatchPttPressed(PttSource.CarTelecom)
        updateCarMediaState()
    }

    override fun onTelecomCaptureStop() {
        dispatchPttReleased(PttSource.CarTelecom)
        updateCarMediaState()
    }

    override fun onTelecomRouteTimeout() {
        playCarErrorBeep()
        updateCarMediaState()
    }

    override fun onTelecomConnectionEnded() {
        if (!telecomDisconnected.isCompleted) telecomDisconnected.complete(Unit)
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

    private fun startTelecomCarPtt() {
        if (!telecomDisconnected.isCompleted) return
        if (activePttSession != null) return
        if (!inputModeController.autoTransitionFor(PttSource.CarTelecom)) {
            playCarErrorBeep()
            return
        }
        publishInputMode()
        cancelIdleTimer()
        val decision = decidePttDispatch(_appState.value) ?: return
        if (decision is PttDispatchDecision.ErrorBeep) {
            playCarErrorBeep()
            return
        }
        telecomRegistrar.register()
        if (!telecomRegistrar.isEnabled()) {
            startActivity(telecomRegistrar.setupIntent())
            return
        }

        val telecom = getSystemService(android.telecom.TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, telecomRegistrar.handle)
        }
        runCatching {
            telecomDisconnected = CompletableDeferred()
            telecom.placeCall(telecomRegistrar.callAddress(), extras)
        }.onFailure {
            if (!telecomDisconnected.isCompleted) telecomDisconnected.complete(Unit)
            playCarErrorBeep()
        }
    }

    private fun playCarErrorBeep() {
        val route = resolveAudioRoute(
            scoRoute = sco,
            scoOutput = pcmOutput,
            scoSource = voiceCommunicationSource,
            localOutput = localOutput,
            localSource = micSource,
        )
        serviceScope.launch {
            route.sco.acquire()
            route.output.playErrorBeep(route.sco.coldStart)
            route.sco.release()
        }
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
                    dispatchPttPressed()
                }
            }
            RawButtonEvent.PttReleased -> dispatchPttReleased()
            else -> Unit
        }
    }

    private fun dispatchPttPressed() {
        dispatchPttPressed(PttSource.Rsm)
    }

    private fun dispatchPttPressed(source: PttSource): Boolean {
        if (activePttSession != null) return false

        if (!inputModeController.autoTransitionFor(source)) {
            val route = resolvePttAudioRoute(inputModeController.mode)
            serviceScope.launch {
                route.sco.acquire()
                route.output.playErrorBeep(route.sco.coldStart)
                route.sco.release()
            }
            return false
        }
        publishInputMode()
        cancelIdleTimer()

        val appState = _appState.value
        val decision = decidePttDispatch(appState) ?: return false
        val activeChannelId = decision.channelId

        val route = resolvePttAudioRoute(inputModeController.mode)

        if (decision is PttDispatchDecision.ErrorBeep) {
            serviceScope.launch {
                route.sco.acquire()
                route.output.playErrorBeep(route.sco.coldStart)
                route.sco.release()
            }
            return false
        }

        activePttSession = PttSession(
            source = source,
            channelId = activeChannelId,
            route = route,
        )

        when (activeChannelId) {
            JournalChannel.ID -> journalPttController?.onPttPressed(route)
            DebugChannel.ID -> {
                when (appState.debugChannel.mode) {
                    DebugMode.ECHO -> echo.onPttPressed(route)
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
        updateCarMediaState()
        return true
    }

    private fun resolvePttAudioRoute(mode: InputMode): ResolvedAudioRoute {
        return when (mode) {
            InputMode.OnTheRoad -> ResolvedAudioRoute(
                sco = sco,
                output = TelecomCapturePcmOutput(
                    captureOutput = pcmOutput,
                    mediaResponsePlayer = mediaResponsePlayer,
                    releaseCaptureRoute = { sco.releaseImmediately() },
                    awaitTelecomDisconnected = { withTimeoutOrNull(POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS) { telecomDisconnected.await() } },
                ),
                source = voiceCommunicationSource,
            )
            InputMode.Work -> resolveAudioRoute(
                scoRoute = sco,
                scoOutput = pcmOutput,
                scoSource = voiceCommunicationSource,
                localOutput = localOutput,
                localSource = micSource,
            )
            InputMode.OnAPinch -> ResolvedAudioRoute(
                sco = NoopScoRoute(),
                output = localOutput,
                source = micSource,
            )
        }
    }

    private fun dispatchPttReleased() {
        dispatchPttReleased(PttSource.Rsm)
    }

    private fun dispatchPttReleased(source: PttSource) {
        val session = activePttSession?.takeIf { ownsPttRelease(it.source, source) } ?: return
        activePttSession = null
        val appState = _appState.value
        val activeChannelId = session.channelId

        val route = session.route

        when (activeChannelId) {
            JournalChannel.ID -> journalPttController?.onPttReleased(route)
            DebugChannel.ID -> {
                when (appState.debugChannel.mode) {
                    DebugMode.ECHO -> echo.onPttReleased(route)
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
        if (inputModeController.mode == InputMode.OnTheRoad) {
            startIdleTimer()
        }
        updateCarMediaState()
    }

    private fun updateCarMediaState() {
        val activeReady = decidePttDispatch(_appState.value) is PttDispatchDecision.Dispatch
        val onTheRoadAvailable = inputModeController.availability.onTheRoad
        val state = when {
            activePttSession != null -> CarMediaPttState.Recording
            idleTimerJob?.isActive == true -> CarMediaPttState.Finalizing
            activeReady && onTheRoadAvailable -> CarMediaPttState.Ready
            onTheRoadAvailable -> CarMediaPttState.Ready
            else -> CarMediaPttState.NotReady
        }
        CarMediaStateBus.update(state)
    }

    private fun isTerminalCarStatus(status: EchoStatus): Boolean = isTerminalCarSource() &&
        (status is EchoStatus.Error || status == EchoStatus.MaxDurationReached)

    private fun isTerminalCarStatus(status: SttStatus): Boolean = isTerminalCarSource() &&
        (status is SttStatus.Error || status == SttStatus.MaxDurationReached)

    private fun isTerminalCarStatus(status: SttTtsStatus): Boolean = isTerminalCarSource() &&
        (status is SttTtsStatus.Error || status == SttTtsStatus.MaxDurationReached)

    private fun isTerminalCarSource(): Boolean = activePttSession?.source == PttSource.CarTelecom

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
        forceReleaseActivePtt()
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

    companion object {
        const val ACTION_START_MONITORING = "dev.nilp0inter.subspace.START_MONITORING"

        const val NOTIFICATION_CHANNEL_ID = "subspace_device_link"
        const val NOTIFICATION_ID = 41

        private const val TAG = "SubspacePttService"
        private const val PARAKEET_ASSET_VERSION = "int8-2026-06-23"
        private const val SUPERTONIC_ASSET_VERSION = "supertonic-3-2026-06-24"
        private const val STT_MODEL_POLL_MS = 500L
        private const val TTS_MODEL_POLL_MS = 500L
        private const val SCO_RATE = 16_000
        private const val POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS = 3_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}
