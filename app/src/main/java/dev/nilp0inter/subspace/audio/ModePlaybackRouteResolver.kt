package dev.nilp0inter.subspace.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.nilp0inter.subspace.model.InputMode

/**
 * Host-only output policy for channel content. This deliberately does not reuse
 * [ResolvedAudioRoute], which owns an input source and a PTT-session release policy.
 */
internal class ModePlaybackRouteResolver(
    private val audioManager: AudioManager,
    private val workSco: ScoAudioController,
) {
    fun strategyFor(mode: InputMode): PlaybackRouteStrategy = PlaybackRouteStrategy {
        when (mode) {
            InputMode.Work -> acquireWork()
            InputMode.OnTheRoad -> acquireCar()
            InputMode.OnAPinch -> acquirePhone()
        }
    }

    private suspend fun acquireWork(): PlaybackRouteAcquisition {
        if (!workSco.acquire()) return PlaybackRouteAcquisition.Unavailable("Target RSM SCO unavailable")
        val device = workSco.selectedCommunicationDevice()
        if (device == null || device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            workSco.release()
            return PlaybackRouteAcquisition.Unavailable("Target RSM SCO transport unavailable")
        }
        return PlaybackRouteAcquisition.Acquired(
            ReleasingPlaybackRoute(
                StreamPlaybackRoute(
                    preferredDevice = device,
                    usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    routeEndpoint = AudioRouteEndpoint.Rsm,
                ),
                releaseUnderlying = workSco::release,
            ),
        )
    }

    private fun acquireCar(): PlaybackRouteAcquisition {
        if (audioManager.mode != AudioManager.MODE_NORMAL ||
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return PlaybackRouteAcquisition.Busy
        }
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in CAR_OUTPUT_TYPES }
            ?: return PlaybackRouteAcquisition.Unavailable("Validated car media output unavailable")
        return PlaybackRouteAcquisition.Acquired(
            StreamPlaybackRoute(
                preferredDevice = device,
                usage = AudioAttributes.USAGE_MEDIA,
                routeEndpoint = AudioRouteEndpoint.Car,
            ),
        )
    }

    private fun acquirePhone(): PlaybackRouteAcquisition {
        if (audioManager.mode != AudioManager.MODE_NORMAL ||
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return PlaybackRouteAcquisition.Busy
        }
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return PlaybackRouteAcquisition.Unavailable("Phone speaker output unavailable")
        return PlaybackRouteAcquisition.Acquired(
            StreamPlaybackRoute(
                preferredDevice = device,
                usage = AudioAttributes.USAGE_MEDIA,
                routeEndpoint = AudioRouteEndpoint.Local,
            ),
        )
    }

    private class ReleasingPlaybackRoute(
        private val delegate: AcquiredPlaybackRoute,
        private val releaseUnderlying: () -> Unit,
    ) : AcquiredPlaybackRoute {
        private var released = false

        override val endpoint: AudioRouteEndpoint
            get() = delegate.endpoint

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = delegate.start(recording)

        override suspend fun release() {
            if (released) return
            released = true
            try {
                delegate.release()
            } finally {
                releaseUnderlying.invoke()
            }
        }
    }

    private companion object {
        val CAR_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
