package dev.nilp0inter.subspace.service

internal class VirtualPttAdapter(
    private val press: () -> Boolean,
    private val release: () -> Unit,
    private val onStateChanged: (VirtualPttState) -> Unit = {},
) {
    var state: VirtualPttState = VirtualPttState.Released
        private set

    fun toggle() {
        if (state == VirtualPttState.Pressed) {
            releaseIfPressed()
            return
        }

        if (press()) {
            state = VirtualPttState.Pressed
            onStateChanged(state)
        } else {
            state = VirtualPttState.Released
            onStateChanged(state)
        }
    }

    fun releaseIfPressed() {
        if (state != VirtualPttState.Pressed) return
        state = VirtualPttState.Finalizing
        onStateChanged(state)
        release()
        state = VirtualPttState.Released
        onStateChanged(state)
    }

    fun forceRelease() {
        releaseIfPressed()
    }

}

internal enum class VirtualPttState { Released, Pressed, Finalizing }
