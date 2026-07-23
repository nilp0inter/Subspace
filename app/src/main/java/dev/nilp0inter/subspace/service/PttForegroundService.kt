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
import androidx.core.app.NotificationCompat
import dev.nilp0inter.subspace.MainActivity
import dev.nilp0inter.subspace.lua.PluginLogSink
import dev.nilp0inter.subspace.lua.PluginLogSinkImpl
import dev.nilp0inter.subspace.lua.LogRecord
import dev.nilp0inter.subspace.R
import dev.nilp0inter.subspace.audio.AndroidMicCaptureSource
import dev.nilp0inter.subspace.audio.AndroidPcmOutput
import dev.nilp0inter.subspace.audio.AndroidVoiceCommunicationCaptureSource
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.LocalPcmOutput
import dev.nilp0inter.subspace.audio.MediaResponsePlayer
import dev.nilp0inter.subspace.audio.MediaResponsePcmOutput
import dev.nilp0inter.subspace.audio.ModelAssetRepository
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoAudioController
import dev.nilp0inter.subspace.audio.StateLossCallback
import dev.nilp0inter.subspace.audio.audioModeDebugString
import dev.nilp0inter.subspace.audio.routeDebugString
import dev.nilp0inter.subspace.bluetooth.DeviceScanner
import dev.nilp0inter.subspace.bluetooth.SppClient
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.JsonKeymapDatabase
import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.JournalBuiltInProvider
import dev.nilp0inter.subspace.channel.KeyboardBuiltInProvider
import dev.nilp0inter.subspace.channel.capability.AudioOperationArtifact
import dev.nilp0inter.subspace.channel.capability.AudioOperationCapabilityAdapter
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.PlaybackResultFactory
import dev.nilp0inter.subspace.channel.capability.RecordingPlaybackResultFactory
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelImplementationDescriptor
import dev.nilp0inter.subspace.channel.OpenAiAgentBuiltInProvider
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.SpeechSynthesisRequest
import dev.nilp0inter.subspace.channel.capability.SpeechVoice
import dev.nilp0inter.subspace.openai.AndroidKeystoreBearerCredentialStore
import dev.nilp0inter.subspace.openai.OpenAiProfileMetadataStore
import dev.nilp0inter.subspace.openai.OpenAiProfileOperations
import dev.nilp0inter.subspace.openai.OpenAiProfileRepository
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkClientRegistry
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkCompletionService
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService
import dev.nilp0inter.subspace.ui.OpenAiProfileEditRequest
import dev.nilp0inter.subspace.ui.OpenAiProfileUiMutationResult
import java.util.concurrent.ConcurrentHashMap
import dev.nilp0inter.subspace.channel.TextOutputAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.BootstrapState
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelBrowseEntry
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.InputModeSelection
import dev.nilp0inter.subspace.model.ChannelRepository
import dev.nilp0inter.subspace.model.ConnectionState
import dev.nilp0inter.subspace.model.DevicePresence
import dev.nilp0inter.subspace.model.HardwareMode
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.PermissionState
import dev.nilp0inter.subspace.model.PttSource
import dev.nilp0inter.subspace.model.RawButtonEvent
import dev.nilp0inter.subspace.model.SppState
import dev.nilp0inter.subspace.model.projectChannelBrowseEntries
import dev.nilp0inter.subspace.model.orderedChannelIds
import dev.nilp0inter.subspace.model.selectChannelByOffset
import dev.nilp0inter.subspace.protocol.ButtonParser
import dev.nilp0inter.subspace.protocol.ButtonStateMachine
import dev.nilp0inter.subspace.telecom.SubspacePhoneAccountRegistrar
import dev.nilp0inter.subspace.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class PluginLogProjection(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
    val timestampMillis: Long,
)

internal fun LogRecord.toPluginLogProjection(): PluginLogProjection? {
    val mappedLevel = when (level.lowercase()) {
        "debug" -> LogLevel.Debug
        "info" -> LogLevel.Info
        "warn" -> LogLevel.Warn
        "error" -> LogLevel.Error
        else -> return null
    }
    return PluginLogProjection(
        level = mappedLevel,
        tag = "LuaChannel",
        message = message,
        throwable = null,
        timestampMillis = timestampMillis,
    )
}

internal suspend fun forwardNextPluginLog(
    sink: PluginLogSink,
    admit: (PluginLogProjection) -> Boolean,
): Boolean {
    val record = sink.receive() ?: return false
    val projection = record.toPluginLogProjection()
    if (projection == null || !admit(projection)) {
        sink.recordProjectionLoss()
    }
    return true
}

internal fun deriveCarMediaPttState(
    phase: PttAudioSessionManager.SessionPhase?,
    onTheRoadAvailable: Boolean,
): CarMediaPttState = when (phase) {
    PttAudioSessionManager.SessionPhase.PttHeld -> CarMediaPttState.Recording
    PttAudioSessionManager.SessionPhase.TerminalWork -> CarMediaPttState.Finalizing
    null -> if (onTheRoadAvailable) CarMediaPttState.Ready else CarMediaPttState.NotReady
}

internal fun rsmChannelOffset(event: RawButtonEvent): Int? = when (event) {
    RawButtonEvent.VolumeUpClicked -> -1
    RawButtonEvent.VolumeDownClicked -> 1
    else -> null
}

internal fun resolveRsmAnnouncementText(
    key: String,
    catalogue: ChannelCatalogueSnapshot,
): String? {
    if (catalogue.definitions.isEmpty()) return null
    if (key == "sys.menu.channels") return "Channels"

    val selected = key.endsWith(".selected")
    val suffix = if (selected) ".selected" else ".name"
    if (!key.startsWith("chan.") || !key.endsWith(suffix)) return null

    val channelId = key.removePrefix("chan.").removeSuffix(suffix)
    val name = catalogue.definitions.firstOrNull { it.id == channelId }?.name ?: return null
    return if (selected) "$name Selected" else name
}

internal fun shouldRetainMonitoringService(reason: ReconnectBlockReason): Boolean =
    reason != ReconnectBlockReason.MonitoringNotRequested

internal fun shouldStopAfterSerialDisconnect(
    serialDisconnectPending: Boolean,
    monitoringRequested: Boolean,
    hasActivePttSession: Boolean,
): Boolean = serialDisconnectPending && !monitoringRequested && !hasActivePttSession

class PttForegroundService : Service(), CarPttCommandListener, TelecomCarPttCoordinator.Listener, ChannelRouter {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateProjector = ServiceStateProjector(
        onConnectionUpdated = {
            updateInputMode()
            updateCarMediaState()
            if (::foregroundCoordinator.isInitialized) foregroundCoordinator.syncReadinessRefreshLoop()
        },
        onInputModePublished = ::updateCarMediaState,
    )
    val appState: StateFlow<AppState> get() = stateProjector.state

    /**
     * Process-scoped model asset repository — the single authoritative
     * owner of model inspection, acquisition, repair, and progress.
     */
    private lateinit var modelRepository: ModelAssetRepository

    /**
     * Service-owned bootstrap coordinator. Owns the authoritative bootstrap
     * state that drives loading/setup/recovery/dashboard routing.
     */
    private lateinit var bootstrapCoordinator: BootstrapCoordinator

    /** Bootstrap state observed by the activity to decide the root surface. */
    val bootstrapState: StateFlow<BootstrapState>
        get() = bootstrapCoordinator.state

