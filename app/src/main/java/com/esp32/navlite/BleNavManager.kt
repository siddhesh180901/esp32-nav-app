package com.esp32.navlite

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
object BleNavManager {

    interface Listener {
        fun onStatusChanged(connected: Boolean, statusText: String, deviceName: String)
        fun onNavPreviewUpdated(preview: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var isScanning = false
    private var currentMtu = 23
    private var connectedDeviceName = "ESP32 Nav"

    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false

    private var lastNavSignature = ""

    fun setListener(l: Listener?) {
        listener = l
        notifyStatus(
            isConnected,
            if (isConnected) "Connected" else "Disconnected",
            connectedDeviceName
        )
    }

    fun startScanAndConnect(context: Context, targetName: String = "ESP32 Nav") {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            notifyStatus(false, "Please turn ON Bluetooth", targetName)
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            notifyStatus(false, "BLE Scanner unavailable", targetName)
            return
        }

        disconnect()
        isScanning = true
        notifyStatus(false, "Scanning for $targetName...", targetName)

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: ""
                val hasChronosUuid = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().equals(ChronosProtocol.SERVICE_UUID, ignoreCase = true)
                } == true

                if (name.contains(targetName, ignoreCase = true) || hasChronosUuid) {
                    if (isScanning) {
                        isScanning = false
                        scanner.stopScan(this)
                        connectToDevice(context.applicationContext, device, name.ifEmpty { targetName })
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                notifyStatus(false, "Scan failed (code $errorCode)", targetName)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        // Stop scan after 12 seconds if device not found
        mainHandler.postDelayed({
            if (isScanning) {
                isScanning = false
                scanner.stopScan(scanCallback)
                notifyStatus(false, "ESP32 Nav not found. Power on ESP32 & retry.", targetName)
            }
        }, 12000)
    }

    private fun connectToDevice(appContext: Context, device: BluetoothDevice, name: String) {
        connectedDeviceName = name
        notifyStatus(false, "Connecting to $name...", name)

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    fun disconnect() {
        writeQueue.clear()
        isWriting = false
        writeChar = null
        isConnected = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        notifyStatus(false, "Disconnected", connectedDeviceName)
    }

    fun sendNavigation(nav: NavData, force: Boolean = false) {
        val signature = "${nav.active}|${nav.distance}|${nav.directions}|${nav.title}|${nav.eta}"
        if (!force && signature == lastNavSignature) return
        lastNavSignature = signature

        val preview = if (nav.active) {
            "DIR : ${nav.directions}\nDIST: ${nav.distance}\nROAD: ${nav.title}\nETA : ${nav.eta}"
        } else {
            "Navigation Stopped (Idle)"
        }
        mainHandler.post { listener?.onNavPreviewUpdated(preview) }

        if (!isConnected || writeChar == null || bluetoothGatt == null) return

        val frame = ChronosProtocol.buildNavFrame(nav)
        val maxPayload = (currentMtu - 3).coerceAtLeast(20)
        val chunks = ChronosProtocol.splitIntoBleChunks(frame, maxPayload)

        writeQueue.clear()
        chunks.forEach { writeQueue.add(it) }
        drainQueue()
    }

    private fun drainQueue() {
        if (isWriting) return
        val gatt = bluetoothGatt ?: return
        val ch = writeChar ?: return
        val next = writeQueue.poll() ?: return

        isWriting = true
        val writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, next, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = writeType
            @Suppress("DEPRECATION")
            ch.value = next
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }

        if (!started) {
            isWriting = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                notifyStatus(false, "Discovering services...", connectedDeviceName)
                gatt.requestMtu(256)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                writeChar = null
                writeQueue.clear()
                isWriting = false
                notifyStatus(false, "Disconnected", connectedDeviceName)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyStatus(false, "Service discovery failed", connectedDeviceName)
                return
            }

            val service = gatt.getService(UUID.fromString(ChronosProtocol.SERVICE_UUID))
            val rxChar = service?.getCharacteristic(UUID.fromString(ChronosProtocol.CHAR_WRITE_UUID))

            if (rxChar != null) {
                writeChar = rxChar
                isConnected = true
                lastNavSignature = ""
                notifyStatus(true, "Connected", connectedDeviceName)

                val txChar = service.getCharacteristic(UUID.fromString(ChronosProtocol.CHAR_NOTIFY_UUID))
                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                }
            } else {
                notifyStatus(false, "Chronos service not found on device", connectedDeviceName)
                disconnect()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWriting = false
            mainHandler.postDelayed({ drainQueue() }, 25)
        }
    }

    private fun notifyStatus(connected: Boolean, text: String, deviceName: String) {
        mainHandler.post {
            listener?.onStatusChanged(connected, text, deviceName)
        }
    }
}
