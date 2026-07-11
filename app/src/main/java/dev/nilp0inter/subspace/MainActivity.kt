package dev.nilp0inter.subspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.provider.Settings
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
import dev.nilp0inter.subspace.service.PttForegroundService
import dev.nilp0inter.subspace.service.RequiredPermissions
import dev.nilp0inter.subspace.ui.BootstrapLoadingScreen
import dev.nilp0inter.subspace.ui.BootstrapRootSurface
import dev.nilp0inter.subspace.ui.ChannelConfigurationScreen
import dev.nilp0inter.subspace.ui.ConnectionScreen
import dev.nilp0inter.subspace.ui.DirectorySelection
import dev.nilp0inter.subspace.ui.InitialSetupScreen
import dev.nilp0inter.subspace.ui.MainDashboardScreen
import dev.nilp0inter.subspace.ui.MonitorScreen
import dev.nilp0inter.subspace.ui.LogAnalysisScreen
import dev.nilp0inter.subspace.ui.PttUiActions
import dev.nilp0inter.subspace.ui.bootstrapRootSurface
import dev.nilp0inter.subspace.ui.theme.SubspaceTheme

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
            val logEntries by currentService?.logEntries?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyList()) }
            val currentGlobalLevel by currentService?.globalLogLevelFlow?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(dev.nilp0inter.subspace.service.LogLevel.Debug) }
            val currentTagLevels by currentService?.tagLogLevelsFlow?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyMap()) }

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

            val actions = remember(currentService, permissionLauncher, directoryLauncher) {
                object : PttUiActions {
                    override fun requestPermissions() {
                        permissionLauncher.launch(RequiredPermissions.runtimePermissions())
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
                        configuredChannelId = null
                        creatingImplementationId = null
                        dashboardRoute = DashboardRoute.Main
                    }

                    override fun navigateToLogAnalysis() {
                        dashboardRoute = DashboardRoute.LogAnalysis
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
                        payload: dev.nilp0inter.subspace.model.OpaqueJsonObject,
                    ): String? = currentServiceState
                        ?.createChannel(implementationId, displayName, payload)
                        .failureMessage()

                    override fun updateChannelConfiguration(
                        channelId: String,
                        payload: dev.nilp0inter.subspace.model.OpaqueJsonObject,
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
                            InitialSetupScreen(
                                missingPermissions = setup.missingPermissions,
                                needsManageExternalStorage = setup.needsManageExternalStorage,
                                invalidModelSets = setup.invalidModelSets,
                                error = setup.error,
                                onGrantPermissions = {
                                    permissionLauncher.launch(RequiredPermissions.runtimePermissions())
                                },
                                onGrantManageExternalStorage = actions::requestManageExternalStorage,
                                onStartModelDownload = { currentServiceState?.startModelAcquisition() },
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
                                            directorySelection = directorySelection,
                                            onPickDirectory = actions::pickDirectory,
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
                                            directorySelection = directorySelection,
                                            onPickDirectory = actions::pickDirectory,
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

    private enum class DashboardRoute { Main, Connection, Monitor, ChannelConfiguration, ChannelCreation, LogAnalysis }
}

internal fun ChannelRepositoryMutationResult?.failureMessage(): String? = when (this) {
    null -> "Channel service is unavailable."
    ChannelRepositoryMutationResult.Success -> null
    is ChannelRepositoryMutationResult.Failure -> error.message
}