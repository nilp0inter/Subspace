package dev.nilp0inter.subspace.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import io.sleepwalker.core.hid.LowLevelOp

class FakeSleepwalkerBleConnection : SleepwalkerBleConnection() {
    val sentOps = mutableListOf<LowLevelOp>()
    var shouldThrowOnSend = false

    fun setConnectionState(state: KeyboardConnectionState) {
        _connectionState.value = state
    }

    override fun connect(adapter: BluetoothAdapter?, context: Context) {
        // No-op or test-driven
    }

    override fun disconnect() {
        _connectionState.value = KeyboardConnectionState.Disconnected
    }

    override suspend fun sendOp(op: LowLevelOp) {
        if (shouldThrowOnSend) {
            throw Exception("Fake connection send failed")
        }
        if (_connectionState.value == KeyboardConnectionState.Connected) {
            sentOps.add(op)
        }
    }

    override suspend fun awaitAck(seqId: Int, timeoutMs: Long): Boolean {
        return true
    }
}
