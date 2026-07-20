package dev.nilp0inter.subspace.audio

import android.bluetooth.BluetoothDevice
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.nilp0inter.subspace.model.InputMode

/** Observable host request used to construct one semantic playback route. */
internal data class PlaybackRouteRequest(
    val mode: InputMode,
    val endpoint: AudioRouteEndpoint,
    val preferredDevice: AudioDeviceInfo,
    val owner: BluetoothDevice? = null,
    val usage: Int,
    val audioMode: Int,
    /** Named alias retained for route-observation consumers. */
    val preferredBluetoothDevice: BluetoothDevice? = owner,
)

/**
 * Host-only output policy for channel content. This deliberately does not reuse
 * [ResolvedAudioRoute], which owns an input source and a PTT-session release policy.
 *
 * [targetRsmDevice] is mandatory for Work acquisition: an anonymous SCO endpoint is accepted only
 * after explicit target ownership proof. [awaitTelecomCaptureRelease] is called before any
 * On-the-road mode/device query.
 */
internal class ModePlaybackRouteResolver(
    private val audioManager: AudioManager,
    private val workSco: ScoAudioController,
    private val targetRsmDevice: () -> BluetoothDevice? = { null },
    /** BluetoothHeadset/startVoiceRecognition ownership proof for the resolved target. */
    private val targetRsmOwnershipProof: (BluetoothDevice) -> Boolean = { true },
    private val awaitTelecomCaptureRelease: suspend () -> Boolean = { false },
    private val carMediaDevice: () -> AudioDeviceInfo? = {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in CAR_OUTPUT_TYPES }
    },
    private val routeFactory: (PlaybackRouteRequest) -> AcquiredPlaybackRoute = { request ->
        StreamPlaybackRoute(
            preferredDevice = request.preferredDevice,
            usage = request.usage,
            routeEndpoint = request.endpoint,
        )
    },
) {
    fun strategyFor(mode: InputMode): PlaybackRouteStrategy = PlaybackRouteStrategy {
        when (mode) {
            InputMode.Work -> acquireWork()
            InputMode.OnTheRoad -> acquireCar()
            InputMode.OnAPinch -> acquirePhone()
        }
    }

    private suspend fun acquireWork(): PlaybackRouteAcquisition {
        val owner = targetRsmDevice()
            ?: return PlaybackRouteAcquisition.Unavailable("Target RSM Bluetooth device unavailable")
        if (!workSco.acquire()) return PlaybackRouteAcquisition.Unavailable("Target RSM SCO unavailable")
        val ownershipProven = runCatching { targetRsmOwnershipProof(owner) }.getOrDefault(false)
        if (!ownershipProven) {
            workSco.release()
            return PlaybackRouteAcquisition.Unavailable("Target RSM Bluetooth ownership unavailable")
        }
        val device = workSco.selectedCommunicationDevice()
        if (!isOwnedTargetSco(device, owner)) {
            workSco.release()
            return PlaybackRouteAcquisition.Unavailable("Target RSM SCO ownership unavailable")
        }
        val ownedDevice = checkNotNull(device)
        val route = try {
            routeFactory(
                PlaybackRouteRequest(
                    mode = InputMode.Work,
                    endpoint = AudioRouteEndpoint.Rsm,
                    preferredDevice = ownedDevice,
                    owner = owner,
                    usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                ),
            )
        } catch (error: Exception) {
            workSco.release()
            return PlaybackRouteAcquisition.Failed(error.message ?: "Unable to construct RSM playback route")
        }
        return PlaybackRouteAcquisition.Acquired(
            ReleasingPlaybackRoute(route, releaseUnderlying = workSco::release),
        )
    }

    private suspend fun acquireCar(): PlaybackRouteAcquisition {
        if (!awaitTelecomCaptureRelease()) {
            return PlaybackRouteAcquisition.Unavailable("Telecom capture route unavailable")
        }
        if (audioManager.mode != AudioManager.MODE_NORMAL ||
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return PlaybackRouteAcquisition.Busy
        }
        val device = carMediaDevice()?.takeIf { it.type in CAR_OUTPUT_TYPES }
            ?: return PlaybackRouteAcquisition.Unavailable("Validated car media output unavailable")
        return PlaybackRouteAcquisition.Acquired(
            routeFactory(
                PlaybackRouteRequest(
                    mode = InputMode.OnTheRoad,
                    endpoint = AudioRouteEndpoint.Car,
                    preferredDevice = device,
                    usage = AudioAttributes.USAGE_MEDIA,
                    audioMode = AudioManager.MODE_NORMAL,
                ),
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
            routeFactory(
                PlaybackRouteRequest(
                    mode = InputMode.OnAPinch,
                    endpoint = AudioRouteEndpoint.Local,
                    preferredDevice = device,
                    usage = AudioAttributes.USAGE_MEDIA,
                    audioMode = AudioManager.MODE_NORMAL,
                ),
            ),
        )
    }

    private fun isOwnedTargetSco(device: AudioDeviceInfo?, owner: BluetoothDevice): Boolean {
        if (device == null || device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return false
        val expectedName = runCatching { owner.name }.getOrNull()?.trim().orEmpty()
        val productName = device.productName?.toString()?.trim().orEmpty()
        if (productName.isNotEmpty() &&
            (expectedName.isEmpty() ||
                (!productName.equals(expectedName, ignoreCase = true) &&
                    !productName.contains(expectedName, ignoreCase = true)))
        ) return false
        val expectedAddress = runCatching { owner.address }.getOrNull()?.trim().orEmpty()
        val endpointAddress = runCatching { device.address }.getOrNull()?.trim().orEmpty()
        if (endpointAddress.isNotEmpty() &&
            (expectedAddress.isEmpty() || !expectedAddress.equals(endpointAddress, ignoreCase = true))
        ) return false
        // An anonymous AudioDeviceInfo is valid only because explicit BluetoothDevice ownership
        // was proven before SCO acquisition.
        return true
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
