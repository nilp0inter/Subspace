package dev.nilp0inter.subspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import dev.nilp0inter.subspace.channel.StoragePathResolver
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.BootstrapState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.service.PttForegroundService
import dev.nilp0inter.subspace.service.RequiredPermissions
import dev.nilp0inter.subspace.ui.BootstrapLoadingScreen
import dev.nilp0inter.subspace.ui.BootstrapRootSurface
import dev.nilp0inter.subspace.ui.bootstrapRootSurface
import dev.nilp0inter.subspace.ui.ConnectionScreen
import dev.nilp0inter.subspace.ui.DebugChannelConfigScreen
import dev.nilp0inter.subspace.ui.InitialSetupScreen
import dev.nilp0inter.subspace.ui.MainDashboardScreen
import dev.nilp0inter.subspace.ui.MonitorScreen
import dev.nilp0inter.subspace.ui.PttUiActions
import dev.nilp0inter.subspace.ui.theme.SubspaceTheme

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<PttForegroundService?>(null)
    private var bound = false
    private var pendingJournalDirectory: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PttForegroundService.LocalBinder).service().also { connectedService ->
                pendingJournalDirectory?.let { path ->
                    connectedService.setJournalDirectory(path)
                    pendingJournalDirectory = null
                }
                connectedService.refreshReadiness()
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
            val level by currentService?.level?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(0f) }
            val isCapturing by currentService?.isCapturing?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(false) }

            // Bootstrap state from the service — the authoritative source
            // for loading, setup, recovery, and dashboard routing.
            val bootstrapState by currentService?.bootstrapState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(BootstrapState.ConnectingService) }
            val modelProgress by currentService?.modelAcquisitionProgress
                ?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(dev.nilp0inter.subspace.model.ModelAcquisitionProgress()) }

            var dashboardRoute by remember { mutableStateOf(DashboardRoute.Main) }
            val scope = rememberCoroutineScope()

            // Derive the root surface from bootstrap state.
            val rootSurface = bootstrapRootSurface(bootstrapState)

            val currentReadyForMonitor by rememberUpdatedState(state.readyForMonitor)
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                // Route permission results to prerequisite refresh.
                currentServiceState?.refreshBootstrapPrerequisites()
            }
            val directoryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val path = uri?.let(StoragePathResolver::resolveTreeUri)
                if (path != null) {
                    val connectedService = currentServiceState
                    if (connectedService == null) {
                        pendingJournalDirectory = path
                    } else {
                        connectedService.setJournalDirectory(path)
                    }
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

                    override fun pickJournalDirectory() {
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

                    override fun setJournalSaveVoice(enabled: Boolean) {
                        currentServiceState?.setJournalSaveVoice(enabled)
                    }

                    override fun setJournalSaveText(enabled: Boolean) {
                        currentServiceState?.setJournalSaveText(enabled)
                    }

                    override fun setActiveChannel(id: String) {
                        currentServiceState?.setActiveChannelId(id)
                    }

                    override fun setInputMode(mode: InputMode) {
                        currentServiceState?.setInputMode(mode)
                    }

                    override fun setDebugChannelMode(mode: dev.nilp0inter.subspace.model.DebugMode) {
                        currentServiceState?.setDebugChannelMode(mode)
                    }

                    override fun setKeyboardHostProfile(profile: io.sleepwalker.core.keymap.HostProfile) {
                        currentServiceState?.setKeyboardHostProfile(profile)
                    }

                    override fun connectKeyboardBridge() {
                        currentServiceState?.connectKeyboardBridge()
                    }

                    override fun disconnectKeyboardBridge() {
                        currentServiceState?.disconnectKeyboardBridge()
                    }

                    override fun navigateToRsmSetup() {
                        dashboardRoute = if (currentReadyForMonitor) DashboardRoute.Monitor else DashboardRoute.Connection
                    }

                    override fun navigateToJournalConfig() {
                        dashboardRoute = DashboardRoute.JournalConfig
                    }

                    override fun navigateToDebugConfig() {
                        dashboardRoute = DashboardRoute.DebugChannelConfig
                    }

                    override fun navigateToKeyboardConfig() {
                        dashboardRoute = DashboardRoute.KeyboardConfig
                    }

                    override fun navigateBack() {
                        dashboardRoute = DashboardRoute.Main
                    }

                    override fun setTtsText(text: String) {
                        currentServiceState?.setTtsText(text)
                    }

                    override fun setTtsVoiceStyle(style: String) {
                        currentServiceState?.setTtsVoiceStyle(style)
                    }

                    override fun setTtsLang(lang: String) {
                        currentServiceState?.setTtsLang(lang)
                    }

                    override fun setTtsTotalSteps(steps: Int) {
                        currentServiceState?.setTtsTotalSteps(steps)
                    }

                    override fun setTtsSpeed(speed: Float) {
                        currentServiceState?.setTtsSpeed(speed)
                    }

                    override fun requestTtsSynthesis() {
                        currentServiceState?.requestTtsSynthesis()
                    }

                    override fun setSttTtsVoiceStyle(style: String) {
                        currentServiceState?.setSttTtsVoiceStyle(style)
                    }

                    override fun setSttTtsLang(lang: String) {
                        currentServiceState?.setSttTtsLang(lang)
                    }

                    override fun setSttTtsTotalSteps(steps: Int) {
                        currentServiceState?.setSttTtsTotalSteps(steps)
                    }

                    override fun setSttTtsSpeed(speed: Float) {
                        currentServiceState?.setSttTtsSpeed(speed)
                    }

                    override fun phonePttPressed(channelId: String) {
                        currentServiceState?.phonePttPressed(channelId)
                    }

                    override fun phonePttReleased(channelId: String) {
                        currentServiceState?.phonePttReleased(channelId)
                    }
                }
            }

            SubspaceTheme {
                Surface {
                    when (rootSurface) {
                        BootstrapRootSurface.Loading -> {
                            val needsSetup = bootstrapState is BootstrapState.NeedsSetup
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
                                invalidModelSets = setup.invalidModelSets,
                                error = setup.error,
                                onGrantPermissions = {
                                    permissionLauncher.launch(RequiredPermissions.runtimePermissions())
                                },
                                onStartModelDownload = {
                                    // Move to loading: the coordinator handles
                                    // acquisition and returns to setup only on
                                    // failure.
                                    currentServiceState?.startModelAcquisition()
                                },
                            )
                        }

                        BootstrapRootSurface.Dashboard -> {
                            BackHandler(enabled = dashboardRoute != DashboardRoute.Main) {
                                dashboardRoute = DashboardRoute.Main
                            }

                            when (dashboardRoute) {
                                DashboardRoute.Main -> MainDashboardScreen(
                                    appState = state,
                                    level = level,
                                    isCapturing = isCapturing,
                                    actions = actions,
                                )

                                DashboardRoute.Connection -> ConnectionScreen(state.connection, actions)
                                DashboardRoute.Monitor -> MonitorScreen(state.monitor, actions)
                                DashboardRoute.JournalConfig -> dev.nilp0inter.subspace.ui.JournalConfigScreen(
                                    channel = state.journal,
                                    actions = actions,
                                    onBack = { dashboardRoute = DashboardRoute.Main },
                                )
                                DashboardRoute.DebugChannelConfig -> dev.nilp0inter.subspace.ui.DebugChannelConfigScreen(
                                    channel = state.debugChannel,
                                    monitorState = state.monitor,
                                    actions = actions,
                                    onBack = { dashboardRoute = DashboardRoute.Main },
                                )
                                DashboardRoute.KeyboardConfig -> dev.nilp0inter.subspace.ui.KeyboardChannelConfigScreen(
                                    channel = state.keyboard,
                                    monitorState = state.monitor,
                                    actions = actions,
                                    keymapProfiles = service?.getKeymapProfiles() ?: emptyList(),
                                    onBack = { dashboardRoute = DashboardRoute.Main },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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

    private enum class DashboardRoute { Main, Connection, Monitor, JournalConfig, DebugChannelConfig, KeyboardConfig }
}