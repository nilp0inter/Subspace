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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.nilp0inter.subspace.MainActivity
import dev.nilp0inter.subspace.R
import dev.nilp0inter.subspace.audio.AndroidPcmOutput
import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.InMemoryRecorder
import dev.nilp0inter.subspace.audio.OggEncoder
import dev.nilp0inter.subspace.audio.ParakeetAssetExtractor
import dev.nilp0inter.subspace.audio.ParakeetJniTranscriber
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.SttTranscriber
import dev.nilp0inter.subspace.audio.SupertonicAssetExtractor
import dev.nilp0inter.subspace.audio.SupertonicJniSynthesizer
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.audio.TtsSynthesizer
import dev.nilp0inter.subspace.audio.TranscriptionService
import dev.nilp0inter.subspace.bluetooth.DeviceScanner
import dev.nilp0inter.subspace.bluetooth.SppClient
import dev.nilp0inter.subspace.channel.CaptainsLogController
import dev.nilp0inter.subspace.channel.CaptainsLogPttController
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.CaptainsLogChannel
import dev.nilp0inter.subspace.model.ChannelRepository
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.DevicePresence
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.HeadsetAudioState
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.PermissionState
import dev.nilp0inter.subspace.model.RawButtonEvent
import dev.nilp0inter.subspace.model.SppState
import dev.nilp0inter.subspace.model.SttModelStatus
import dev.nilp0inter.subspace.model.SttStatus
import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TtsStatus
import dev.nilp0inter.subspace.protocol.ButtonParser
import dev.nilp0inter.subspace.protocol.ButtonStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PttForegroundService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var scanner: DeviceScanner
    private lateinit var channelRepository: ChannelRepository
    private lateinit var sco: ScoAudioController
    private lateinit var pcmOutput: AndroidPcmOutput
    private lateinit var echo: EchoController
    private var sttController: SttController? = null
    private var sttTranscriber: SttTranscriber? = null
    private var transcriptionService: TranscriptionService? = null
    private var sttModelStatusJob: Job? = null
    private var ttsController: TtsController? = null
    private var ttsSynthesizer: TtsSynthesizer? = null
    private var ttsModelStatusJob: Job? = null
    private var sttTtsController: SttTtsController? = null
    private var captainsLogPttController: CaptainsLogPttController? = null
    private val buttonStateMachine = ButtonStateMachine()

    private var targetDevice: BluetoothDevice? = null
    private var sppClient: SppClient? = null
    private var serialJob: Job? = null
    private var sppStateJob: Job? = null
    private val reconnectPolicy = ReconnectPolicy()
    private var reconnectJob: Job? = null
    private var foreground = false

    inner class LocalBinder : Binder() {
        fun service(): PttForegroundService = this@PttForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        scanner = DeviceScanner(applicationContext, bluetoothAdapter)
        channelRepository = ChannelRepository(applicationContext)
        _appState.value = _appState.value.copy(
            captainsLog = channelRepository.loadCaptainsLog(),
            debugChannel = channelRepository.loadDebugChannel(),
            activeChannelId = channelRepository.loadActiveChannelId(),
        )

        val audioManager = getSystemService(AudioManager::class.java)
        sco = ScoAudioController(audioManager)
        pcmOutput = AndroidPcmOutput(audioManager)
        echo = EchoController(
            scope = serviceScope,
            sco = sco,
            recorder = InMemoryRecorder(serviceScope),
            output = AndroidPcmOutput(audioManager),
        )

        initializeStt(audioManager)
        initializeTts(audioManager)
        initializeCaptainsLog(audioManager)
        
        updateActiveControllers()

        serviceScope.launch {
            sco.state.collect { state ->
                updateMonitor { it.copy(scoState = state) }
            }
        }
        serviceScope.launch {
            echo.status.collect { status ->
                updateMonitor { it.copy(echoStatus = status) }
            }
        }

        refreshReadiness()
    }

    private fun initializeStt(audioManager: AudioManager) {
        val transcriber = try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val modelDir = ParakeetAssetExtractor.extract(this, PARAKEET_ASSET_VERSION)
            ParakeetJniTranscriber(
                nativeLibDir = nativeLibDir,
                modelDir = modelDir.absolutePath,
            )
        } catch (err: Throwable) {
            android.util.Log.w(TAG, "STT transcriber unavailable: ${err.message}")
            null
        }
        sttTranscriber = transcriber
        if (transcriber != null) {
            val transcription = TranscriptionService(transcriber)
            transcriptionService = transcription
            sttController = SttController(
                scope = serviceScope,
                sco = sco,
                recorder = InMemoryRecorder(serviceScope),
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
                    updateMonitor {
                        val newTranscript = (status as? SttStatus.Transcribed)?.text
                            ?: it.sttTranscript
                        it.copy(sttStatus = status, sttTranscript = newTranscript)
                    }
                }
            }
        }
    }

    private fun initializeTts(audioManager: AudioManager) {
        val synth = try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val modelDir = SupertonicAssetExtractor.extract(this, SUPERTONIC_ASSET_VERSION)
            SupertonicJniSynthesizer(
                nativeLibDir = nativeLibDir,
                modelDir = modelDir.absolutePath,
            )
        } catch (err: Throwable) {
            android.util.Log.w(TAG, "TTS synthesizer unavailable: ${err.message}")
            null
        }
        ttsSynthesizer = synth
        if (synth != null) {
            ttsController = TtsController(
                scope = serviceScope,
                sco = sco,
                output = AndroidPcmOutput(audioManager),
                synthesizer = synth,
            )
            sttTtsController = SttTtsController(
                scope = serviceScope,
                sco = sco,
                recorder = InMemoryRecorder(serviceScope),
                output = AndroidPcmOutput(audioManager),
                transcriber = sttTranscriber ?: return,
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
                sttTtsController?.status?.collect { status ->
                    val transcript = (status as? SttTtsStatus.Transcript)?.text
                        ?: _appState.value.monitor.sttTtsTranscript
                    updateMonitor { it.copy(sttTtsStatus = status, sttTtsTranscript = transcript) }
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
        captainsLogPttController?.cancelAndRelease()
        stopForegroundIfNeeded()
        super.onDestroy()
    }

    private fun initializeCaptainsLog(audioManager: AudioManager) {
        val transcriber = transcriptionService ?: object : PcmTranscriber {
            override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
                throw IllegalStateException("STT transcriber unavailable")
            }
        }
        captainsLogPttController = CaptainsLogPttController(
            scope = serviceScope,
            sco = sco,
            recorder = InMemoryRecorder(serviceScope),
            output = AndroidPcmOutput(audioManager),
            captainsLog = CaptainsLogController(
                scope = serviceScope,
                encoder = OggEncoder(),
                transcriber = transcriber,
            ),
            channelProvider = { _appState.value.captainsLog },
        )
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
        
        if (activeChannelId != CaptainsLogChannel.ID) {
            captainsLogPttController?.cancelAndRelease()
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
        captainsLogPttController?.cancelAndRelease()
        stopForegroundIfNeeded()
        stopSelf()
        refreshReadiness()
    }

    fun setCaptainsLogDirectory(path: String) {
        val channel = channelRepository.loadCaptainsLog().copy(baseDirectory = path)
        saveCaptainsLog(channel)
    }

    fun setCaptainsLogSaveVoice(enabled: Boolean) {
        val current = _appState.value.captainsLog
        if (!enabled && !current.saveText) return
        saveCaptainsLog(current.copy(saveVoice = enabled))
    }

    fun setCaptainsLogSaveText(enabled: Boolean) {
        val current = _appState.value.captainsLog
        if (!enabled && !current.saveVoice) return
        saveCaptainsLog(current.copy(saveText = enabled))
    }

    fun setActiveChannelId(id: String) {
        val current = _appState.value
        if (current.activeChannelId == id) return

        channelRepository.saveActiveChannelId(id)
        _appState.update { it.copy(activeChannelId = id) }
        updateActiveControllers()
    }

    fun setDebugChannelMode(mode: DebugMode) {
        saveDebugChannel(_appState.value.debugChannel.copy(mode = mode))
        updateActiveControllers()
    }

    private fun saveCaptainsLog(channel: CaptainsLogChannel) {
        channelRepository.saveCaptainsLog(channel)
        _appState.value = _appState.value.copy(captainsLog = channel)
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
        val monitor = _appState.value.monitor
        val voiceStylePath = voiceStyleFile(monitor.ttsVoiceStyle).absolutePath
        tts.synthesize(
            text = monitor.ttsText,
            voiceStylePath = voiceStylePath,
            lang = monitor.ttsLang,
            totalSteps = monitor.ttsTotalSteps,
            speed = monitor.ttsSpeed,
            scoRate = SCO_RATE,
        )
    }

    private fun voiceStyleFile(style: String): java.io.File {
        val modelDir = SupertonicAssetExtractor.extract(this, SUPERTONIC_ASSET_VERSION)
        return java.io.File(modelDir, "$style.json")
    }

    private fun handleRawButtonEvent(event: RawButtonEvent) {
        val snapshot = buttonStateMachine.apply(event, SystemClock.elapsedRealtime())
        updateMonitor {
            it.copy(
                hardwareMode = snapshot.hardwareMode,
                buttons = snapshot.buttons,
            )
        }

        when (event) {
            RawButtonEvent.PttPressed -> dispatchPttPressed()
            RawButtonEvent.PttReleased -> dispatchPttReleased()
            RawButtonEvent.VolumeUpClicked,
            RawButtonEvent.VolumeDownClicked,
            -> scheduleVolumeExpiry()
            else -> Unit
        }
    }

    private fun dispatchPttPressed() {
        val appState = _appState.value
        val activeChannelId = appState.activeChannelId

        val activeChannel = when (activeChannelId) {
            CaptainsLogChannel.ID -> appState.captainsLog
            DebugChannel.ID -> appState.debugChannel
            else -> return
        }

        if (!activeChannel.isReady) {
            serviceScope.launch {
                sco.acquire()
                pcmOutput.playErrorBeep(sco.coldStart)
                sco.release()
            }
            return
        }

        when (activeChannelId) {
            CaptainsLogChannel.ID -> captainsLogPttController?.onPttPressed()
            DebugChannel.ID -> {
                when (appState.debugChannel.mode) {
                    DebugMode.ECHO -> echo.onPttPressed()
                    DebugMode.STT -> sttController?.onPttPressed()
                    DebugMode.TTS -> {
                        val monitor = appState.monitor
                        ttsController?.onPttPressed(
                            text = monitor.ttsText,
                            voiceStylePath = voiceStyleFile(monitor.ttsVoiceStyle).absolutePath,
                            lang = monitor.ttsLang,
                            totalSteps = monitor.ttsTotalSteps,
                            speed = monitor.ttsSpeed,
                            scoRate = SCO_RATE,
                        )
                    }
                    DebugMode.STT_TTS -> sttTtsController?.onPttPressed()
                }
            }
        }
    }

    private fun dispatchPttReleased() {
        val appState = _appState.value
        val activeChannelId = appState.activeChannelId

        val activeChannel = when (activeChannelId) {
            CaptainsLogChannel.ID -> appState.captainsLog
            DebugChannel.ID -> appState.debugChannel
            else -> return
        }

        if (!activeChannel.isReady) return

        when (activeChannelId) {
            CaptainsLogChannel.ID -> captainsLogPttController?.onPttReleased()
            DebugChannel.ID -> {
                when (appState.debugChannel.mode) {
                    DebugMode.ECHO -> echo.onPttReleased()
                    DebugMode.STT -> sttController?.onPttReleased()
                    DebugMode.TTS -> ttsController?.onPttReleased()
                    DebugMode.STT_TTS -> {
                        val monitor = appState.monitor
                        sttTtsController?.onPttReleased(
                            voiceStylePath = voiceStyleFile(monitor.sttTtsVoiceStyle).absolutePath,
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
        echo.cancelAndRelease("SPP disconnected")
        sttController?.cancelAndRelease()
        ttsController?.cancelAndRelease()
        sttTtsController?.cancelAndRelease()
        captainsLogPttController?.cancelAndRelease()
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
    }
}
