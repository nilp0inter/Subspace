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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.EchoTimingMode
import dev.nilp0inter.subspace.service.PttForegroundService
import dev.nilp0inter.subspace.service.RequiredPermissions
import dev.nilp0inter.subspace.ui.ConnectionScreen
import dev.nilp0inter.subspace.ui.MainDashboardScreen
import dev.nilp0inter.subspace.ui.MonitorScreen
import dev.nilp0inter.subspace.ui.PttUiActions
import dev.nilp0inter.subspace.ui.theme.SubspaceTheme

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<PttForegroundService?>(null)
    private var bound = false
    private var pendingCaptainsLogDirectory: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PttForegroundService.LocalBinder).service().also { connectedService ->
                pendingCaptainsLogDirectory?.let { path ->
                    connectedService.setCaptainsLogDirectory(path)
                    pendingCaptainsLogDirectory = null
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
            var route by remember { mutableStateOf(MainRoute.Dashboard) }
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
                        pendingCaptainsLogDirectory = path
                    } else {
                        connectedService.setCaptainsLogDirectory(path)
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

                    override fun pickCaptainsLogDirectory() {
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

                    override fun setEchoEnabled(enabled: Boolean) {
                        currentServiceState?.setEchoEnabled(enabled)
                    }

                    override fun setEchoTimingMode(mode: EchoTimingMode) {
                        currentServiceState?.setEchoTimingMode(mode)
                    }

                    override fun setSttEnabled(enabled: Boolean) {
                        currentServiceState?.setSttEnabled(enabled)
                    }

                    override fun setTtsEnabled(enabled: Boolean) {
                        currentServiceState?.setTtsEnabled(enabled)
                    }

                    override fun setSttTtsEnabled(enabled: Boolean) {
                        currentServiceState?.setSttTtsEnabled(enabled)
                    }

                    override fun setCaptainsLogSaveVoice(enabled: Boolean) {
                        currentServiceState?.setCaptainsLogSaveVoice(enabled)
                    }

                    override fun setCaptainsLogSaveText(enabled: Boolean) {
                        currentServiceState?.setCaptainsLogSaveText(enabled)
                    }

                    override fun setCaptainsLogEnabled(enabled: Boolean) {
                        currentServiceState?.setCaptainsLogEnabled(enabled)
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
                }
            }

            SubspaceTheme {
                Surface {
                    BackHandler(enabled = route != MainRoute.Dashboard) {
                        route = MainRoute.Dashboard
                    }

                    when (route) {
                        MainRoute.Dashboard -> MainDashboardScreen(
                            connected = state.readyForMonitor,
                            captainsLog = state.captainsLog,
                            testModeActive = state.monitor.echoEnabled || state.monitor.sttEnabled ||
                                state.monitor.ttsEnabled || state.monitor.sttTtsEnabled,
                            actions = actions,
                            onConnectionClick = {
                                route = if (state.readyForMonitor) MainRoute.Monitor else MainRoute.Connection
                            },
                        )

                        MainRoute.Connection -> ConnectionScreen(state.connection, actions)
                        MainRoute.Monitor -> MonitorScreen(state.monitor, actions)
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

    private enum class MainRoute { Dashboard, Connection, Monitor }
}