    /** Model acquisition progress for loading display. */
    val modelAcquisitionProgress: StateFlow<dev.nilp0inter.subspace.model.ModelAcquisitionProgress>
        get() = modelRepository.progress

    val isCapturing: StateFlow<Boolean> get() = captureService.isCapturing
    val level: StateFlow<Float> get() = captureService.level

    /**
     * Car-browse projection of the channel list. Derived purely from
     * [appState] via [projectChannelBrowseEntries]; the Android Auto Media
     * service collects this to populate `onLoadChildren` and drive
     * `notifyChildrenChanged` (see design D3).
     * Pending counts are derived from provider-neutral runtime snapshots. The browse surface
     * never receives a provider payload, transcript, credential, or SDK object.
     */
    val channelBrowseEntries: Flow<List<ChannelBrowseEntry>>
        get() = stateProjector.state
            .map { state ->
                projectChannelBrowseEntries(state, state.channels.associate { it.id to it.pendingCount })
            }
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
    private lateinit var openAiProfiles: OpenAiProfileRepository
    private lateinit var openAiClients: OpenAiSdkClientRegistry
    private lateinit var openAiModels: OpenAiSdkModelDiscoveryService
    private lateinit var openAiProfileOperations: OpenAiProfileOperations
    private lateinit var openAiProfileFacade: ServiceOpenAiProfileFacade
    private lateinit var agentRuntimeGraph: ServiceAgentRuntimeGraph
    val profileUiState
        get() = openAiProfileFacade.profileUiState
    val dynamicChoiceResolver
        get() = openAiProfileFacade.dynamicChoiceResolver
    private lateinit var coreInitializer: ServiceCoreInitializer
    private lateinit var providerRegistry: ChannelImplementationProviderRegistry
    /** Service-owned installed-package coordinator; started after built-in registration. */
    private lateinit var installedPackagesCoordinator: InstalledPackagesCoordinator
    /** Internal facade exposing installed-package state and mutations. */
    private lateinit var installedPackagesFacade: InstalledPackagesFacade
    internal val installedPackages: InstalledPackagesFacade get() = installedPackagesFacade
    /** Service-owned package-management coordinator (source resolution, inspection, trust). */
    private lateinit var packageManagementCoordinator: PackageManagementCoordinator
    val packageManagementState: StateFlow<dev.nilp0inter.subspace.service.PackageManagementSummary>
        get() = packageManagementCoordinator.managementState
    private lateinit var pluginLogSink: PluginLogSink
    private var logSinkWorkerJob: Job? = null
    private val _channelDescriptors = MutableStateFlow<List<ChannelImplementationDescriptor>>(emptyList())
    val channelDescriptors: StateFlow<List<ChannelImplementationDescriptor>> = _channelDescriptors.asStateFlow()
    private lateinit var runtimeInvocationBoundary: RuntimeInvocationBoundary
    private lateinit var runtimeRegistry: ChannelRuntimeRegistry
    /** Process-wide synthetic scope for agent-initiated speech synthesis; allocated once, never generation zero. */
    private val agentPlaybackScope = CapabilityScopeIdentity("agent-playback", RuntimeGeneration.next())
    private lateinit var textOutputService: SleepwalkerTextOutputService
    private lateinit var capabilityHost: ServiceChannelCapabilityHost
    private val journalStorageBackends = ConcurrentHashMap<String, ServiceJournalStorageBackend>()
    private lateinit var scanner: DeviceScanner
    private lateinit var channelRepository: ChannelRepository
    private lateinit var channelManager: ServiceChannelManager
    private lateinit var mountBindingStore: dev.nilp0inter.subspace.resource.MountBindingStore
    private lateinit var safMountAdapter: dev.nilp0inter.subspace.mount.saf.SafMountAdapter
    private lateinit var mountSelectionController: dev.nilp0inter.subspace.ui.MountSelectionController
    val mountTreePickerBridge = dev.nilp0inter.subspace.mount.saf.SafTreePickerBridge()
    private lateinit var audioManager: AudioManager
    private lateinit var sco: ScoAudioController
    private lateinit var pcmOutput: AndroidPcmOutput
    private lateinit var telecomCaptureOutput: AndroidPcmOutput
    private lateinit var captureService: CaptureService
    private lateinit var voiceCommunicationSource: AndroidVoiceCommunicationCaptureSource
    lateinit var sleepwalkerConnection: SleepwalkerBleConnection
    private val keymapDatabase: JsonKeymapDatabase by lazy { JsonKeymapDatabase(resources) }
    private val buttonStateMachine = ButtonStateMachine()

    private lateinit var localOutput: MediaResponsePcmOutput
    private lateinit var micSource: AndroidMicCaptureSource
    private lateinit var telecomRegistrar: SubspacePhoneAccountRegistrar
    private lateinit var mediaResponsePlayer: MediaResponsePlayer
    private lateinit var carTelecomStarter: CarTelecomStarter
    private lateinit var carHfpConfigurationStore: CarHfpConfigurationStore
    private lateinit var carHfpConfigurationController: CarHfpConfigurationController<BluetoothDevice>
    private lateinit var audioSessionManager: PttAudioSessionManager
    private lateinit var hostAudioCoordinator: HostAudioCoordinator
    private lateinit var playbackRouteResolver: dev.nilp0inter.subspace.audio.ModePlaybackRouteResolver
    private lateinit var pttDispatcher: PttDispatcher
    private val inputModeController = InputModeController()
    private var idleTimerJob: Job? = null

    private lateinit var readinessProbe: ReadinessProbe
    private lateinit var serialCoordinator: RsmSerialConnectionCoordinator
    private lateinit var foregroundCoordinator: ForegroundServiceCoordinator
    private lateinit var announcementCoordinator: RsmAnnouncementCoordinator


    @SuppressLint("MissingPermission")
    private fun logAudioRouteSnapshot(event: String) {
        SubspaceLogger.d(ROUTE_LOG_TAG,
        "SNAPSHOT event=$event mode=${inputModeController.mode} selectedBy=${inputModeController.selectedBy} " +
            "availability=${inputModeController.availability} audioMode=${audioManager.mode.audioModeDebugString()} " +
            "current=${audioManager.communicationDevice.routeDebugString()} " +
            "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",)
    }


    inner class LocalBinder : Binder() {
        fun service(): PttForegroundService = this@PttForegroundService
    }
    val repository: ChannelRepository
        get() = channelRepository

    val logEntries: StateFlow<List<LogEntry>>
        get() = SubspaceLogger.entries

    val globalLogLevelFlow: StateFlow<LogLevel>
        get() = SubspaceLogger.globalLevelFlow

    val tagLogLevelsFlow: StateFlow<Map<String, LogLevel>>
        get() = SubspaceLogger.perTagLevelFlow

    fun clearLogs() = SubspaceLogger.clear()

    fun setGlobalLogLevel(level: LogLevel) = SubspaceLogger.setGlobalLevel(level)

    fun setTagLogLevel(tag: String, level: LogLevel) = SubspaceLogger.setTagLevel(tag, level)

    fun clearTagLogLevel(tag: String) = SubspaceLogger.clearTagLevel(tag)

    fun refreshCarHfpConfiguration() {
        if (!::carHfpConfigurationController.isInitialized) return
        val configuration = carHfpConfigurationController.refresh()
        stateProjector.publishCarHfpConfiguration(configuration)
    }

