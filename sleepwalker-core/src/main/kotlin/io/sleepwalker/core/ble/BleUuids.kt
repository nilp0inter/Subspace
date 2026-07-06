package io.sleepwalker.core.ble

/**
 * BLE UUID constants for the sleepwalker custom UART-style GATT service.
 *
 * Mirror of firmware ble_uart.h. Must agree with the ESP32-S3 firmware
 * service/characteristic UUIDs.
 */
object BleUuids {
    // Custom 128-bit service/characteristic UUIDs (sleepwalker-specific).
    const val SERVICE: String = "0f1e2d3c-4b5a-6987-8765-4321fedcba98"
    const val RX_CHARACTERISTIC: String = "0f1e2d3c-4b5a-6987-8765-4321fedcba99"
    const val TX_CHARACTERISTIC: String = "0f1e2d3c-4b5a-6987-8765-4321fedcba9a"
}