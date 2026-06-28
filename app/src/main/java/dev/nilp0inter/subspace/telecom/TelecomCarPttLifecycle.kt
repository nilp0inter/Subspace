package dev.nilp0inter.subspace.telecom

internal class TelecomCarPttLifecycle(
    private val callbacks: Callbacks,
    private val routeTimeoutMs: Long = DEFAULT_ROUTE_TIMEOUT_MS,
) {
    private var state: State = State.Idle
    private var deadlineAtMs: Long = 0L

    val currentState: State
        get() = state

    fun startRequest(nowMs: Long): Boolean {
        if (state != State.Idle) return false
        state = State.WaitingForRoute
        deadlineAtMs = nowMs + routeTimeoutMs
        return true
    }

    fun routeChanged(acceptable: Boolean) {
        if (state != State.WaitingForRoute || !acceptable) return
        state = State.Recording
        callbacks.onCaptureStart()
    }

    fun checkTimeout(nowMs: Long) {
        if (state == State.WaitingForRoute && nowMs >= deadlineAtMs) {
            state = State.Released
            callbacks.onRouteTimeout()
        }
    }

    fun disconnect() {
        val previous = state
        if (previous == State.Idle || previous == State.Released) return
        state = State.Released
        if (previous == State.Recording) callbacks.onCaptureStop()
        callbacks.onDisconnected()
    }

    fun abort() {
        val previous = state
        if (previous == State.Idle || previous == State.Released) return
        state = State.Released
        if (previous == State.Recording) callbacks.onCaptureStop()
        callbacks.onAborted()
    }

    fun releaseAfterTeardown() {
        state = State.Idle
    }

    enum class State { Idle, WaitingForRoute, Recording, Released }

    interface Callbacks {
        fun onCaptureStart()
        fun onCaptureStop()
        fun onRouteTimeout()
        fun onDisconnected()
        fun onAborted()
    }

    companion object {
        const val DEFAULT_ROUTE_TIMEOUT_MS = 8_000L
    }
}