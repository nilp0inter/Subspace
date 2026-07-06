package dev.nilp0inter.subspace.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.nilp0inter.subspace.MainActivity
import dev.nilp0inter.subspace.R
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelBrowseEntry
import dev.nilp0inter.subspace.model.ChannelRepository
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.HardwareMode
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.InputModeSelection
import dev.nilp0inter.subspace.model.KeyboardChannel
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.RawButtonEvent
import dev.nilp0inter.subspace.model.projectChannelBrowseEntries
import dev.nilp0inter.subspace.model.orderedChannelIds
import dev.nilp0inter.subspace.model.selectChannelByOffset
import dev.nilp0inter.subspace.protocol.ButtonStateMachine
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PttForegroundService : Service(), CarPttCommandListener, TelecomCarPttCoordinator.Listener {
    private val binder = LocalBinder()
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    internal lateinit var connectionManager: SubspaceConnectionManager
    internal lateinit var audioCoordinator: AudioSessionCoordinator
    internal lateinit var speechEngineController: SpeechEngineController
    internal lateinit var telecomBridge: TelecomBridge

    internal lateinit var channelRepository: ChannelRepository
    private val buttonStateMachine = ButtonStateMachine()
    internal var foreground = false

    val isCapturing: StateFlow<Boolean> get() = audioCoordinator.isCapturing
    val level: StateFlow<Float> get() = audioCoordinator.level

    val channelBrowseEntries: Flow<List<ChannelBrowseEntry>>
        get() = _appState
            .map { projectChannelBrowseEntries(it) }
            .distinctUntilChanged()

    inner class LocalBinder : Binder() {
        fun service(): PttForegroundService = this@PttForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        channelRepository = ChannelRepository(applicationContext)
        
        connectionManager = SubspaceConnectionManager(this, serviceScope) { _appState.value }
        audioCoordinator = AudioSessionCoordinator(this, serviceScope, { _appState.value }, connectionManager)
        speechEngineController = SpeechEngineController(this, serviceScope, { _appState.value }, audioCoordinator, connectionManager)
        telecomBridge = TelecomBridge(this, serviceScope, { _appState.value }, audioCoordinator, connectionManager)

        audioCoordinator.pttHandler = speechEngineController

        _appState.value = _appState.value.copy(
            journal = channelRepository.loadJournal(),
            debugChannel = channelRepository.loadDebugChannel(),
            keyboard = channelRepository.loadKeyboard({ speechEngineController.sleepwalkerConnection.connectionState.value == dev.nilp0inter.subspace.model.KeyboardConnectionState.Connected }),
            activeChannelId = channelRepository.loadActiveChannelId(),
        )

        connectionManager.onCreate()
        audioCoordinator.onCreate()
        speechEngineController.onCreate()
        telecomBridge.onCreate()

        CarPttCommandBus.setListener(this)
        TelecomCarPttCoordinator.setListener(this)
        
        AndroidAutoPresenceBus.setListener { connected ->
            updateInputMode()
        }

        refreshReadiness()
        updateInputMode()
        updateCarMediaState()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_MONITORING) {
            ensureForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        audioCoordinator.forceReleaseActivePtt()
        speechEngineController.onDestroy()
        connectionManager.onDestroy()
        telecomBridge.onDestroy()
        CarPttCommandBus.setListener(null)
        AndroidAutoPresenceBus.setListener(null)
        TelecomCarPttCoordinator.setListener(null)
        TelecomCarPttCoordinator.forceAbort()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun refreshReadiness() {
        connectionManager.refreshReadiness()
    }

    fun scanForDevice() {
        connectionManager.scanForDevice()
    }

    fun pairTarget() {
        connectionManager.pairTarget()
    }

    fun connectSerial() {
        connectionManager.connectSerial()
    }

    fun disconnectSerial() {
        audioCoordinator.forceReleaseActivePtt()
        speechEngineController.cancelAll()
        connectionManager.disconnectSerial()
    }

    fun setJournalDirectory(path: String) {
        speechEngineController.setJournalDirectory(path)
    }

    fun setKeyboardHostProfile(profile: io.sleepwalker.core.keymap.HostProfile) {
        speechEngineController.setKeyboardHostProfile(profile)
    }

    fun connectKeyboardBridge() {
        speechEngineController.connectKeyboardBridge()
    }

    fun disconnectKeyboardBridge() {
        speechEngineController.disconnectKeyboardBridge()
    }

    fun setJournalSaveVoice(enabled: Boolean) {
        speechEngineController.setJournalSaveVoice(enabled)
    }

    fun setJournalSaveText(enabled: Boolean) {
        speechEngineController.setJournalSaveText(enabled)
    }

    fun setActiveChannelId(id: String) {
        val current = _appState.value
        if (current.activeChannelId == id) return

        _appState.value = _appState.value.copy(activeChannelId = id)
        channelRepository.saveActiveChannelId(id)
        speechEngineController.updateActiveControllers()
        audioCoordinator.updateCarMediaState()
    }

    fun setActiveChannelOffset(offset: Int) {
        val orderedIds = orderedChannelIds(_appState.value)
        val newId = selectChannelByOffset(orderedIds, _appState.value.activeChannelId, offset)
            ?: return
        setActiveChannelId(newId)
    }

    fun skipCurrentMessage() {
        // Unimplemented in original
    }

    fun replayLastHeard() {
        // Unimplemented in original
    }

    fun phonePttPressed(channelId: String) {
        Log.d(ROUTE_LOG_TAG, "PHONE_PTT_PRESSED channel=$channelId")
        audioCoordinator.logAudioRouteSnapshot("phone-ptt-pressed")
        setActiveChannelId(channelId)
        audioCoordinator.dispatchPttPressed(dev.nilp0inter.subspace.model.PttSource.Phone)
    }

    fun phonePttReleased(channelId: String) {
        audioCoordinator.dispatchPttReleased(dev.nilp0inter.subspace.model.PttSource.Phone)
    }

    fun setInputMode(mode: InputMode): Boolean = setInputMode(mode, InputModeSelection.User)

    fun setInputMode(mode: InputMode, by: InputModeSelection): Boolean {
        val changed = audioCoordinator.inputModeController.setInputMode(mode, by)
        if (changed) publishInputMode()
        return changed
    }

    fun updateInputMode() {
        val readyForMonitor = _appState.value.readyForMonitor
        val aaConnected = AndroidAutoPresenceBus.isConnected()
        audioCoordinator.inputModeController.updateInputs(readyForMonitor, aaConnected)
    }

    fun publishInputMode() {
        _appState.value = _appState.value.copy(
            inputMode = audioCoordinator.inputModeController.mode,
            inputModeSelectedBy = audioCoordinator.inputModeController.selectedBy,
            inputModeAvailability = audioCoordinator.inputModeController.availability,
        )
    }

    fun updateCarMediaState() {
        audioCoordinator.updateCarMediaState()
    }

    fun setDebugChannelMode(mode: DebugMode) {
        channelRepository.saveDebugChannel(DebugChannel(mode = mode))
        _appState.value = _appState.value.copy(debugChannel = DebugChannel(mode = mode))
        speechEngineController.updateActiveControllers()
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
        speechEngineController.requestTtsSynthesis()
    }

    internal fun handleRawButtonEvent(event: RawButtonEvent) {
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
                    speechEngineController.announce("sys.menu.channels")
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
                    speechEngineController.announce("chan.${_appState.value.activeChannelId}.selected")
                } else {
                    audioCoordinator.dispatchPttPressed()
                }
            }
            RawButtonEvent.PttReleased -> {
                if (previousMode != HardwareMode.Control) {
                    audioCoordinator.dispatchPttReleased()
                }
            }
            else -> Unit
        }
    }

    private fun cycleActiveChannel(next: Boolean) {
        val offset = if (next) 1 else -1
        val previousId = _appState.value.activeChannelId
        setActiveChannelOffset(offset)
        val newId = _appState.value.activeChannelId
        if (newId != previousId) {
            speechEngineController.announce("chan.$newId.name")
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

    fun updateAppState(transform: (AppState) -> AppState) {
        _appState.value = transform(_appState.value)
    }

    fun updateConnection(transform: (ConnectionState) -> ConnectionState) {
        _appState.value = _appState.value.copy(connection = transform(_appState.value.connection))
        updateInputMode()
        updateCarMediaState()
        connectionManager.syncReadinessRefreshLoop()
    }

    fun updateMonitor(transform: (MonitorState) -> MonitorState) {
        _appState.value = _appState.value.copy(monitor = transform(_appState.value.monitor))
    }

    internal fun ensureForeground() {
        if (foreground) return
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
        connectionManager.syncReadinessRefreshLoop()
    }

    internal fun stopForegroundIfNeeded() {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        foreground = false
        connectionManager.syncReadinessRefreshLoop()
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

    // CarPttCommandListener
    override fun onCarPttStart() = telecomBridge.onCarPttStart()
    override fun onCarPttRelease() = telecomBridge.onCarPttRelease()
    override fun onCarSetActiveChannel(id: String) = setActiveChannelId(id)
    override fun onCarSetActiveChannelOffset(offset: Int) = setActiveChannelOffset(offset)
    override fun onCarSkipMessage() = skipCurrentMessage()
    override fun onCarReplayMessage() = replayLastHeard()

    // TelecomCarPttCoordinator.Listener
    override fun onTelecomCaptureStart() = telecomBridge.onTelecomCaptureStart()
    override fun onTelecomCaptureStop() = telecomBridge.onTelecomCaptureStop()
    override fun onTelecomRouteTimeout() = telecomBridge.onTelecomRouteTimeout()
    override fun onTelecomConnectionEnded() = telecomBridge.onTelecomConnectionEnded()

    companion object {
        const val ACTION_START_MONITORING = "dev.nilp0inter.subspace.START_MONITORING"
        const val NOTIFICATION_CHANNEL_ID = "subspace_device_link"
        const val NOTIFICATION_ID = 41
    }
}
