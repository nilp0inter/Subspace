package dev.nilp0inter.subspace.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun readyOnlyWhenAllRequiredSignalsAreValid() {
        val ready = ConnectionState(
            permissions = PermissionState.Granted,
            bluetoothEnabled = true,
            devicePresence = DevicePresence.Bonded,
            spp = SppState.Connected,
            headsetAudio = HeadsetAudioState.Available,
        )

        assertTrue(ready.readyForMonitor)
        assertFalse(ready.copy(spp = SppState.Disconnected).readyForMonitor)
        assertFalse(ready.copy(headsetAudio = HeadsetAudioState.Unavailable).readyForMonitor)
        assertFalse(ready.copy(devicePresence = DevicePresence.Found).readyForMonitor)
        assertFalse(ready.copy(permissions = PermissionState.Missing).readyForMonitor)
        assertFalse(ready.copy(bluetoothEnabled = false).readyForMonitor)
    }
}
