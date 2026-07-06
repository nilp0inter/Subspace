package dev.nilp0inter.subspace.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import io.sleepwalker.core.ble.BleUuids
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.hid.SessionStatusParser
import io.sleepwalker.core.hid.toFrameBytes
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.sleepwalker.core.hid.SessionStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

open class SleepwalkerBleConnection {
    companion object {
        private const val TAG = "SubspaceRoute"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }

    protected val _connectionState = MutableStateFlow(KeyboardConnectionState.Disconnected)
    open val connectionState: StateFlow<KeyboardConnectionState> = _connectionState.asStateFlow()

    private val _statusFlow = MutableSharedFlow<SessionStatus>(extraBufferCapacity = 64)
    val statusFlow: SharedFlow<SessionStatus> = _statusFlow.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23
    private val gattLock = Any()
    private var scanCallback: ScanCallback? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    @SuppressLint("MissingPermission")
    open fun connect(adapter: BluetoothAdapter?, context: Context) {
        if (adapter == null) {
            Log.d(TAG, "BLE_CONNECT_FAILED reason=no_adapter")
            return
        }
        bluetoothAdapter = adapter
        synchronized(gattLock) {
            val currentState = _connectionState.value
            if (currentState != KeyboardConnectionState.Disconnected) {
                Log.d(TAG, "BLE_CONNECT_SKIP current_state=$currentState")
                return
            }
            _connectionState.value = KeyboardConnectionState.Scanning
        }

        Log.d(TAG, "BLE_SCAN_START")
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.d(TAG, "BLE_SCAN_FAILED reason=no_le_scanner")
            _connectionState.value = KeyboardConnectionState.Disconnected
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: ""
                if (name.contains("sleepwalker", ignoreCase = true)) {
                    Log.d(TAG, "BLE_SCAN_FOUND name=$name")
                    synchronized(gattLock) {
                        if (_connectionState.value == KeyboardConnectionState.Scanning) {
                            _connectionState.value = KeyboardConnectionState.Connecting
                            try {
                                scanner.stopScan(this)
                            } catch (e: Exception) {
                                Log.d(TAG, "BLE_SCAN_STOP_ERROR msg=${e.message}")
                            }
                            connectToDevice(dev, context)
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d(TAG, "BLE_SCAN_FAILED error_code=$errorCode")
                synchronized(gattLock) {
                    if (_connectionState.value == KeyboardConnectionState.Scanning) {
                        _connectionState.value = KeyboardConnectionState.Disconnected
                    }
                }
            }
        }
        scanCallback = callback

        val filter = ScanFilter.Builder()
            .setDeviceName("sleepwalker")
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            Log.d(TAG, "BLE_SCAN_FAILED reason=security_exception msg=${e.message}")
            _connectionState.value = KeyboardConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, context: Context) {
        Log.d(TAG, "BLE_CONNECT_TO_DEVICE addr=${device.address}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    open fun disconnect() {
        synchronized(gattLock) {
            scanCallback?.let { callback ->
                try {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
                } catch (e: Exception) {}
            }
            scanCallback = null
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            rxChar = null
            txChar = null
            negotiatedMtu = 23
            _connectionState.value = KeyboardConnectionState.Disconnected
            Log.d(TAG, "BLE_DISCONNECTED_DONE")
        }
    }

    @SuppressLint("MissingPermission")
    open suspend fun sendOp(op: LowLevelOp) {
        val g = gatt
        val rx = rxChar
        if (_connectionState.value != KeyboardConnectionState.Connected || g == null || rx == null) {
            Log.d(TAG, "BLE_SEND_OP_SKIP state=${_connectionState.value} gatt=${g != null} rx=${rx != null}")
            return
        }
        val frame = op.toFrameBytes()
        val chunks = BleWriter.chunkFrame(frame, negotiatedMtu)
        for (chunk in chunks) {
            synchronized(gattLock) {
                @Suppress("DEPRECATION")
                rx.value = chunk
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val success = g.writeCharacteristic(rx)
                Log.d(TAG, "BLE_WRITE_RESULT opcode=${op.opcode} success=$success size=${chunk.size}")
            }
            kotlinx.coroutines.delay(15)
        }
    }

    open suspend fun awaitAck(seqId: Int, timeoutMs: Long = 3000): Boolean {
        if (_connectionState.value != KeyboardConnectionState.Connected) return false
        return withTimeoutOrNull(timeoutMs) {
            statusFlow.first { it.seqId == seqId && it.status == io.sleepwalker.core.protocol.Status.SENT_TO_USB }
            true
        } ?: false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "BLE_CONN_STATE status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "BLE_DISCONNECTED")
                synchronized(gattLock) {
                    gatt.close()
                    if (this@SleepwalkerBleConnection.gatt == gatt) {
                        this@SleepwalkerBleConnection.gatt = null
                        rxChar = null
                        txChar = null
                        negotiatedMtu = 23
                        _connectionState.value = KeyboardConnectionState.Disconnected
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "BLE_SERVICES_DISCOVERED status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                return
            }
            val service = gatt.getService(UUID.fromString(BleUuids.SERVICE))
            if (service == null) {
                Log.d(TAG, "BLE_SERVICE_NOT_FOUND")
                disconnect()
                return
            }
            val rx = service.getCharacteristic(UUID.fromString(BleUuids.RX_CHARACTERISTIC))
            val tx = service.getCharacteristic(UUID.fromString(BleUuids.TX_CHARACTERISTIC))
            if (rx == null || tx == null) {
                Log.d(TAG, "BLE_CHARACTERISTIC_NOT_FOUND")
                disconnect()
                return
            }
            synchronized(gattLock) {
                rxChar = rx
                txChar = tx
                gatt.requestMtu(247)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "BLE_MTU_CHANGED mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            synchronized(gattLock) {
                val tx = txChar
                if (tx != null) {
                    gatt.setCharacteristicNotification(tx, true)
                    val cccd = tx.getDescriptor(UUID.fromString(CCCD_UUID))
                    if (cccd != null) {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val success = gatt.writeDescriptor(cccd)
                        Log.d(TAG, "BLE_WRITE_DESCRIPTOR started=$success")
                    } else {
                        _connectionState.value = KeyboardConnectionState.Connected
                    }
                } else {
                    _connectionState.value = KeyboardConnectionState.Connected
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "BLE_DESCRIPTOR_WRITE status=$status")
            synchronized(gattLock) {
                _connectionState.value = KeyboardConnectionState.Connected
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }
    }

    private fun handleNotification(data: ByteArray?) {
        if (data == null) return
        val status = SessionStatusParser.parse(data)
        if (status != null) {
            Log.d(TAG, "BLE_STATUS seqId=${status.seqId} statusName=${status.statusName}")
            _statusFlow.tryEmit(status)
        }
    }
}
