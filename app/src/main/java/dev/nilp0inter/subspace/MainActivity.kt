package dev.nilp0inter.subspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nilp0inter.subspace.channel.StoragePathResolver
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.BootstrapState
import dev.nilp0inter.subspace.model.ChannelRepositoryMutationResult
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.OfflineNavigationVoiceIssue
import dev.nilp0inter.subspace.service.PttForegroundService
import dev.nilp0inter.subspace.service.RequiredPermissions
import dev.nilp0inter.subspace.ui.BootstrapLoadingScreen
import dev.nilp0inter.subspace.ui.CarHfpConfigurationScreen
import dev.nilp0inter.subspace.ui.BootstrapRootSurface
import dev.nilp0inter.subspace.ui.ChannelConfigurationScreen
import dev.nilp0inter.subspace.ui.ConnectionScreen
import dev.nilp0inter.subspace.ui.DirectorySelection
import dev.nilp0inter.subspace.ui.InitialSetupScreen
import dev.nilp0inter.subspace.ui.MainDashboardScreen
import dev.nilp0inter.subspace.ui.MonitorScreen
import dev.nilp0inter.subspace.ui.LogAnalysisScreen
import dev.nilp0inter.subspace.ui.PackageManagementScreen
import dev.nilp0inter.subspace.ui.OpenAiProfileManagementScreen
import dev.nilp0inter.subspace.ui.OpenAiProfileUiError
import dev.nilp0inter.subspace.ui.OpenAiProfileUiItem
import dev.nilp0inter.subspace.ui.OpenAiProfileUiMutationResult
import dev.nilp0inter.subspace.ui.PttUiActions
import dev.nilp0inter.subspace.ui.bootstrapRootSurface
import dev.nilp0inter.subspace.ui.theme.SubspaceTheme

