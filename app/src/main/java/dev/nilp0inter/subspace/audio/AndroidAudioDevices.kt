package dev.nilp0inter.subspace.audio

import android.media.AudioDeviceInfo
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME

internal fun AudioDeviceInfo.isBluetoothScoEndpoint(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

internal fun AudioDeviceInfo.isTargetRsmScoEndpoint(): Boolean =
    isBluetoothScoEndpoint() &&
        productName?.contains(TARGET_DEVICE_NAME, ignoreCase = true) == true

internal fun sameAudioDevice(left: AudioDeviceInfo, right: AudioDeviceInfo): Boolean =
    left.id == right.id && left.type == right.type
