package dev.nilp0inter.subspace.telecom

import android.os.SystemClock

internal object TelecomCarPttCoordinator {
    @Volatile private var listener: Listener? = null
    @Volatile private var connection: SubspaceConnection? = null
    
    private val lifecycle: TelecomCarPttLifecycle = TelecomCarPttLifecycle(object : TelecomCarPttLifecycle.Callbacks {
        override fun onCaptureStart() {
            synchronized(TelecomCarPttCoordinator) {
                listener?.onTelecomCaptureStart()
            }
        }

        override fun onCaptureStop() {
            synchronized(TelecomCarPttCoordinator) {
                listener?.onTelecomCaptureStop()
            }
        }

        override fun onRouteTimeout() {
            synchronized(TelecomCarPttCoordinator) {
                listener?.onTelecomRouteTimeout()
                connection?.disconnectFromCoordinator()
            }
        }

        override fun onDisconnected() {
            synchronized(TelecomCarPttCoordinator) {
                connection?.destroyFromCoordinator()
                connection = null
                listener?.onTelecomConnectionEnded()
                releaseLifecycleAfterTeardown()
            }
        }

        override fun onAborted() {
            synchronized(TelecomCarPttCoordinator) {
                connection?.destroyFromCoordinator()
                connection = null
                listener?.onTelecomConnectionEnded()
                releaseLifecycleAfterTeardown()
            }
        }
    })

    private fun releaseLifecycleAfterTeardown() {
        lifecycle.releaseAfterTeardown()
    }

    @Synchronized
    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    @Synchronized
    fun attachConnection(connection: SubspaceConnection): Boolean {
        if (!lifecycle.startRequest(SystemClock.elapsedRealtime())) return false
        this.connection = connection
        return true
    }

    @Synchronized
    fun onRouteChanged(acceptable: Boolean) {
        lifecycle.routeChanged(acceptable)
    }

    @Synchronized
    fun isCaptureRouteReady(): Boolean =
        lifecycle.currentState == TelecomCarPttLifecycle.State.Recording

    @Synchronized
    fun checkRouteTimeout() {
        lifecycle.checkTimeout(SystemClock.elapsedRealtime())
    }

    @Synchronized
    fun onDisconnect() {
        lifecycle.disconnect()
    }

    @Synchronized
    fun onAbort() {
        lifecycle.abort()
    }

    @Synchronized
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
