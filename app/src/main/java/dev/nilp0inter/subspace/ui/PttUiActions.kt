package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.EchoTimingMode

interface PttUiActions {
    fun requestPermissions()
    fun openBluetoothSettings()
    fun scanForDevice()
    fun pairTarget()
    fun connectSerial()
    fun retry()
    fun disconnectSerial()
    fun setEchoEnabled(enabled: Boolean)
    fun setEchoTimingMode(mode: EchoTimingMode)
}
