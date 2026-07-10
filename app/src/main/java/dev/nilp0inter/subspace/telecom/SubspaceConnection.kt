package dev.nilp0inter.subspace.telecom

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.util.Log
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import android.telecom.Connection
import android.telecom.DisconnectCause
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME

private const val TELECOM_CAPTURE_ROUTE_STABILITY_MS = 500L

internal fun isAcceptableTelecomCaptureRoute(
    route: Int,
    activeBluetoothDevicePresent: Boolean,
    activeBluetoothDeviceName: String?,
): Boolean =
    route == CallAudioState.ROUTE_BLUETOOTH &&
        activeBluetoothDevicePresent &&
        activeBluetoothDeviceName?.contains(TARGET_DEVICE_NAME, ignoreCase = true) != true

internal class TelecomCaptureRouteStabilizer(
    private val stabilityDelayMs: Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onRouteChanged: (Boolean) -> Unit,
) {
    private var pending = false
    private var stable = false
    private val publishStableRoute = Runnable {
        pending = false
        stable = true
        onRouteChanged(true)
    }

    fun routeChanged(acceptable: Boolean) {
        if (acceptable) {
            if (pending || stable) return
            pending = true
            postDelayed(publishStableRoute, stabilityDelayMs)
            return
        }

        if (pending) removeCallbacks(publishStableRoute)
        pending = false
        stable = false
        onRouteChanged(false)
    }

    fun cancel() {
        if (pending) removeCallbacks(publishStableRoute)
        pending = false
        stable = false
    }
}

internal class SubspaceConnection : Connection() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { TelecomCarPttCoordinator.checkRouteTimeout() }
    private val routeStabilizer = TelecomCaptureRouteStabilizer(
        stabilityDelayMs = TELECOM_CAPTURE_ROUTE_STABILITY_MS,
        postDelayed = handler::postDelayed,
        removeCallbacks = handler::removeCallbacks,
        onRouteChanged = TelecomCarPttCoordinator::onRouteChanged,
    )
    private var coordinatorDestroy = false
    private var coordinatorDisconnect = false

    init {
        audioModeIsVoip = true
        setInitialized()
        setActive()
        handler.postDelayed(timeout, TelecomCarPttLifecycle.DEFAULT_ROUTE_TIMEOUT_MS)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        routeStabilizer.routeChanged(isAcceptableCaptureRoute(state))
    }

    override fun onDisconnect() {
        handler.removeCallbacks(timeout)
        routeStabilizer.cancel()
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
        routeStabilizer.cancel()
        setDisconnected(DisconnectCause(DisconnectCause.ERROR, "No usable car call audio route"))
        destroyFromCoordinator()
    }

    fun destroyFromCoordinator() {
        coordinatorDestroy = true
        routeStabilizer.cancel()
        destroy()
    }

    private fun abortFromTelecom() {
        handler.removeCallbacks(timeout)
        routeStabilizer.cancel()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        TelecomCarPttCoordinator.onAbort()
    }

    @SuppressLint("MissingPermission")
    private fun isAcceptableCaptureRoute(state: CallAudioState): Boolean {
        val activeDevice = state.activeBluetoothDevice
        val name = runCatching { activeDevice?.name }.getOrNull()
        val bluetoothRoute = state.route == CallAudioState.ROUTE_BLUETOOTH
        val acceptable = isAcceptableTelecomCaptureRoute(
            route = state.route,
            activeBluetoothDevicePresent = activeDevice != null,
            activeBluetoothDeviceName = name,
        )
        val displayName = name?.let { "'$it'" } ?: "none"
        Log.d(
            ROUTE_LOG_TAG,
            "TELECOM_CALL_AUDIO route=${state.route} supported=${state.supportedRouteMask} " +
                "activeBtName=$displayName bluetoothRoute=$bluetoothRoute acceptable=$acceptable",
        )
        return acceptable
    }
}
