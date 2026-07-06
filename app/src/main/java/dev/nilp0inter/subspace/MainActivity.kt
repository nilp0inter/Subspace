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
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.service.PttForegroundService
import dev.nilp0inter.subspace.service.RequiredPermissions
import dev.nilp0inter.subspace.ui.ConnectionScreen
import dev.nilp0inter.subspace.ui.DebugChannelConfigScreen
import dev.nilp0inter.subspace.ui.MainDashboardScreen
import dev.nilp0inter.subspace.ui.MainViewModel
import dev.nilp0inter.subspace.ui.MonitorScreen
import dev.nilp0inter.subspace.ui.PttUiActions
import dev.nilp0inter.subspace.ui.theme.SubspaceTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var bound = false
    private var pendingJournalDirectory: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = (binder as PttForegroundService.LocalBinder).service().also { connectedService ->
                pendingJournalDirectory?.let { path ->
                    connectedService.setJournalDirectory(path)
                    pendingJournalDirectory = null
                }
                connectedService.refreshReadiness()
            }
            viewModel.onServiceConnected(connectedService)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            viewModel.onServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.appState.collectAsStateWithLifecycle()
            val level by viewModel.level.collectAsStateWithLifecycle()
            val isCapturing by viewModel.isCapturing.collectAsStateWithLifecycle()
            var route by remember { mutableStateOf(MainRoute.Dashboard) }
            val currentReadyForMonitor by rememberUpdatedState(state.readyForMonitor)
            
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                viewModel.service?.refreshReadiness()
            }
            val directoryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val path = uri?.let(StoragePathResolver::resolveTreeUri)
                if (path != null) {
                    val connectedService = viewModel.service
                    if (connectedService == null) {
                        pendingJournalDirectory = path
                    } else {
                        connectedService.setJournalDirectory(path)
                    }
                }
            }

            val actions = remember(permissionLauncher, directoryLauncher) {
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
                        viewModel.service?.scanForDevice()
                    }

                    override fun pairTarget() {
                        viewModel.service?.pairTarget()
                    }

                    override fun connectSerial() {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, PttForegroundService::class.java)
                                .setAction(PttForegroundService.ACTION_START_MONITORING),
                        )
                        viewModel.service?.connectSerial()
                    }

                    override fun retry() {
                        viewModel.service?.refreshReadiness()
                    }

                    override fun disconnectSerial() {
                        viewModel.service?.disconnectSerial()
                    }

                    override fun setJournalSaveVoice(enabled: Boolean) {
                        viewModel.service?.setJournalSaveVoice(enabled)
                    }

                    override fun setJournalSaveText(enabled: Boolean) {
                        viewModel.service?.setJournalSaveText(enabled)
                    }

                    override fun setActiveChannel(id: String) {
                        viewModel.service?.setActiveChannelId(id)
                    }

                    override fun setInputMode(mode: InputMode) {
                        viewModel.service?.setInputMode(mode)
                    }

                    override fun setDebugChannelMode(mode: dev.nilp0inter.subspace.model.DebugMode) {
                        viewModel.service?.setDebugChannelMode(mode)
                    }

                    override fun setKeyboardHostProfile(profile: io.sleepwalker.core.keymap.HostProfile) {
                        viewModel.service?.setKeyboardHostProfile(profile)
                    }

                    override fun connectKeyboardBridge() {
                        viewModel.service?.connectKeyboardBridge()
                    }

                    override fun disconnectKeyboardBridge() {
                        viewModel.service?.disconnectKeyboardBridge()
                    }

                    override fun navigateToRsmSetup() {
                        route = if (currentReadyForMonitor) MainRoute.Monitor else MainRoute.Connection
                    }

                    override fun navigateToJournalConfig() {
                        route = MainRoute.Dashboard // Fallback, handled below
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
                        viewModel.service?.setTtsText(text)
                    }

                    override fun setTtsVoiceStyle(style: String) {
                        viewModel.service?.setTtsVoiceStyle(style)
                    }

                    override fun setTtsLang(lang: String) {
                        viewModel.service?.setTtsLang(lang)
                    }

                    override fun setTtsTotalSteps(steps: Int) {
                        viewModel.service?.setTtsTotalSteps(steps)
                    }

                    override fun setTtsSpeed(speed: Float) {
                        viewModel.service?.setTtsSpeed(speed)
                    }

                    override fun requestTtsSynthesis() {
                        viewModel.service?.requestTtsSynthesis()
                    }

                    override fun setSttTtsVoiceStyle(style: String) {
                        viewModel.service?.setSttTtsVoiceStyle(style)
                    }

                    override fun setSttTtsLang(lang: String) {
                        viewModel.service?.setSttTtsLang(lang)
                    }

                    override fun setSttTtsTotalSteps(steps: Int) {
                        viewModel.service?.setSttTtsTotalSteps(steps)
                    }

                    override fun setSttTtsSpeed(speed: Float) {
                        viewModel.service?.setSttTtsSpeed(speed)
                    }

                    override fun phonePttPressed(channelId: String) {
                        viewModel.service?.phonePttPressed(channelId)
                    }

                    override fun phonePttReleased(channelId: String) {
                        viewModel.service?.phonePttReleased(channelId)
                    }
                }
            }

            SubspaceTheme {
                Surface {
                    BackHandler(enabled = route != MainRoute.Dashboard) {
                        route = MainRoute.Dashboard
                    }

                    when (route) {
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
        viewModel.service?.refreshReadiness()
    }

    override fun onStop() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        viewModel.onServiceDisconnected()
        super.onStop()
    }

    private enum class MainRoute { Dashboard, Connection, Monitor, JournalConfig, DebugChannelConfig, KeyboardConfig }
}
