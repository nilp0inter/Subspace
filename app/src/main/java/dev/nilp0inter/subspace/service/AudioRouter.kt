package dev.nilp0inter.subspace.service

class AudioRouter(
    private val channelRoute: AudioRoute,
    private val testRoutes: Map<AudioTestMode, AudioRoute>,
) {
    fun setChannelEnabled(enabled: Boolean) {
        if (enabled) {
            testRoutes.values.forEach { route ->
                if (route.enabled()) {
                    route.setEnabled(false)
                    route.cancel()
                }
            }
        } else {
            channelRoute.cancel()
        }
        channelRoute.setEnabled(enabled)
    }

    fun setTestModeEnabled(mode: AudioTestMode, enabled: Boolean) {
        val target = testRoutes[mode] ?: return
        if (enabled) {
            if (channelRoute.enabled()) {
                channelRoute.setEnabled(false)
                channelRoute.cancel()
            }
            testRoutes.forEach { (otherMode, route) ->
                if (otherMode != mode && route.enabled()) {
                    route.setEnabled(false)
                    route.cancel()
                }
            }
        } else {
            target.cancel()
        }
        target.setEnabled(enabled)
    }

    fun onPttPressed() {
        activeRoute()?.onPttPressed()
    }

    fun onPttReleased() {
        activeRoute()?.onPttReleased()
    }

    private fun activeRoute(): AudioRoute? = when {
        channelRoute.enabled() -> channelRoute
        else -> testRoutes.values.firstOrNull { it.enabled() }
    }
}

enum class AudioTestMode { Echo, Stt, Tts, SttTts }

data class AudioRoute(
    val enabled: () -> Boolean,
    val setEnabled: (Boolean) -> Unit,
    val cancel: () -> Unit,
    val onPttPressed: () -> Unit,
    val onPttReleased: () -> Unit,
)