internal fun exitDashboardRoute(
    isPackageManagement: Boolean,
    cleanup: () -> Unit,
    setMainRoute: () -> Unit,
) {
    if (isPackageManagement) cleanup()
    setMainRoute()
}

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<PttForegroundService?>(null)
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
            service = (binder as PttForegroundService.LocalBinder).service().also { connectedService ->
                connectedService.refreshReadiness()
                connectedService.refreshBootstrapPrerequisites()
            }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val currentService = service
            val currentServiceState by rememberUpdatedState(currentService)
            val state by currentService?.appState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(AppState()) }
            val catalogue by currentService?.repository?.catalogueState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(null) }
            val level by currentService?.level?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(0f) }
            val isCapturing by currentService?.isCapturing?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(false) }
            val bootstrapState by currentService?.bootstrapState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(BootstrapState.ConnectingService) }
            val modelProgress by currentService?.modelAcquisitionProgress?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(dev.nilp0inter.subspace.model.ModelAcquisitionProgress()) }
            val providerDescriptors by currentService?.channelDescriptors?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyList()) }
            val profileUiState by currentService?.profileUiState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyList<OpenAiProfileUiItem>()) }
            val dynamicChoiceResolver = currentService?.dynamicChoiceResolver
                ?: dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolver {
                    dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution.Unavailable(
                        dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY,
                    )
                }
            val logEntries by currentService?.logEntries?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyList()) }
            val currentGlobalLevel by currentService?.globalLogLevelFlow?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(dev.nilp0inter.subspace.service.LogLevel.Debug) }
            val currentTagLevels by currentService?.tagLogLevelsFlow?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyMap()) }
            val packageManagementSummary by currentService?.packageManagementState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(dev.nilp0inter.subspace.service.PackageManagementSummary(
                    emptyList(),
                    dev.nilp0inter.subspace.service.PackageManagementState.Idle,
                    dev.nilp0inter.subspace.service.OperationGeneration(0L),
                )) }

            var dashboardRoute by rememberSaveable { mutableStateOf(DashboardRoute.Main) }
            var configuredChannelId by rememberSaveable { mutableStateOf<String?>(null) }
            var creatingImplementationId by rememberSaveable { mutableStateOf<String?>(null) }
            var creatingDisplayName by rememberSaveable { mutableStateOf("") }
            var pendingDirectoryOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingDirectoryFieldId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedDirectoryOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedDirectoryFieldId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedDirectoryPath by rememberSaveable { mutableStateOf<String?>(null) }
            val directorySelection = selectedDirectoryOwnerId?.let { ownerId ->
                selectedDirectoryFieldId?.let { fieldId ->
                    selectedDirectoryPath?.let { path -> DirectorySelection(ownerId, fieldId, path) }
                }
            }
            var pendingMountRequest by remember { mutableStateOf<dev.nilp0inter.subspace.ui.MountSelectionRequest?>(null) }
            var pendingMountDeclaration by remember { mutableStateOf<dev.nilp0inter.subspace.dependency.PackageMountDeclaration?>(null) }

            val rootSurface = bootstrapRootSurface(bootstrapState)
            val currentReadyForMonitor by rememberUpdatedState(state.readyForMonitor)
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                currentServiceState?.refreshBootstrapPrerequisites()
            }
            val directoryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val ownerId = pendingDirectoryOwnerId
                val fieldId = pendingDirectoryFieldId
                pendingDirectoryOwnerId = null
                pendingDirectoryFieldId = null
                val path = uri?.let(StoragePathResolver::resolveTreeUri)
                if (ownerId != null && fieldId != null && path != null) {
                    selectedDirectoryOwnerId = ownerId
                    selectedDirectoryFieldId = fieldId
                    selectedDirectoryPath = path
                }
            }
            val mountLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val service = currentServiceState ?: return@rememberLauncherForActivityResult
                val outcome = service.mountTreePickerBridge.outcomeFrom(result.data)
                service.completeMountSelection(outcome)
                pendingMountRequest = null
                pendingMountDeclaration = null
            }

            val voiceSetupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) {
                currentServiceState?.refreshBootstrapPrerequisites()
            }

            val actions = remember(currentService, permissionLauncher, directoryLauncher, mountLauncher, providerDescriptors) {
                object : PttUiActions {
                    override fun requestPermissions() {
                        permissionLauncher.launch(RequiredPermissions.runtimePermissions())
                    }

                    override fun pickMount(request: dev.nilp0inter.subspace.ui.MountSelectionRequest) {
                        val service = currentServiceState ?: return
                        val descriptor = providerDescriptors.firstOrNull { it.implementationId == request.implementationId }
                            ?: return
                        val declaration = descriptor.resourceDeclarations.mounts.firstOrNull { it.id == request.declarationId }
                            ?: return
                        service.beginMountSelection(request, declaration)
                        pendingMountRequest = request
                        pendingMountDeclaration = declaration
                        val intent = service.mountTreePickerBridge.launchIntent(declaration)
                        mountLauncher.launch(intent)
                    }
                    override fun requestManageExternalStorage() {
                        startActivity(RequiredPermissions.manageExternalStorageIntent(this@MainActivity))
                    }

                    override fun pickDirectory(configurationOwnerId: String, fieldId: String) {
                        pendingDirectoryOwnerId = configurationOwnerId
                        pendingDirectoryFieldId = fieldId
                        directoryLauncher.launch(null)
                    }

                    override fun openBluetoothSettings() {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }

                    override fun scanForDevice() {
                        currentServiceState?.scanForDevice()
                    }

                    override fun pairTarget() {
                        currentServiceState?.pairTarget()
                    }

                    override fun refreshCarHfpConfiguration() {
                        currentServiceState?.refreshCarHfpConfiguration()
                    }

                    override fun selectCarHfpCandidate(selectionId: String) {
                        currentServiceState?.selectCarHfpCandidate(selectionId)
                    }

                    override fun connectSerial() {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, PttForegroundService::class.java)
                                .setAction(PttForegroundService.ACTION_START_MONITORING),
                        )
                        currentServiceState?.connectSerial()
                    }

                    override fun retry() {
                        currentServiceState?.refreshReadiness()
                    }

                    override fun disconnectSerial() {
                        currentServiceState?.disconnectSerial()
                    }

                    override fun setActiveChannel(id: String) {
                        currentServiceState?.selectChannel(id)
                    }

                    override fun setInputMode(mode: InputMode) {
                        currentServiceState?.setInputMode(mode)
                    }

                    override fun navigateToRsmSetup() {
                        dashboardRoute = if (currentReadyForMonitor) DashboardRoute.Monitor else DashboardRoute.Connection
                    }

                    override fun navigateToCarSetup() {
                        currentServiceState?.refreshCarHfpConfiguration()
                        dashboardRoute = DashboardRoute.CarConfiguration
                    }

                    override fun navigateToChannelConfiguration(channelId: String) {
                        configuredChannelId = channelId
                        creatingImplementationId = null
                        dashboardRoute = DashboardRoute.ChannelConfiguration
                    }

                    override fun navigateToChannelCreation(
                        implementationId: dev.nilp0inter.subspace.model.ChannelImplementationId,
                        displayName: String,
                    ) {
                        configuredChannelId = null
                        creatingImplementationId = implementationId.value
                        creatingDisplayName = displayName
                        dashboardRoute = DashboardRoute.ChannelCreation
                    }

                    override fun navigateBack() {
                        exitDashboardRoute(
                            isPackageManagement = dashboardRoute == DashboardRoute.PackageManagement,
                            cleanup = {
                                currentServiceState?.cleanupPackageManagementRouteExit()
                            },
                            setMainRoute = {
                                dashboardRoute = DashboardRoute.Main
                            },
                        )
                        configuredChannelId = null
                        creatingImplementationId = null
                    }

                    override fun navigateToLogAnalysis() {
                        dashboardRoute = DashboardRoute.LogAnalysis
                    }

                    override fun navigateToOpenAiProfiles() {
                        dashboardRoute = DashboardRoute.OpenAiProfiles
                    }

                    override fun navigateToPackageManagement() {
                        dashboardRoute = DashboardRoute.PackageManagement
                    }

                    override fun resolvePackageRepository(url: String) {
                        currentServiceState?.resolvePackageRepository(url)
                    }

                    override fun selectPackageRelease(releaseId: String) {
                        currentServiceState?.selectPackageRelease(releaseId)
                    }

                    override fun confirmPackageInstall(acknowledged: Boolean) {
                        currentServiceState?.confirmPackageInstall(acknowledged)
                    }

                    override fun rollbackPackage(repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity) {
                        currentServiceState?.rollbackPackage(repositoryId)
                    }

                    override fun removePackage(repositoryId: dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity) {
                        currentServiceState?.removePackage(repositoryId)
                    }

                    override fun cancelPackageInspection() {
                        currentServiceState?.cancelPackageInspection()
                    }


                    override fun refreshPackageManagement(url: String) {
                        currentServiceState?.refreshPackageManagement(url)
                    }

                    override fun phonePttPressed(channelId: String) {
                        currentServiceState?.startPhonePtt(channelId)
                    }

                    override fun phonePttReleased(channelId: String) {
                        currentServiceState?.phonePttReleased(channelId)
                    }

                    override fun createChannel(
                        implementationId: dev.nilp0inter.subspace.model.ChannelImplementationId,
                        displayName: String,
                        payload: OpaqueJsonObject,
                    ): String? = currentServiceState
                        ?.createChannel(implementationId, displayName, payload)
                        .failureMessage()

                    override fun updateChannelConfiguration(
                        channelId: String,
                        payload: OpaqueJsonObject,
                    ): String? = currentServiceState
                        ?.updateChannelConfiguration(channelId, payload)
                        .failureMessage()

                    override fun removeChannel(id: String) {
                        currentServiceState?.repository?.removeChannel(id)
                    }

                    override fun moveChannel(id: String, toIndex: Int) {
                        currentServiceState?.repository?.moveChannel(id, toIndex)
                    }

                    override fun renameChannel(id: String, newName: String) {
                        currentServiceState?.repository?.updateChannel(id) { definition ->
                            definition.copy(name = newName)
                        }
                    }
                    override fun createProfile(
                        request: dev.nilp0inter.subspace.ui.OpenAiProfileEditRequest,
                    ): OpenAiProfileUiMutationResult = currentServiceState?.createProfile(request)
                        ?: OpenAiProfileUiMutationResult.Failure(OpenAiProfileUiError.HostUnavailable)

                    override fun updateProfile(
                        request: dev.nilp0inter.subspace.ui.OpenAiProfileEditRequest,
                    ): OpenAiProfileUiMutationResult = currentServiceState?.updateProfile(request)
                        ?: OpenAiProfileUiMutationResult.Failure(OpenAiProfileUiError.HostUnavailable)

                    override fun deleteProfile(id: String): OpenAiProfileUiMutationResult =
                        currentServiceState?.deleteProfile(id)
                            ?: OpenAiProfileUiMutationResult.Failure(OpenAiProfileUiError.HostUnavailable)

                    override fun testProfile(id: String) {
                        currentServiceState?.testProfile(id)
                    }

                    override fun refreshProfile(id: String) {
                        currentServiceState?.refreshProfile(id)
                    }
                }
            }

            SubspaceTheme {
                Surface {
                    when (rootSurface) {
                        BootstrapRootSurface.Loading -> {
                            BackHandler(enabled = false) { }
                            BootstrapLoadingScreen(
                                state = bootstrapState,
                                modelProgress = modelProgress,
                                onRetry = { currentServiceState?.retryBootstrap() },
                            )
                        }

                        BootstrapRootSurface.Setup -> {
                            val setup = bootstrapState as BootstrapState.NeedsSetup
                            BackHandler(enabled = false) { }
                            val voiceIssue = setup.offlineNavigationVoiceIssue
                            val voiceSetupIntent = remember(voiceIssue) {
                                resolveVoiceSetupIntent(voiceIssue)
                            }
                            InitialSetupScreen(
                                missingPermissions = setup.missingPermissions,
                                needsManageExternalStorage = setup.needsManageExternalStorage,
                                invalidModelSets = setup.invalidModelSets,
                                error = setup.error,
                                offlineNavigationVoiceIssue = voiceIssue,
                                voiceSetupRequiresManualNavigation =
                                    voiceSetupIntent.action == Settings.ACTION_SETTINGS,
                                onGrantPermissions = {
                                    permissionLauncher.launch(RequiredPermissions.runtimePermissions())
                                },
                                onGrantManageExternalStorage = actions::requestManageExternalStorage,
                                onStartModelDownload = { currentServiceState?.startModelAcquisition() },
                                onResolveVoiceSetup = {
                                    voiceSetupLauncher.launch(voiceSetupIntent)
                                },
                            )
                        }

                        BootstrapRootSurface.Dashboard -> {
                            BackHandler(enabled = dashboardRoute != DashboardRoute.Main) { actions.navigateBack() }
                            when (dashboardRoute) {
                                DashboardRoute.Main -> MainDashboardScreen(
                                    appState = state,
                                    level = level,
                                    isCapturing = isCapturing,
                                    providerDescriptors = providerDescriptors,
                                    actions = actions,
                                )

                                DashboardRoute.Connection -> ConnectionScreen(state.connection, actions)
                                DashboardRoute.Monitor -> MonitorScreen(state.monitor, actions)
                                DashboardRoute.CarConfiguration -> CarHfpConfigurationScreen(
                                    state = state.carHfpConfiguration,
                                    actions = actions,
                                )
                                DashboardRoute.ChannelConfiguration -> {
                                    val definition = catalogue?.definitions?.firstOrNull { it.id == configuredChannelId }
                                    val descriptor = definition?.let { target ->
                                        providerDescriptors.firstOrNull {
                                            it.implementationId == target.implementationId
                                        }
                                    }
                                    if (definition != null && descriptor != null) {
                                        ChannelConfigurationScreen(
                                            title = definition.name,
                                            configurationOwnerId = definition.id,
                                            descriptor = descriptor,
                                            initialPayload = definition.configPayload,
                                            submitLabel = "Save configuration",
                                            onSubmit = { payload ->
                                                actions.updateChannelConfiguration(definition.id, payload).also { error ->
                                                    if (error == null) actions.navigateBack()
                                                }
                                            },
                                            choiceResolver = dynamicChoiceResolver,
                                            directorySelection = directorySelection,
                                            onPickDirectory = actions::pickDirectory,
                                            mountEntries = currentService?.mountEditorEntries(definition.id, definition.implementationId) ?: emptyList(),
                                            onPickMount = actions::pickMount,
                                            onBack = actions::navigateBack,
                                        )
                                    }
                                }

                                DashboardRoute.ChannelCreation -> {
                                    val descriptor = providerDescriptors.firstOrNull {
                                        it.implementationId.value == creatingImplementationId
                                    }
                                    if (descriptor != null) {
                                        ChannelConfigurationScreen(
                                            title = "New ${descriptor.presentation.label}",
                                            configurationOwnerId = "new:${descriptor.implementationId.value}:$creatingDisplayName",
                                            descriptor = descriptor,
                                            initialPayload = descriptor.configuration.defaultPayload(),
                                            submitLabel = "Create channel",
                                            onSubmit = { payload ->
                                                actions.createChannel(
                                                    descriptor.implementationId,
                                                    creatingDisplayName,
                                                    payload,
                                                ).also { error ->
                                                    if (error == null) actions.navigateBack()
                                                }
                                            },
                                            choiceResolver = dynamicChoiceResolver,
                                            directorySelection = directorySelection,
                                            onPickDirectory = actions::pickDirectory,
                                            mountEntries = emptyList(),
                                            onPickMount = actions::pickMount,
                                            onBack = actions::navigateBack,
                                        )
                                    }
                                }

                                DashboardRoute.LogAnalysis -> {
                                    LogAnalysisScreen(
                                        entries = logEntries,
                                        onClear = { currentServiceState?.clearLogs() },
                                        onSetGlobalLevel = { level ->
                                            currentServiceState?.setGlobalLogLevel(level)
                                        },
                                        onSetTagLevel = { tag, level ->
                                            currentServiceState?.setTagLogLevel(tag, level)
                                        },
                                        onClearTagLevel = { tag ->
                                            currentServiceState?.clearTagLogLevel(tag)
                                        },
                                        currentGlobalLevel = currentGlobalLevel,
                                        tagLevels = currentTagLevels,
                                    )
                                }

                                DashboardRoute.OpenAiProfiles -> OpenAiProfileManagementScreen(
                                    profiles = profileUiState,
                                    onSubmit = { request ->
                                        if (request.id == null) actions.createProfile(request)
                                        else actions.updateProfile(request)
                                    },
                                    onTest = actions::testProfile,
                                    onRefreshModels = actions::refreshProfile,
                                    onDelete = actions::deleteProfile,
                                    onBack = actions::navigateBack,
                                )

                                DashboardRoute.PackageManagement -> {
                                    PackageManagementScreen(
                                        summary = packageManagementSummary,
                                        actions = actions,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.startForegroundService(
            this,
            Intent(this, PttForegroundService::class.java)
                .setAction(PttForegroundService.ACTION_START_MONITORING),
        )
        bindService(
            Intent(this, PttForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onResume() {
        super.onResume()
        service?.refreshReadiness()
    }

    override fun onStop() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        service = null
        super.onStop()
    }

    private enum class DashboardRoute {
        Main,
        Connection,
        Monitor,
        CarConfiguration,
        ChannelConfiguration,
        ChannelCreation,
        LogAnalysis,
        OpenAiProfiles,
        PackageManagement,
    }

    private fun resolveVoiceSetupIntent(issue: OfflineNavigationVoiceIssue?): Intent {
        val enginePackage = issue?.enginePackage
        if (enginePackage != null) {
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                .setPackage(enginePackage)
            val handlers = packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            if (handlers.isNotEmpty()) {
                return installIntent
            }
        }
        return Intent(Settings.ACTION_SETTINGS)
    }
}

internal fun ChannelRepositoryMutationResult?.failureMessage(): String? = when (this) {
    null -> "Channel service is unavailable."
    ChannelRepositoryMutationResult.Success -> null
    is ChannelRepositoryMutationResult.Failure -> error.message
}