    fun selectCarHfpCandidate(selectionId: String) {
        if (!::carHfpConfigurationController.isInitialized) return
        val configuration = carHfpConfigurationController.select(selectionId)
        stateProjector.publishCarHfpConfiguration(configuration)
    }

    fun createProfile(request: OpenAiProfileEditRequest): OpenAiProfileUiMutationResult = openAiProfileFacade.create(request)

    fun updateProfile(request: OpenAiProfileEditRequest): OpenAiProfileUiMutationResult = openAiProfileFacade.update(request)

    fun deleteProfile(id: String): OpenAiProfileUiMutationResult = openAiProfileFacade.delete(id)

    fun testProfile(id: String) {
        serviceScope.launch(Dispatchers.IO) { openAiProfileFacade.test(id) }
    }

    fun refreshProfile(id: String) {
        serviceScope.launch(Dispatchers.IO) { openAiProfileFacade.refresh(id) }
    }

    override fun onCreate() {
        super.onCreate()
        SubspaceLogger.initialize(cacheDir)
        pluginLogSink = PluginLogSinkImpl()
        logSinkWorkerJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && forwardNextPluginLog(pluginLogSink) { projection ->
                SubspaceLogger.tryLogPlugin(
                    level = projection.level,
                    tag = projection.tag,
                    message = projection.message,
                    timestamp = projection.timestampMillis,
                )
            }) {
                // Drain until service shutdown closes the bounded sink.
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            val legacyCache = java.io.File(noBackupFilesDir, "announcement-cache")
            if (legacyCache.exists()) legacyCache.deleteRecursively()
        }
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        scanner = DeviceScanner(applicationContext, bluetoothAdapter)
        readinessProbe = ReadinessProbe(this, scanner, bluetoothAdapter, { headsetProxy })
        carHfpConfigurationStore = SharedPreferencesCarHfpConfigurationStore(applicationContext)
        carHfpConfigurationController = CarHfpConfigurationController(
            store = carHfpConfigurationStore,
            hasBluetoothConnect = { RequiredPermissions.hasBluetoothConnect(this) },
            profileDevicesProvider = { headsetProxy?.connectedDevices },
            targetRsmProvider = ::targetRsm,
            addressOf = { it.address },
            displayNameOf = { it.name },
            isConnected = { device -> headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED },
            log = { message -> SubspaceLogger.d(ROUTE_LOG_TAG, message) },
        )
        bluetoothAdapter?.getProfileProxy(this, headsetServiceListener, BluetoothProfile.HEADSET)
        sleepwalkerConnection = SleepwalkerBleConnection()
        textOutputService = SleepwalkerTextOutputService(
            scope = serviceScope,
            connection = sleepwalkerConnection,
            hid = LowLevelHidImpl(),
            keymapDatabase = keymapDatabase,
            connect = { timeoutMillis ->
                sleepwalkerConnection.ensureConnected(bluetoothAdapter, this@PttForegroundService, timeoutMillis)
            },
        )
        val openAiCredentials = AndroidKeystoreBearerCredentialStore(applicationContext)
        openAiProfiles = OpenAiProfileRepository(
            OpenAiProfileMetadataStore(java.io.File(noBackupFilesDir, "openai-profiles.json")),
            openAiCredentials,
        )
        openAiClients = OpenAiSdkClientRegistry(openAiProfiles, openAiCredentials)
        openAiModels = OpenAiSdkModelDiscoveryService(openAiClients)
        openAiProfileOperations = OpenAiProfileOperations(openAiProfiles, openAiClients, openAiModels)
        openAiProfileFacade = ServiceOpenAiProfileFacade(
            serviceScope,
            openAiProfiles,
            openAiCredentials,
            openAiProfileOperations,
            openAiModels,
        ) {
            (keymapDatabase.profiles.ifEmpty { listOf(io.sleepwalker.core.keymap.HostProfile.LINUX_US) }).distinctBy { it.key }.sortedBy { it.key }.map { p ->
                val label = buildString {
                    append(p.layout)
                    p.variant?.let { append(" ($it)") }
                    append(" [${p.hostOs}]")
                }
                dev.nilp0inter.subspace.model.DynamicConfigurationChoice(p.key, label)
            }
        }
        val keyboardProvider = KeyboardBuiltInProvider()
        providerRegistry = ChannelImplementationProviderRegistry().also { providers ->
            check(providers.register(JournalBuiltInProvider()) is dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult.Registered)
            check(providers.register(keyboardProvider) is dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult.Registered)
            check(providers.register(OpenAiAgentBuiltInProvider()) is dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult.Registered)
        }
        _channelDescriptors.value = providerRegistry.descriptors()
        channelRepository = ChannelRepository(applicationContext, providerRegistry)
        stateProjector.publishChannels(emptyList())

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
        telecomCaptureOutput = AndroidPcmOutput(audioManager, requireActiveScoCommunicationDevice = false)
        val rawLocalOutput = LocalPcmOutput()
        micSource = AndroidMicCaptureSource()
        captureService = CaptureService(serviceScope)
        voiceCommunicationSource = AndroidVoiceCommunicationCaptureSource()
        telecomRegistrar = SubspacePhoneAccountRegistrar(this)
        telecomRegistrar.register()
        mediaResponsePlayer = MediaResponsePlayer(audioManager, rawLocalOutput)
        localOutput = MediaResponsePcmOutput(rawLocalOutput, mediaResponsePlayer)
        hostAudioCoordinator = HostAudioCoordinator()
        playbackRouteResolver = dev.nilp0inter.subspace.audio.ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = ::targetRsm,
            awaitTelecomCaptureRelease = ::awaitTelecomCaptureReleaseForPlayback,
        )
        audioSessionManager = PttAudioSessionManager(
            scope = serviceScope,
            captureService = captureService,
            channelRouter = this,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onTerminalCompleted = ::onAudioSessionTerminalCompleted,
        )
        agentRuntimeGraph = ServiceAgentRuntimeComposition.create(
            context = applicationContext,
            scope = serviceScope,
            catalogue = { channelRepository.catalogueState.value },
            selectedChannel = { channelRepository.catalogueState.value.activeChannelId },
            modelDiscovery = openAiModels,
            completion = OpenAiSdkCompletionService(openAiClients),
            synthesize = { text ->
                synthesisCapability(agentPlaybackScope)
                    ?.synthesize(SpeechSynthesisRequest(text, "en", SpeechVoice("default")))
                    ?: CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.MODEL_NOT_READY)
            },
            play = { _, recording ->
                when (val result = hostAudioCoordinator.play(recording) {
                    playbackRouteResolver.strategyFor(inputModeController.mode)
                }) {
                    HostPlaybackResult.Completed -> DelayedPlaybackAudioResult.Completed
                    HostPlaybackResult.ExplicitlySkipped -> DelayedPlaybackAudioResult.ExplicitlySkipped
                    HostPlaybackResult.Interrupted -> DelayedPlaybackAudioResult.Interrupted
                    HostPlaybackResult.Busy -> DelayedPlaybackAudioResult.Busy
                    HostPlaybackResult.Closed -> DelayedPlaybackAudioResult.Cancelled
                    is HostPlaybackResult.Unavailable,
                    is HostPlaybackResult.Failed,
                    -> DelayedPlaybackAudioResult.Failed(dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason.PLAYBACK_FAILED)
                }
            },
            playOperation = { _, operation ->
                val recording = dev.nilp0inter.subspace.channel.capability.recordedPcmOf(operation)
                    ?: return@create DelayedPlaybackAudioResult.Failed(dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason.PLAYBACK_FAILED)
                when (val result = hostAudioCoordinator.play(recording) {
                    playbackRouteResolver.strategyFor(inputModeController.mode)
                }) {
                    HostPlaybackResult.Completed -> DelayedPlaybackAudioResult.Completed
                    HostPlaybackResult.ExplicitlySkipped -> DelayedPlaybackAudioResult.ExplicitlySkipped
                    HostPlaybackResult.Interrupted -> DelayedPlaybackAudioResult.Interrupted
                    HostPlaybackResult.Busy -> DelayedPlaybackAudioResult.Busy
                    HostPlaybackResult.Closed -> DelayedPlaybackAudioResult.Cancelled
                    is HostPlaybackResult.Unavailable,
                    is HostPlaybackResult.Failed,
                    -> DelayedPlaybackAudioResult.Failed(dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason.PLAYBACK_FAILED)
                }
            },
            textOutput = { channelId -> textOutputService.capabilityFor(channelId) },
            textOutputAvailable = { textOutputService.availability.value is TextOutputAvailability.Available }
        )
        channelManager = ServiceChannelManager(
            channelRepository = channelRepository,
            providerRegistry = providerRegistry,
            immediateSelection = { agentRuntimeGraph.playback.onChannelSelected(it) },
            deferredSelection = { agentRuntimeGraph.deferredAudioPlayback.onChannelSelected(it) },
            newChannelId = { java.util.UUID.randomUUID().toString() },
            log = { message -> SubspaceLogger.d(ROUTE_LOG_TAG, message) },
            onConfigurationCommitted = { channelId ->
                serviceScope.launch {
                    runtimeRegistry.reconcile(channelRepository.catalogueState.value)
                }
            },
        )
        serviceScope.launch { agentRuntimeGraph.coordinator.start() }
        capabilityHost = ServiceChannelCapabilityHost(
            textOutputService = textOutputService,
            transcription = ::transcriptionCapability,
            synthesis = ::synthesisCapability,
            audioOperation = ::audioOperationCapability,
            journal = ::journalCapability,
            openAiModelDiscovery = { agentRuntimeGraph.modelDiscovery },
            openAiCompletion = { agentRuntimeGraph.completion },
            asynchronousConversation = { agentRuntimeGraph.coordinator },
            delayedPlayback = { agentRuntimeGraph.playback },
            deferredAudioPlayback = { agentRuntimeGraph.deferredAudioPlayback },
        )
        serviceScope.launch(Dispatchers.Default) {
            keyboardProvider.updateHostProfiles(keymapDatabase.profiles)
            _channelDescriptors.value = providerRegistry.descriptors()
        }
        runtimeInvocationBoundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.create(workerCount = 2, queueCapacity = 64),
        )
        runtimeRegistry = ChannelRuntimeRegistry(
            providers = providerRegistry,
            capabilityHost = capabilityHost,
            invocationBoundary = runtimeInvocationBoundary,
            runtimeScope = serviceScope,
            closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onPttSessionCancelRequested = {
                pttDispatcher.cancelAnyActivePttForServiceTeardown(
                    caller = PttCancellationCaller.ChannelRuntimeShutdown,
                    reason = "Channel runtime shutdown",
                )
            }
        )
        // 2.7: Compose the generic SAF mount adapter and selection controller after
        // the runtime registry is live. Successful mount binding replacements trigger
        // a single-instance atomic reconcile through reconcileResourceBinding.
        mountBindingStore = dev.nilp0inter.subspace.resource.MountBindingStore(
            java.io.File(filesDir, "mount-bindings.json"),
        )
        mountBindingStore.load()
        val safGrantController = dev.nilp0inter.subspace.mount.saf.AndroidSafGrantController(contentResolver)
        safMountAdapter = dev.nilp0inter.subspace.mount.saf.SafMountAdapter(mountBindingStore, safGrantController)
        mountSelectionController = dev.nilp0inter.subspace.ui.MountSelectionController(safMountAdapter) { request, _ ->
            serviceScope.launch {
                runtimeRegistry.reconcileResourceBinding(channelRepository.catalogueState.value, request.ownerInstanceId)
            }
        }
        // Compose the service-owned installed-package coordinator after synchronous
        // built-in registration and runtime-registry construction. Package loading runs
        // asynchronously on Dispatchers.IO; built-in providers and foreground-service
        // startup proceed without waiting for package I/O. Lua remains dormant until the
        // runtime registry constructs a generation for a matching catalogue instance.
        installedPackagesCoordinator = InstalledPackagesCoordinator(
            storeRoot = java.io.File(noBackupFilesDir, "installed-lua-packages"),
            providerRegistry = providerRegistry,
            bridge = dev.nilp0inter.subspace.lua.LuaNativeKernelBridge(),
            logSink = pluginLogSink,
            onCatalogueReconcile = {
                _channelDescriptors.value = providerRegistry.descriptors()
                runtimeRegistry.reconcile(channelRepository.catalogueState.value)
            },
            serviceScope = serviceScope,
        )
        installedPackagesFacade = InstalledPackagesFacade(installedPackagesCoordinator)
        installedPackagesCoordinator.start()
        // Compose the package-management coordinator after the installed-package
        // facade is live. It owns source resolution, candidate inspection, trust
        // confirmation, and delegates committed mutations to the facade. All network
        // and disk I/O runs on Dispatchers.IO; startup is non-blocking.
        val packageStoreRoot = java.io.File(noBackupFilesDir, "installed-lua-packages")
        val gitHubHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val gitHubSourceClient = dev.nilp0inter.subspace.dependency.RealGitHubPackageSourceClient(
            dev.nilp0inter.subspace.dependency.OkHttpGitHubTransport(gitHubHttpClient),
        )
        packageManagementCoordinator = PackageManagementCoordinator(
            facade = installedPackagesFacade,
            sourceClient = gitHubSourceClient,
            providerRegistry = providerRegistry,
            storeRoot = packageStoreRoot,
            serviceScope = serviceScope,
        )
        packageManagementCoordinator.start()
        carTelecomStarter = CarTelecomStarter(
            context = this,
            serviceScope = serviceScope,
            sco = sco,
            audioManager = audioManager,
            headsetProxyProvider = { headsetProxy },
            targetRsm = ::targetRsm,
            inputModeController = inputModeController,
            carConfigurationStore = carHfpConfigurationStore,
            telecomRegistrar = telecomRegistrar,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            publishInputMode = ::publishInputMode,
            isActivePttSession = { pttDispatcher.activePttSession != null },
            decidePttDispatch = { decidePttDispatch(runtimeRegistry.runtimeSnapshots.value) },
            reserveCaptureAdmission = { pttDispatcher.reserveCaptureAdmission() },
            abandonCaptureAdmission = { lease -> pttDispatcher.abandonCaptureAdmission(lease) },
            reservePendingCarPtt = { channelId, lease ->
                pttDispatcher.reservePendingPtt(PttSource.CarTelecom, channelId, lease)
            },
            cancelPendingCarPtt = { reason ->
                pttDispatcher.cancelPttBySource(
                    source = PttSource.CarTelecom,
                    caller = PttCancellationCaller.CarSetupFailure,
                    reason = reason,
                    eligibility = PttAudioSessionManager.CancellationEligibility.PendingOnly,
                )
            },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
            updateCarMediaState = ::updateCarMediaState,
        )
        pttDispatcher = PttDispatcher(
            serviceScope = serviceScope,
            inputModeController = inputModeController,
            audioSessionManager = audioSessionManager,
            audioCoordinator = hostAudioCoordinator,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            publishInputMode = ::publishInputMode,
            cancelIdleTimer = ::cancelIdleTimer,
            decidePttDispatch = { decidePttDispatch(runtimeRegistry.runtimeSnapshots.value) },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
            updateCarMediaState = ::updateCarMediaState,
        )
        serviceScope.launch {
            textOutputService.availability.collect { availability ->
                val monitorState = when (availability) {
                    TextOutputAvailability.Available -> dev.nilp0inter.subspace.model.KeyboardConnectionState.Connected
                    TextOutputAvailability.Preparing -> dev.nilp0inter.subspace.model.KeyboardConnectionState.Connecting
                    TextOutputAvailability.Unavailable,
                    TextOutputAvailability.Closed -> dev.nilp0inter.subspace.model.KeyboardConnectionState.Disconnected
                }
                updateMonitor { it.copy(keyboardConnectionState = monitorState) }
                runtimeRegistry.refreshReadiness()
            }
        }
        CarPttCommandBus.setListener(this)
        TelecomCarPttCoordinator.setListener(this)
        serviceScope.launch {
            combine(
                runtimeRegistry.runtimeSnapshots,
                agentRuntimeGraph.coordinator.status,
                agentRuntimeGraph.deferredAudioPlayback.pendingCounts,
            ) { aggregate, agentStatuses, deferredPending ->
                Triple(aggregate, agentStatuses, deferredPending)
            }.collect { (aggregate, agentStatuses, deferredPending) ->
                val projected = aggregate.entries.map { snapshot ->
                    val deferred = deferredPending[snapshot.id] ?: 0
                    val status = agentStatuses[snapshot.id]
                    if (status == null) {
                        // Non-agent channel: no agent status to project, but the
                        // deferred opaque-audio pending count still applies additively.
                        snapshot.copy(pendingCount = deferred)
                    } else {
                        snapshot.copy(
                            pendingCount = status.pendingResponseCount + deferred,
                            playbackPaused = status.playbackPaused,
                            executionStatus = when (status.state) {
                                dev.nilp0inter.subspace.model.AgentRunState.QUEUED -> ChannelExecutionStatus.IDLE
                                dev.nilp0inter.subspace.model.AgentRunState.RUNNING,
                                dev.nilp0inter.subspace.model.AgentRunState.WAITING_FOR_TOOL,
                                dev.nilp0inter.subspace.model.AgentRunState.SYNTHESIZING,
                                dev.nilp0inter.subspace.model.AgentRunState.PENDING_PLAYBACK -> ChannelExecutionStatus.PROCESSING
                                dev.nilp0inter.subspace.model.AgentRunState.FAILED,
                                dev.nilp0inter.subspace.model.AgentRunState.INDETERMINATE -> ChannelExecutionStatus.FAILED
                                else -> ChannelExecutionStatus.SUCCESS
                            },
                        )
                    }
                }
                stateProjector.publishChannelRuntime(projected, aggregate.activeChannelId)
                updateCarMediaState()
                if (agentStatuses.values.any { it.pendingResponseCount > 0 }) {
                    agentRuntimeGraph.playback.onAudioAvailable()
                    agentRuntimeGraph.deferredAudioPlayback.onAudioAvailable()
                }
            }
        }
        serviceScope.launch {
            var previous = emptyMap<String, dev.nilp0inter.subspace.model.ChannelDefinition>()
            channelRepository.catalogueState.collect { snapshot ->
                val current = snapshot.definitions.associateBy { it.id }
                previous.values
                    .filter { it.implementationId == dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds.OPENAI_AGENT }
                    .forEach { old ->
                        val replacement = current[old.id]
                        if (replacement == null || replacement != old) {
                            agentRuntimeGraph.coordinator.replace(
                                runtimeRegistry.capabilityScopeIdentity(old.id) ?: CapabilityScopeIdentity(old.id, RuntimeGeneration.next()),
                                removed = replacement == null,
                            )
                        }
                    }
                previous = current
                runtimeRegistry.reconcile(snapshot)
            }
        }
        // Initialize the process-scoped model asset repository, core
        // initializer, and bootstrap coordinator. The initializer owns the
        // native STT/TTS/journal/navigation-TTS resources and implements
        // CoreInit for the bootstrap coordinator.
        modelRepository = ModelAssetRepository(this, serviceScope)
        coreInitializer = ServiceCoreInitializer(
            context = applicationContext,
            scope = serviceScope,
            nativeLibraryDirProvider = { applicationInfo.nativeLibraryDir },
            filesDirProvider = { filesDir },
            textOutputService = textOutputService,
            journalStorageBackends = journalStorageBackends,
            channelCatalogue = { channelRepository.catalogueState.value },
            modelStatusSink = ModelStatusSink { update ->
                updateMonitor {
                    var monitor = it
                    update.sttModelStatus?.let { s -> monitor = monitor.copy(sttModelStatus = s) }
                    update.ttsModelStatus?.let { s -> monitor = monitor.copy(ttsModelStatus = s) }
                    update.ttsStatus?.let { s -> monitor = monitor.copy(ttsStatus = s) }
                    monitor
                }
            },
            navigationStateLoss = StateLossCallback { failure, enginePackage ->
                bootstrapCoordinator.onNavigationVoiceStateLoss(failure, enginePackage)
            },
            hostAudioPlay = { recording ->
                hostAudioCoordinator.play(recording) {
                    playbackRouteResolver.strategyFor(InputMode.Work)
                } is HostPlaybackResult.Completed
            },
        )
        bootstrapCoordinator = BootstrapCoordinator(
            context = this,
            scope = serviceScope,
            modelRepository = modelRepository,
            coreInit = coreInitializer,
        )
        bootstrapCoordinator.startBootstrap()

        
        updateActiveControllers()

        serviceScope.launch {
            sco.state.collect { state ->
                updateMonitor { it.copy(scoState = state) }
            }
        }

        AndroidAutoPresenceBus.setListener { connected ->
            updateInputMode()
        }

        serialCoordinator = RsmSerialConnectionCoordinator(
            scope = serviceScope,
            adapterProvider = { bluetoothAdapter },
            scanner = RsmSerialScanner { runCatching { scanner.bondedTarget() }.getOrNull() },
            sppFactory = { adapter -> SppClientAdapter(SppClient(adapter, ButtonParser())) },
            elapsedRealtime = { SystemClock.elapsedRealtime() },
            reconnectScheduler = { delayMs, action ->
                val job = serviceScope.launch {
                    if (delayMs > 0) delay(delayMs)
                    action()
                }
                RsmReconnectHandle { job.cancel() }
            },
            prerequisitesProvider = { device -> reconnectPrerequisites(device ?: runCatching { scanner.bondedTarget() }.getOrNull()) },
            onEvent = ::handleSerialCoordinatorEvent,
        )
        foregroundCoordinator = ForegroundServiceCoordinator(
            scope = serviceScope,
            startForeground = ::startForegroundAtEdge,
            stopForeground = ::stopForegroundAtEdge,
            stopSelf = { startId -> if (startId == null) stopSelf() else stopSelf(startId) },
            refreshReadiness = ::refreshReadiness,
            monitoringRequested = { serialCoordinator.monitoringRequested },
            readyForMonitor = { stateProjector.snapshot().readyForMonitor },
            hasActivePttSession = { pttDispatcher.activePttSession != null },
            refreshIntervalMs = READINESS_REFRESH_INTERVAL_MS,
        )
        announcementCoordinator = RsmAnnouncementCoordinator(
            scope = serviceScope,
            catalogue = { channelRepository.catalogueState.value },
            navigationEngine = { coreInitializer.navigationTtsEngine },
            playPcm = { recording ->
                hostAudioCoordinator.play(recording) {
                    playbackRouteResolver.strategyFor(InputMode.Work)
                }
            },
            onSynthesisResult = bootstrapCoordinator::onNavigationSynthesisResult,
        )
        serialCoordinator.connectSerial()
        refreshReadiness()
        updateInputMode()

        updateCarMediaState()
    }
    private fun transcriptionCapability(identity: CapabilityScopeIdentity) =
        coreInitializer.transcriptionCapability(identity)

    private fun synthesisCapability(identity: CapabilityScopeIdentity) =
        coreInitializer.synthesisCapability(
            identity = identity,
            voiceStylePath = {
                val modelDir = coreInitializer.supertonicModelDir
                modelDir?.let { voiceStyleFile(stateProjector.snapshot().monitor.ttsVoiceStyle, it).absolutePath }
            },
            totalSteps = { stateProjector.snapshot().monitor.ttsTotalSteps },
        )

    private fun audioOperationCapability(identity: CapabilityScopeIdentity) =
        AudioOperationCapabilityAdapter(
            PlaybackResultFactory { samples, generation ->
                AudioOperationArtifact(
                    dev.nilp0inter.subspace.audio.TtsAudio.toScoPlayback(samples, SCO_RATE),
                    generation = generation,
                )
            },
            RecordingPlaybackResultFactory { recording, generation ->
                AudioOperationArtifact(recording, generation = generation)
            },
            identity,
        )

    private fun journalCapability(identity: CapabilityScopeIdentity) =
        coreInitializer.journalCapability(identity)

    // ---- Binder commands for bootstrap ----

    fun refreshBootstrapPrerequisites() {
        bootstrapCoordinator.refreshPrerequisites()
    }

    fun startModelAcquisition() {
        bootstrapCoordinator.startModelAcquisition()
    }

    fun retryBootstrap() {
        bootstrapCoordinator.retry()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_MONITORING) {
            foregroundCoordinator.onStartCommand(
                monitoringRequested = serialCoordinator.monitoringRequested,
                startId = startId,
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(45_000L) {
                    pttDispatcher.cancelAnyActivePttForServiceTeardown(
                        caller = PttCancellationCaller.ServiceTeardown,
                        reason = "Service teardown",
                    )
                    // Destruction is resumable: it cancels volatile workers but leaves the durable
                    // ledger intact. Replacement/removal remains generation-retirement work.
                    agentRuntimeGraph.coordinator.shutdown()
                    coreInitializer.shutdown()
                    hostAudioCoordinator.close()
                    agentRuntimeGraph.playback.close()
                    agentRuntimeGraph.deferredAudioPlayback.close()
                    // Shutdown package management first (source resolution, inspection).
                    // It depends on the installed-package facade, which depends on the
                    // repository. Tear down in dependency order.
                    if (::packageManagementCoordinator.isInitialized) {
                        packageManagementCoordinator.shutdown()
                    }
                    // Stop package publication and close the repository before runtime
                    // generations are torn down, so no new providers appear during teardown.
                    if (::installedPackagesCoordinator.isInitialized) {
                        installedPackagesCoordinator.shutdown()
                    }
                    if (::pluginLogSink.isInitialized) {
                        pluginLogSink.close()
                    }
                    runtimeRegistry.shutdownAndAwait()
                    withTimeoutOrNull(500L) {
                        logSinkWorkerJob?.join()
                    }
                    openAiProfileOperations.close()
                    textOutputService.close()
                    runtimeInvocationBoundary.close()
                }
            }
        }
        bootstrapCoordinator.cancelAttempt()
        CarPttCommandBus.setListener(null)
        AndroidAutoPresenceBus.setListener(null)
        TelecomCarPttCoordinator.setListener(null)
        TelecomCarPttCoordinator.forceAbort()
        idleTimerJob?.cancel()
        serialCoordinator.shutdown()
        foregroundCoordinator.stopReadinessRefreshLoop()
        serviceScope.cancel()
        foregroundCoordinator.stopForegroundIfNeeded()
        headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        headsetProxy = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun targetRsm(): BluetoothDevice? =
        runCatching { scanner.bondedTarget() }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun targetRsmName(): String? = targetRsm()?.name

    /**
     * Semantic On-the-road playback cannot inspect or claim a car output while Telecom still
     * owns capture. The route resolver calls this after host playback admission and before
     * selecting a physical output.
     */
    private suspend fun awaitTelecomCaptureReleaseForPlayback(): Boolean {
        repeat(160) {
            if (!TelecomCarPttCoordinator.isCaptureActive() &&
                audioManager.mode == android.media.AudioManager.MODE_NORMAL &&
                audioManager.communicationDevice?.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                return true
            }
            delay(25)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun isRsmHfpConnected(): Boolean = readinessProbe.isRsmHfpConnected()

    @SuppressLint("MissingPermission")
    private fun startTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        val connectionState = runCatching { proxy.getConnectionState(rsm) }.getOrDefault(-1)
        SubspaceLogger.d(ROUTE_LOG_TAG,
        "RSM_HFP_START_REQUEST target='${rsm.name}' connectionState=$connectionState " +
            "audioBefore=${runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)} " +
            "current=${audioManager.communicationDevice.routeDebugString()} " +
            "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",)
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



    private fun updateActiveControllers() {
        if (::runtimeRegistry.isInitialized) {
            serviceScope.launch { runtimeRegistry.refreshReadiness() }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshReadiness() {
        val previous = stateProjector.snapshot().connection
        val snapshot = readinessProbe.refresh(previous.devicePresence, serialCoordinator.targetDevice())
        if (snapshot.bondedDevice != null) serialCoordinator.setTargetDevice(snapshot.bondedDevice)
        updateConnection {
            it.copy(
                permissions = snapshot.permissions,
                missingPermissions = snapshot.missingPermissions,
                bluetoothEnabled = snapshot.bluetoothEnabled,
                devicePresence = snapshot.devicePresence,
                headsetAudio = snapshot.headsetAudio,
            )
        }
        refreshCarHfpConfiguration()
        updateInputMode()

        if (::runtimeRegistry.isInitialized) {
            serviceScope.launch { runtimeRegistry.refreshReadiness() }
        }
        serialCoordinator.onReadinessRefreshed()
    }


    @SuppressLint("MissingPermission")
    fun scanForDevice() {
        serviceScope.launch {
            refreshReadiness()
            if (stateProjector.snapshot().connection.permissions != PermissionState.Granted) return@launch
            if (!stateProjector.snapshot().connection.bluetoothEnabled) return@launch

            updateConnection { it.copy(devicePresence = DevicePresence.Scanning) }
            val found = runCatching { scanner.scanForTarget() }.getOrNull()
            serialCoordinator.setTargetDevice(found)
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
            val device = serialCoordinator.targetDevice() ?: runCatching { scanner.scanForTarget() }.getOrNull()
            if (device == null) {
                updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                return@launch
            }

            serialCoordinator.setTargetDevice(device)
            updateConnection { it.copy(devicePresence = DevicePresence.Pairing) }
            val bonded = runCatching { scanner.createBondAndWait(device) }.getOrDefault(false)
            updateConnection {
                it.copy(devicePresence = if (bonded) DevicePresence.Bonded else DevicePresence.PairingFailed)
            }
            refreshReadiness()
        }
    }

    fun connectSerial() {
        serialCoordinator.connectSerial()
        serviceScope.launch { refreshReadiness() }
    }

    fun disconnectSerial() {
        serialCoordinator.disconnectSerial()
    }


    fun createChannel(
        implementationId: dev.nilp0inter.subspace.model.ChannelImplementationId,
        name: String,
        payload: dev.nilp0inter.subspace.model.OpaqueJsonObject? = null,
    ): dev.nilp0inter.subspace.model.ChannelRepositoryMutationResult =
        channelManager.createChannel(implementationId, name, payload)

    fun updateChannelConfiguration(
        channelId: String,
        payload: dev.nilp0inter.subspace.model.OpaqueJsonObject,
    ): dev.nilp0inter.subspace.model.ChannelRepositoryMutationResult =
        channelManager.updateChannelConfiguration(channelId, payload)

    fun beginMountSelection(
        request: dev.nilp0inter.subspace.ui.MountSelectionRequest,
        declaration: dev.nilp0inter.subspace.dependency.PackageMountDeclaration,
    ) {
        mountSelectionController.begin(request, declaration)
    }

    fun completeMountSelection(
        outcome: dev.nilp0inter.subspace.mount.saf.SafTreePickerOutcome,
    ): dev.nilp0inter.subspace.ui.MountSelectionResult =
        mountSelectionController.complete(outcome)

    fun mountEditorEntries(
        channelInstanceId: String,
        implementationId: dev.nilp0inter.subspace.model.ChannelImplementationId,
    ): List<dev.nilp0inter.subspace.ui.MountEditorEntry> {
        val descriptor = providerRegistry.descriptors().firstOrNull { it.implementationId == implementationId }
            ?: return emptyList()
        return dev.nilp0inter.subspace.ui.MountEditorProjection.entries(
            descriptor.resourceDeclarations.mounts,
        ) { declarationId ->
            val declaration = descriptor.resourceDeclarations.mounts.firstOrNull { it.id == declarationId }
                ?: return@entries dev.nilp0inter.subspace.resource.MountAvailability.Unavailable(
                    dev.nilp0inter.subspace.resource.MountUnavailableReason.Undeclared,
                )
            val binding = mountBindingStore.currentBinding(channelInstanceId, implementationId, declarationId)
            dev.nilp0inter.subspace.resource.MountAvailabilityProjection.project(
                implementationId,
                declaration,
                binding,
            )
        }
    }

    fun selectChannel(id: String): Boolean = channelManager.selectChannel(id)

    fun setActiveChannelId(id: String) {
        selectChannel(id)
    }

    fun setActiveChannelOffset(offset: Int) {
        val orderedIds = orderedChannelIds(stateProjector.snapshot())
        val newId = selectChannelByOffset(orderedIds, stateProjector.snapshot().activeChannelId, offset)
            ?: return
        selectChannel(newId)
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

    fun startPhonePtt(channelId: String): Boolean {
        if (!selectChannel(channelId)) return false
        SubspaceLogger.d(ROUTE_LOG_TAG, "PHONE_PTT_PRESSED channel=$channelId")
        logAudioRouteSnapshot("phone-ptt-pressed")
        return pttDispatcher.dispatchPttPressed(PttSource.Phone)
    }

    fun phonePttReleased(channelId: String) {
        pttDispatcher.dispatchPttReleased(PttSource.Phone)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Package-management intents — fire-and-forget wrappers that delegate to
    // the service-owned coordinator on serviceScope. The UI never touches
    // OkHttp, files, streams, or repository transactions directly.
    // ──────────────────────────────────────────────────────────────────────

    fun resolvePackageRepository(url: String) {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.resolveRepository(url)
    }

    fun selectPackageRelease(releaseId: String) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.selectRelease(releaseId) }
    }

    fun confirmPackageInstall(acknowledged: Boolean) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.confirmTrustAndInstall(acknowledged) }
    }

    fun rollbackPackage(repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.confirmRollback(repositoryId, confirmed = true) }
    }

    fun removePackage(repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.confirmRemove(repositoryId, confirmed = true) }
    }

    fun cancelPackageInspection() {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.cancelInspection()
    }

    fun refreshPackageManagement(url: String) {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.refresh(url)
    }

    fun cleanupPackageManagementRouteExit() {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.cleanupRouteExit()
    }

    fun setInputMode(mode: InputMode): Boolean = setInputMode(mode, InputModeSelection.User)

    fun setInputMode(mode: InputMode, by: InputModeSelection): Boolean {
        val changed = inputModeController.setInputMode(mode, by)
        if (changed) {
            publishInputMode()
            if (::agentRuntimeGraph.isInitialized) {
                agentRuntimeGraph.playback.onAudioAvailable()
                agentRuntimeGraph.deferredAudioPlayback.onAudioAvailable()
            }
        }
        return changed
    }

    private fun updateInputMode() {
        val readyForMonitor = stateProjector.snapshot().readyForMonitor
        val aaConnected = AndroidAutoPresenceBus.isConnected()
        inputModeController.updateInputs(readyForMonitor, aaConnected)
        publishInputMode()
        if (::agentRuntimeGraph.isInitialized) {
            agentRuntimeGraph.playback.onAudioAvailable()
            agentRuntimeGraph.deferredAudioPlayback.onAudioAvailable()
        }
    }

    private fun publishInputMode() {
        stateProjector.publishInputMode(
            mode = inputModeController.mode,
            selectedBy = inputModeController.selectedBy,
            availability = inputModeController.availability,
        )
    }


    private fun onAudioSessionTerminalCompleted(
        completion: PttAudioSessionManager.TerminalCompletion,
    ) {
        pttDispatcher.onTerminalCompleted(completion)
        if (completion.mode == InputMode.OnTheRoad) startIdleTimer()
        agentRuntimeGraph.playback.onAudioAvailable()
        agentRuntimeGraph.deferredAudioPlayback.onAudioAvailable()
        updateCarMediaState()
        foregroundCoordinator.onPttTerminalCompleted()
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            idleTimerJob = null
            updateCarMediaState()
        }
    }

    private fun cancelIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }


    override fun onCarPttStart() {
        cancelIdleTimer()
        carTelecomStarter.startTelecomCarPtt()
    }

    override fun onCarPttRelease() {
        TelecomCarPttCoordinator.forceAbort()
    }

    override fun onTelecomCaptureStart() {
        pttDispatcher.dispatchPttPressed(PttSource.CarTelecom)
    }

    override fun onTelecomCaptureStop() {
        pttDispatcher.dispatchPttReleased(PttSource.CarTelecom)
    }
    override fun onTelecomRouteTimeout() {
        val cancellation = pttDispatcher.cancelPttBySource(
            source = PttSource.CarTelecom,
            caller = PttCancellationCaller.TelecomRouteTimeout,
            reason = "Telecom route timeout",
        )
        if (cancellation.disposition == PttAudioSessionManager.CancellationDisposition.Accepted) {
            carTelecomStarter.playCarErrorBeep()
        }
        carTelecomStarter.notifyTelecomDisconnected()
    }
    override fun onTelecomConnectionEnded() {
        pttDispatcher.cancelPttBySource(
            source = PttSource.CarTelecom,
            caller = PttCancellationCaller.TelecomConnectionEnded,
            reason = "Telecom connection ended",
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOnly,
        )
        carTelecomStarter.notifyTelecomDisconnected()
    }

    private fun voiceStyleFile(style: String, modelDir: java.io.File): java.io.File =
        java.io.File(modelDir, "$style.json")

    private fun cycleActiveChannel(offset: Int) {
        val previousId = stateProjector.snapshot().activeChannelId
        setActiveChannelOffset(offset)
        val newId = stateProjector.snapshot().activeChannelId
        if (newId == previousId) {
            announcementCoordinator.announceErrorBeep()
        } else {
            announcementCoordinator.announce("chan.$newId.name")
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
        val previousMode = stateProjector.snapshot().monitor.hardwareMode
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
                    announcementCoordinator.announce("sys.menu.channels")
                }
            }
            RawButtonEvent.VolumeUpClicked,
            RawButtonEvent.VolumeDownClicked -> {
                if (snapshot.hardwareMode == HardwareMode.Control) {
                    cycleActiveChannel(checkNotNull(rsmChannelOffset(event)))
                }
                scheduleVolumeExpiry()
            }
            RawButtonEvent.PttPressed -> {
                if (previousMode == HardwareMode.Control) {
                    selectChannel(stateProjector.snapshot().activeChannelId)
                    announcementCoordinator.announce("chan.${stateProjector.snapshot().activeChannelId}.selected")
                } else {
                    pttDispatcher.dispatchPttPressed(PttSource.Rsm)
                }
            }
            RawButtonEvent.PttReleased -> pttDispatcher.dispatchPttReleased(PttSource.Rsm)
            RawButtonEvent.SosPressed -> serviceScope.launch {
                if (hostAudioCoordinator.consumeSosDuringPlayback() is HostSosDisposition.DispatchToChannel) {
                    runtimeRegistry.dispatchSos(stateProjector.snapshot().activeChannelId)
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
            releaseStaleWorkRoute = { reason -> sco.releaseImmediately(reason) },
            releaseTelecomCaptureRoute = {
                dev.nilp0inter.subspace.service.releaseTelecomCaptureRoute(
                    audioManager, ::logAudioRouteSnapshot,
                )
            },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
        )

    private fun updateCarMediaState() {
        CarMediaStateBus.update(
            deriveCarMediaPttState(
                phase = pttDispatcher.activePttSession?.phase,
                onTheRoadAvailable = inputModeController.availability.onTheRoad,
            ),
        )
    }




    private fun handleSerialCoordinatorEvent(event: SerialCoordinatorEvent) {
        when (event) {
            is SerialCoordinatorEvent.CancelPtt -> pttDispatcher.cancelPttBySource(
                source = PttSource.Rsm,
                caller = event.caller,
                reason = event.reason,
            )
            SerialCoordinatorEvent.ReleaseTts -> coreInitializer.ttsController?.cancelAndRelease()
            is SerialCoordinatorEvent.SppStateChanged -> updateConnection {
                it.copy(
                    spp = event.state,
                    sppError = event.error,
                )
            }
            is SerialCoordinatorEvent.DevicePresenceChanged -> updateConnection {
                it.copy(devicePresence = event.presence)
            }
            SerialCoordinatorEvent.RequestEnsureForeground -> foregroundCoordinator.ensureForeground()
            SerialCoordinatorEvent.RequestStopForegroundAndSelf -> foregroundCoordinator.requestStopForegroundAndSelf()
            SerialCoordinatorEvent.RequestStopReadinessRefreshLoop -> foregroundCoordinator.stopReadinessRefreshLoop()
            SerialCoordinatorEvent.RequestReevaluateSerialDisconnectShutdown -> foregroundCoordinator.reevaluateSerialDisconnectShutdown()
            SerialCoordinatorEvent.RequestReadinessRefresh -> refreshReadiness()
            is SerialCoordinatorEvent.SerialDisconnectPendingChanged -> foregroundCoordinator.onSerialDisconnectPendingChanged(event.pending)
            is SerialCoordinatorEvent.RawButtonReceived -> handleRawButtonEvent(event.event)
            is SerialCoordinatorEvent.LogTermination -> SubspaceLogger.d(
                ROUTE_LOG_TAG,
                "RSM_SPP_SESSION_TERMINATION mode=${if (event.automatic) "Automatic" else "Manual"} " +
                    "everConnected=${event.everConnected} monitoringRequested=${event.monitoringRequested} " +
                    "reconnectDisposition=${event.disposition}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnectPrerequisites(
        bondedTarget: BluetoothDevice?,
    ): ReconnectPrerequisites = ReconnectPrerequisites(
        permissionsGranted = stateProjector.snapshot().connection.permissions == PermissionState.Granted,
        bluetoothEnabled = stateProjector.snapshot().connection.bluetoothEnabled,
        bondedTargetAvailable = bondedTarget?.bondState == BluetoothDevice.BOND_BONDED,
    )

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
        stateProjector.updateConnection(transform)
    }

    private fun updateMonitor(transform: (MonitorState) -> MonitorState) {
        stateProjector.updateMonitor(transform)
    }

    /**
     * Android-edge foreground start. Creates the notification channel and
     * notification, then invokes the actual [android.app.Service.startForeground]
     * with the existing notification ID, content, and foreground-service types.
     * Returns true on success; the coordinator owns the logical foreground flag
     * and readiness-loop sync.
     */
    private fun startForegroundAtEdge(): Boolean {
        createNotificationChannel()
        val notification = buildNotification()
        return runCatching {
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
        }.isSuccess
    }

    /**
     * Android-edge foreground stop. Invokes the actual
     * [android.app.Service.stopForeground] with the existing removal flag. The
     * coordinator owns the logical foreground flag and readiness-loop sync.
     */
    private fun stopForegroundAtEdge() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

    override suspend fun prepareInput(channelId: String): ChannelInputAcceptance {
        SubspaceLogger.d(ROUTE_LOG_TAG, "CHANNEL_INPUT_PREPARE channel=$channelId")
        return runtimeRegistry.prepareInput(channelId)
    }
    companion object {
        const val ACTION_START_MONITORING = "dev.nilp0inter.subspace.START_MONITORING"

        const val NOTIFICATION_CHANNEL_ID = "subspace_device_link"
        const val NOTIFICATION_ID = 41

        private const val READINESS_REFRESH_INTERVAL_MS = 5_000L
        private const val SCO_RATE = 16_000
        private const val POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS = 3_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}

private class SppClientAdapter(
    private val client: SppClient,
) : RsmSppConnection {
    override val state: StateFlow<SppState> = client.state
    override fun events(device: BluetoothDevice): Flow<RawButtonEvent> = client.events(device)
    override fun disconnect() = client.disconnect()
}
