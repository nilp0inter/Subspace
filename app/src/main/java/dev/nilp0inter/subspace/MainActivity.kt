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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.SetupState
import dev.nilp0inter.subspace.audio.ModelDownloader
import dev.nilp0inter.subspace.audio.ModelVerifier
import dev.nilp0inter.subspace.service.PttForegroundService
import dev.nilp0inter.subspace.service.RequiredPermissions
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
            var route by remember { mutableStateOf(MainRoute.Setup) }
            var setupState by remember { mutableStateOf(SetupState()) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                val ctx = this@MainActivity
                val permissionsDone = withContext(Dispatchers.IO) {
                    RequiredPermissions.missing(ctx).isEmpty()
                }
                val modelsDone = withContext(Dispatchers.IO) {
                    runCatching { ModelVerifier.isComplete(ctx) }.getOrDefault(false)
                }
                setupState = setupState.copy(
                    permissionsDone = permissionsDone,
                    modelsDone = modelsDone,
                )
                if (permissionsDone && modelsDone) {
                    route = MainRoute.Dashboard
                }
            }
            val currentReadyForMonitor by rememberUpdatedState(state.readyForMonitor)
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                currentServiceState?.refreshReadiness()
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
                        route = if (currentReadyForMonitor) MainRoute.Monitor else MainRoute.Connection
                    }

                    override fun navigateToJournalConfig() {
                        route = MainRoute.JournalConfig
                    }

                    override fun navigateToDebugConfig() {
                        route = MainRoute.DebugChannelConfig
                    }

                    override fun navigateToKeyboardConfig() {
                        route = MainRoute.KeyboardConfig
                    }

                    override fun navigateBack() {
                        route = MainRoute.Dashboard
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
                    BackHandler(enabled = route != MainRoute.Dashboard && route != MainRoute.Setup) {
                        route = MainRoute.Dashboard
                    }

                    when (route) {
                        MainRoute.Setup -> InitialSetupScreen(
                            state = setupState,
                            onGrantPermissions = {
                                val missing = RequiredPermissions.missing(this@MainActivity)
                                setupState = setupState.copy(permissionsDone = missing.isEmpty())
                            },
                            onStartModelDownload = {
                                scope.launch {
                                    setupState = setupState.copy(downloading = true, error = null)
                                    try {
                                        val ctx = this@MainActivity
                                        withContext(Dispatchers.IO) {
                                            ModelDownloader.ensure(ctx, ModelVerifier.PARAKEET_DIR) { p ->
                                                setupState = setupState.copy(parakeetProgress = p)
                                            }
                                            ModelDownloader.ensure(ctx, ModelVerifier.SUPERTONIC_DIR) { p ->
                                                setupState = setupState.copy(supertonicProgress = p)
                                            }
                                        }
                                        setupState = setupState.copy(downloading = false, modelsDone = true)
                                    } catch (e: Exception) {
                                        setupState = setupState.copy(
                                            downloading = false,
                                            error = e.message ?: "Download failed",
                                        )
                                    }
                                }
                            },
                            onEnterSubspace = { route = MainRoute.Dashboard },
                        )

                        MainRoute.Dashboard -> MainDashboardScreen(
                            appState = state,
                            level = level,
                            isCapturing = isCapturing,
                            actions = actions,
                        )

                        MainRoute.Connection -> ConnectionScreen(state.connection, actions)
                        MainRoute.Monitor -> MonitorScreen(state.monitor, actions)
                        MainRoute.JournalConfig -> dev.nilp0inter.subspace.ui.JournalConfigScreen(
                            channel = state.journal,
                            actions = actions,
                            onBack = { route = MainRoute.Dashboard },
                        )
                        MainRoute.DebugChannelConfig -> dev.nilp0inter.subspace.ui.DebugChannelConfigScreen(
                            channel = state.debugChannel,
                            monitorState = state.monitor,
                            actions = actions,
                            onBack = { route = MainRoute.Dashboard },
                        )
                        MainRoute.KeyboardConfig -> dev.nilp0inter.subspace.ui.KeyboardChannelConfigScreen(
                            channel = state.keyboard,
                            monitorState = state.monitor,
                            actions = actions,
                            keymapProfiles = service?.getKeymapProfiles() ?: emptyList(),
                            onBack = { route = MainRoute.Dashboard },
                        )
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

    private enum class MainRoute { Setup, Dashboard, Connection, Monitor, JournalConfig, DebugChannelConfig, KeyboardConfig }
}
