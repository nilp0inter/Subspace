package dev.nilp0inter.subspace.telecom

import android.os.SystemClock

internal object TelecomCarPttCoordinator {
    private var listener: Listener? = null
    private var connection: SubspaceConnection? = null
    private val lifecycle: TelecomCarPttLifecycle = TelecomCarPttLifecycle(object : TelecomCarPttLifecycle.Callbacks {
        override fun onCaptureStart() {
            listener?.onTelecomCaptureStart()
        }

        override fun onCaptureStop() {
            listener?.onTelecomCaptureStop()
        }

        override fun onRouteTimeout() {
            listener?.onTelecomRouteTimeout()
            connection?.disconnectFromCoordinator()
            connection = null
            releaseLifecycleAfterTeardown()
        }

        override fun onDisconnected() {
            connection?.destroyFromCoordinator()
            connection = null
            listener?.onTelecomConnectionEnded()
            releaseLifecycleAfterTeardown()
        }

        override fun onAborted() {
            connection?.destroyFromCoordinator()
            connection = null
            listener?.onTelecomConnectionEnded()
            releaseLifecycleAfterTeardown()
        }
    })

    private fun releaseLifecycleAfterTeardown() {
        lifecycle.releaseAfterTeardown()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun attachConnection(connection: SubspaceConnection): Boolean {
        if (!lifecycle.startRequest(SystemClock.elapsedRealtime())) return false
        this.connection = connection
        return true
    }

    fun onRouteChanged(acceptable: Boolean) {
        lifecycle.routeChanged(acceptable)
    }

    fun isCaptureRouteReady(): Boolean =
        lifecycle.currentState == TelecomCarPttLifecycle.State.Recording

    fun checkRouteTimeout() {
        lifecycle.checkTimeout(SystemClock.elapsedRealtime())
    }

    fun onDisconnect() {
        lifecycle.disconnect()
    }

    fun onAbort() {
        lifecycle.abort()
    }

    fun forceAbort() {
        lifecycle.abort()
    }

    interface Listener {
        fun onTelecomCaptureStart()
        fun onTelecomCaptureStop()
        fun onTelecomRouteTimeout()
        fun onTelecomConnectionEnded()
    }
}
