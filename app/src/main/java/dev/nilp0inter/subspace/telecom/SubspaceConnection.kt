package dev.nilp0inter.subspace.telecom

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME

internal class SubspaceConnection : Connection() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { TelecomCarPttCoordinator.checkRouteTimeout() }
    private var coordinatorDestroy = false
    private var coordinatorDisconnect = false

    init {
        audioModeIsVoip = true
        setInitialized()
        setActive()
        handler.postDelayed(timeout, TelecomCarPttLifecycle.DEFAULT_ROUTE_TIMEOUT_MS)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        TelecomCarPttCoordinator.onRouteChanged(isAcceptableCaptureRoute(state))
    }

    override fun onDisconnect() {
        handler.removeCallbacks(timeout)
        if (!coordinatorDisconnect) {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            TelecomCarPttCoordinator.onDisconnect()
        }
    }

    override fun onAbort() {
        abortFromTelecom()
    }

    override fun onReject() {
        abortFromTelecom()
    }

    override fun onPlayDtmfTone(c: Char) {
        super.onPlayDtmfTone(c)
    }

    override fun onHold() {
        super.onHold()
    }

    override fun onUnhold() {
        super.onUnhold()
    }

    override fun onAnswer() {
        super.onAnswer()
    }

    override fun onAnswer(videoState: Int) {
        super.onAnswer(videoState)
    }

    override fun onSilence() {
        super.onSilence()
    }

    fun disconnectFromCoordinator() {
        coordinatorDisconnect = true
        handler.removeCallbacks(timeout)
        setDisconnected(DisconnectCause(DisconnectCause.ERROR, "No usable car call audio route"))
        destroyFromCoordinator()
    }

    fun destroyFromCoordinator() {
        coordinatorDestroy = true
        destroy()
    }

    private fun abortFromTelecom() {
        handler.removeCallbacks(timeout)
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        TelecomCarPttCoordinator.onAbort()
    }

    @SuppressLint("MissingPermission")
    private fun isAcceptableCaptureRoute(state: CallAudioState): Boolean {
        val activeDevice = state.activeBluetoothDevice ?: return false
        val bluetoothRoute = state.route == CallAudioState.ROUTE_BLUETOOTH ||
            (state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
        if (!bluetoothRoute) return false

        val name = runCatching { activeDevice.name }.getOrNull()
        return name?.contains(TARGET_DEVICE_NAME, ignoreCase = true) != true
    }
}
