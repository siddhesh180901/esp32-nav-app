package com.esp32.navlite

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
object BleNavManager {

    interface Listener {
        fun onStatusChanged(connected: Boolean, statusText: String, deviceName: String)
        fun onNavPreviewUpdated(preview: String)
    }

    private const val PREFS_NAME = "esp32_nav_ble_prefs"
    private const val KEY_LAST_MAC = "last_device_mac"
    private const val KEY_LAST_NAME = "last_device_name"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect_enabled"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var listener: Listener? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var activeScanCallback: ScanCallback? = null
    private var isConnected = false
    private var isScanning = false
    private var isAutoWaiting = false
    private var currentMtu = 23
    private var connectedDeviceName = "ESP32 Nav"
    private var lastStatusText = "Disconnected"
    private var btReceiverRegistered = false

    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false
    private var lastNavSignature = ""

    private val reconnectRunnable = Runnable {
        attemptAutoReconnect()
    }

    private val stopScanRunnable = Runnable {
        val ctx = appContext
        if (isScanning) {
            stopActiveScan()
            if (!isConnected) {
                if (ctx != null && isAutoReconnectEnabled(ctx) && !getSavedMac(ctx).isNullOrEmpty()) {
                    scheduleAutoReconnect(1500L)
                } else {
                    notifyStatus(false, "ESP32 Nav not found. Power on ESP32 & retry.", connectedDeviceName)
                }
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                mainHandler.removeCallbacks(reconnectRunnable)
                stopActiveScan()
                closeGattOnly()
                notifyStatus(false, "Bluetooth is OFF", connectedDeviceName)
            } else if (state == BluetoothAdapter.STATE_ON) {
                val ctx = appContext ?: context?.applicationContext ?: return
                if (isAutoReconnectEnabled(ctx)) {
                    scheduleAutoReconnect(1500L)
                }
            }
        }
    }

    private fun initContext(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val savedName = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_NAME, null)
        if (!savedName.isNullOrEmpty()) {
            connectedDeviceName = savedName
        }

        if (!btReceiverRegistered) {
            btReceiverRegistered = true
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(btStateReceiver, filter)
                }
            } catch (_: Exception) {
                btReceiverRegistered = false
            }
        }
    }

    private fun hasBleConnectPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getSavedMac(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MAC, null)
    }

    private fun isAutoReconnectEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RECONNECT, false)
    }

    private fun setAutoReconnectEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RECONNECT, enabled)
            .apply()
    }

    private fun saveDeviceInfo(ctx: Context, mac: String, name: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MAC, mac)
            .putString(KEY_LAST_NAME, name)
            .putBoolean(KEY_AUTO_RECONNECT, true)
            .apply()
    }

    fun setListener(l: Listener?) {
        listener = l
        notifyStatus(isConnected, lastStatusText, connectedDeviceName)
    }

    // Background service / app open zalyavar saved ESP32 la silently auto-reconnect suru karto
    fun ensureAutoReconnect(context: Context) {
        initContext(context)
        val ctx = appContext ?: return
        if (isConnected || isScanning || isAutoWaiting) return
        if (!isAutoReconnectEnabled(ctx)) return
        if (getSavedMac(ctx).isNullOrEmpty()) return
        scheduleAutoReconnect(1000L)
    }

    private fun scheduleAutoReconnect(delayMs: Long) {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun attemptAutoReconnect() {
        val ctx = appContext ?: return
        if (isConnected || isScanning || isAutoWaiting) return
        if (!isAutoReconnectEnabled(ctx)) return
        if (!hasBleConnectPermission(ctx)) return

        val savedMac = getSavedMac(ctx) ?: return
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) {
            notifyStatus(false, "Waiting for Bluetooth...", connectedDeviceName)
            return
        }

        try {
            val device = adapter.getRemoteDevice(savedMac)
            closeGattOnly()
            isAutoWaiting = true
            notifyStatus(false, "Auto-reconnecting...", connectedDeviceName)
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(ctx, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(ctx, true, gattCallback)
            }
        } catch (_: Exception) {
            isAutoWaiting = false
        }
    }

    fun startScanAndConnect(context: Context, targetName: String = "ESP32 Nav") {
        initContext(context)
        val ctx = appContext ?: return

        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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

        mainHandler.removeCallbacks(reconnectRunnable)
        setAutoReconnectEnabled(ctx, true)
        stopActiveScan()
        closeGattOnly()

        connectedDeviceName = targetName
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
                        stopActiveScan()
                        val resolvedName = name.ifEmpty { targetName }
                        saveDeviceInfo(ctx, device.address, resolvedName)
                        connectToDevice(ctx, device, resolvedName)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                activeScanCallback = null
                notifyStatus(false, "Scan failed (code $errorCode)", targetName)
            }
        }

        activeScanCallback = scanCallback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        mainHandler.removeCallbacks(stopScanRunnable)
        mainHandler.postDelayed(stopScanRunnable, 12000L)
    }

    private fun stopActiveScan() {
        mainHandler.removeCallbacks(stopScanRunnable)
        val cb = activeScanCallback
        activeScanCallback = null
        isScanning = false
        if (cb != null) {
            try {
                val manager = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                manager?.adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {}
        }
    }

    private fun connectToDevice(ctx: Context, device: BluetoothDevice, name: String) {
        connectedDeviceName = name
        isAutoWaiting = false
        notifyStatus(false, "Connecting to $name...", name)

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, gattCallback)
        }
    }

    private fun closeGattOnly() {
        writeQueue.clear()
        isWriting = false
        writeChar = null
        isConnected = false
        isAutoWaiting = false
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
    }

    // User ne manual Disconnect button dabla tar auto-reconnect band kar
    fun disconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        appContext?.let { setAutoReconnectEnabled(it, false) }
        stopActiveScan()
        closeGattOnly()
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
                isAutoWaiting = false
                mainHandler.removeCallbacks(reconnectRunnable)
                notifyStatus(false, "Discovering services...", connectedDeviceName)
                if (!gatt.requestMtu(256)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                isAutoWaiting = false
                writeChar = null
                writeQueue.clear()
                isWriting = false
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }

                val ctx = appContext
                if (ctx != null && isAutoReconnectEnabled(ctx)) {
                    notifyStatus(false, "Disconnected (Auto-reconnecting...)", connectedDeviceName)
                    scheduleAutoReconnect(2500L)
                } else {
                    notifyStatus(false, "Disconnected", connectedDeviceName)
                }
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

                // Connect hotach fresh Time, Battery, Temp, City pathav
                mainHandler.postDelayed({
                    appContext?.let { HomeSyncManager.start(it) }
                }, 500L)
            } else {
                notifyStatus(false, "Chronos service not found on device", connectedDeviceName)
                closeGattOnly()
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
        lastStatusText = text
        mainHandler.post {
            listener?.onStatusChanged(connected, text, deviceName)
        }
    }
}
