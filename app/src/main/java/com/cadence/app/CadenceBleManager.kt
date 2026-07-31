package com.cadence.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Работа со стандартным BLE-профилем Cycling Speed and Cadence (CSC).
 * CyCPLUS C3/S3 и большинство велодатчиков каденса используют именно этот профиль,
 * поэтому специфичного для CyCPLUS кода не требуется.
 */
class CadenceBleManager(private val context: Context) {

    companion object {
        private const val TAG = "CadenceBle"

        val CSC_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        val CSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null

    private var lastCrankRevs: Int? = null
    private var lastCrankEventTime: Int? = null // в единицах 1/1024 секунды

    private val _cadence = MutableStateFlow(0)
    val cadence: StateFlow<Int> = _cadence

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED, SCANNING, CONNECTING, CONNECTED
    }

    @SuppressLint("MissingPermission")
    fun startScanAndConnect() {
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter unavailable or disabled")
            return
        }
        _connectionState.value = ConnectionState.SCANNING

        val scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        // Останавливаем сканирование через 15 секунд, если ничего не найдено
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_connectionState.value == ConnectionState.SCANNING) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "stopScan failed", e)
                }
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids
            val hasCscService = uuids?.any {
                it.uuid == CSC_SERVICE_UUID
            } == true

            if (hasCscService) {
                adapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _cadence.value = 0
                lastCrankRevs = null
                lastCrankEventTime = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(CSC_SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CSC_MEASUREMENT_UUID) ?: return

            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CSC_MEASUREMENT_UUID) {
                parseCscMeasurement(characteristic.value)
            }
        }
    }

    /**
     * Формат CSC Measurement (Bluetooth SIG стандарт):
     * byte 0: флаги (бит 0 = wheel data present, бит 1 = crank data present)
     * далее опционально wheel revolution data (uint32 + uint16)
     * далее crank revolution data: uint16 cumulative crank revs + uint16 last crank event time (1/1024 s)
     */
    private fun parseCscMeasurement(data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        val flags = data[0].toInt()
        val wheelPresent = (flags and 0x01) != 0
        val crankPresent = (flags and 0x02) != 0

        var offset = 1
        if (wheelPresent) {
            offset += 6 // uint32 revolutions + uint16 event time
        }

        if (!crankPresent || data.size < offset + 4) return

        val crankRevs = ((data[offset].toInt() and 0xFF)) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
        val crankEventTime = ((data[offset + 2].toInt() and 0xFF)) or
                ((data[offset + 3].toInt() and 0xFF) shl 8)

        val prevRevs = lastCrankRevs
        val prevTime = lastCrankEventTime

        if (prevRevs != null && prevTime != null) {
            var revDiff = crankRevs - prevRevs
            if (revDiff < 0) revDiff += 65536 // переполнение uint16

            var timeDiff = crankEventTime - prevTime
            if (timeDiff < 0) timeDiff += 65536 // переполнение uint16

            if (timeDiff > 0) {
                // timeDiff в единицах 1/1024 секунды
                val timeDiffSeconds = timeDiff / 1024.0
                val rpm = (revDiff / timeDiffSeconds) * 60.0
                _cadence.value = rpm.toInt().coerceIn(0, 300)
            } else if (revDiff == 0) {
                // Педали не крутятся продолжительное время
                _cadence.value = 0
            }
        }

        lastCrankRevs = crankRevs
        lastCrankEventTime = crankEventTime
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _cadence.value = 0
        lastCrankRevs = null
        lastCrankEventTime = null
    }
}
