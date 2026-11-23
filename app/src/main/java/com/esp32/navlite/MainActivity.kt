package com.esp32.navlite

import android.Manifest
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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_DEVICE_NAME = "ESP32 Nav"
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "esp32_nav_prefs"
        private const val KEY_LAST_MAC = "last_device_mac"
    }

    private var tvNotifStatus: TextView? = null
    private var tvBleStatus: TextView? = null
    private var tvLiveData: TextView? = null
    private var btnNotifAccess: Button? = null
    private var btnScanConnect: Button? = null
    private var btnDisconnect: Button? = null

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var isConnected = false
    private var manualDisconnect = false

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            HomeSyncManager.tryStartLocationUpdates()
            startBleScan(clearSavedMac = true)
        } else {
            updateBleStatus("Grant Bluetooth & Location permission")
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (bluetoothAdapter?.isEnabled == true) {
            startBleScan(clearSavedMac = true)
        } else {
            updateBleStatus("Bluetooth is OFF")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViewsIfPresent()

        try {
            val attachMethod = BleNavManager::class.java.methods.firstOrNull {
                it.name == "attachMainActivity" && it.parameterCount == 1
            }
            attachMethod?.invoke(BleNavManager, this)
        } catch (_: Exception) {
        }

        HomeSyncManager.start(applicationContext)

        btnNotifAccess?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnScanConnect?.setOnClickListener {
            Toast.makeText(this, "Scanning for $TARGET_DEVICE_NAME...", Toast.LENGTH_SHORT).show()
            manualDisconnect = false
            disconnectGatt(notifyManager = false)
            updateBleStatus("Starting BLE scan...")
            ensureBlePermissionsAndConnect(forceFreshScan = true)
        }

        btnDisconnect?.setOnClickListener {
            manualDisconnect = true
            disconnectGatt(notifyManager = true)
            updateBleStatus("Disconnected")
        }

        wireTestButton("btnTestLeft", NavData(true, "200 m", "Turn Left", "FC Road", "2 min", "10:15 AM"))
        wireTestButton("btnTestRight", NavData(true, "150 m", "Turn Right", "JM Road", "1 min", "10:16 AM"))
        wireTestButton("btnTestStraight", NavData(true, "500 m", "Straight", "MG Road", "4 min", "10:18 AM"))
        wireTestButton("btnTestUTurn", NavData(true, "80 m", "U-Turn", "Highway", "1 min", "10:19 AM"))

        updateNotificationAccessStatus()
        updateBleStatus(if (isConnected) "Connected to $TARGET_DEVICE_NAME" else "Disconnected")
    }

    private fun findViewByAnyId(vararg names: String): View? {
        for (name in names) {
            val id = resources.getIdentifier(name, "id", packageName)
            if (id != 0) {
                val view = findViewById<View>(id)
                if (view != null) return view
            }
        }
        return null
    }

    private fun bindViewsIfPresent() {
        tvNotifStatus = findViewByAnyId("tvNotifStatus", "tv_notif_status") as? TextView
        tvBleStatus = findViewByAnyId("tvBleStatus", "tv_ble_status") as? TextView
        tvLiveData = findViewByAnyId("tvLiveData", "tv_live_data") as? TextView
        btnNotifAccess = findViewByAnyId("btnNotifAccess", "btn_notif_access") as? Button
        btnScanConnect = findViewByAnyId("btnScanConnect", "btn_scan_connect") as? Button
        btnDisconnect = findViewByAnyId("btnDisconnect", "btn_disconnect") as? Button
    }

    private fun wireTestButton(idName: String, sample: NavData) {
        val resId = resources.getIdentifier(idName, "id", packageName)
        if (resId == 0) return
        findViewById<Button>(resId)?.setOnClickListener {
            BleNavManager.sendNavigation(sample, force = true)
        }
    }

    private fun notifyBleManagerConnection(connected: Boolean) {
        try {
            val method = BleNavManager::class.java.methods.firstOrNull {
                it.name == "onConnectionChanged" && it.parameterCount == 1
            }
            method?.invoke(BleNavManager, connected)
        } catch (_: Exception) {
        }
    }

    private fun notifyBleManagerReady() {
        try {
            val method = BleNavManager::class.java.methods.firstOrNull {
                it.name == "onReadyToSend" && it.parameterCount == 0
            }
            method?.invoke(BleNavManager)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessStatus()
        HomeSyncManager.start(applicationContext)
        if (!isConnected && !manualDisconnect) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getString(KEY_LAST_MAC, null).isNullOrEmpty()) {
                ensureBlePermissionsAndConnect(forceFreshScan = false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        try {
            val detachMethod = BleNavManager::class.java.methods.firstOrNull {
                it.name == "detachMainActivity" && it.parameterCount == 1
            }
            detachMethod?.invoke(BleNavManager, this)
        } catch (_: Exception) {
        }
    }

    private fun updateNotificationAccessStatus() {
        val enabled = isNotificationServiceEnabled()
        if (enabled) {
            tvNotifStatus?.text = "Notification Access: ENABLED"
            btnNotifAccess?.text = "Notification Access Granted"
        } else {
            tvNotifStatus?.text = "Notification Access: DISABLED (Tap below)"
            btnNotifAccess?.text = "Enable Notification Access"
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun ensureBlePermissionsAndConnect(forceFreshScan: Boolean) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val distinctPerms = required.distinct()
        if (distinctPerms.isNotEmpty()) {
            updateBleStatus("Requesting permissions...")
            permissionLauncher.launch(distinctPerms.toTypedArray())
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            updateBleStatus("Turn ON Bluetooth...")
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (forceFreshScan) {
            startBleScan(clearSavedMac = true)
        } else {
            startBleScanOrDirectConnect()
        }
    }

    @Suppress("MissingPermission")
    private fun startBleScanOrDirectConnect() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || isConnected) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(KEY_LAST_MAC, null)
        if (!savedMac.isNullOrEmpty()) {
            try {
                val device = adapter.getRemoteDevice(savedMac)
                connectToDevice(device)
                return
            } catch (_: Exception) {
            }
        }

        startBleScan(clearSavedMac = false)
    }

    @Suppress("MissingPermission")
    private fun startBleScan(clearSavedMac: Boolean) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            updateBleStatus("BLE Scanner unavailable")
            return
        }
        if (clearSavedMac) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_MAC).apply()
        }
        if (isScanning) return

        isScanning = true
        updateBleStatus("Scanning for $TARGET_DEVICE_NAME...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, 10_000L)
    }

    private val stopScanRunnable = Runnable {
        stopBleScan()
        if (!isConnected) {
            updateBleStatus("Device not found. Tap Scan & Connect.")
        }
    }

    @Suppress("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        handler.removeCallbacks(stopScanRunnable)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        @Suppress("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val advName = result.scanRecord?.deviceName ?: ""
            val devName = try { device.name ?: "" } catch (_: Exception) { "" }
            val hasNusUuid = result.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true

            if (advName.equals(TARGET_DEVICE_NAME, ignoreCase = true) ||
                devName.equals(TARGET_DEVICE_NAME, ignoreCase = true) ||
                advName.contains("ESP32", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true) ||
                hasNusUuid
            ) {
                stopBleScan()
                connectToDevice(device)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        updateBleStatus("Connecting to $TARGET_DEVICE_NAME...")
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MAC, device.address)
            .apply()

        bluetoothGatt?.close()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    @Suppress("MissingPermission")
    private fun disconnectGatt(notifyManager: Boolean) {
        stopBleScan()
        rxCharacteristic = null
        isConnected = false
        if (notifyManager) {
            notifyBleManagerConnection(false)
        }
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @Suppress("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                runOnUiThread {
                    updateBleStatus("Connected to $TARGET_DEVICE_NAME")
                }
                notifyBleManagerConnection(true)
                gatt.requestMtu(185)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                rxCharacteristic = null
                notifyBleManagerConnection(false)
                runOnUiThread {
                    updateBleStatus("Disconnected")
                }
                if (!manualDisconnect) {
                    handler.postDelayed({
                        if (!isConnected && !manualDisconnect) {
                            startBleScanOrDirectConnect()
                        }
                    }, 2_000L)
                }
            }
        }

        @Suppress("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(NUS_SERVICE_UUID) ?: return
            rxCharacteristic = service.getCharacteristic(NUS_RX_CHAR_UUID)
            val txCharacteristic = service.getCharacteristic(NUS_TX_CHAR_UUID)
            if (txCharacteristic != null) {
                gatt.setCharacteristicNotification(txCharacteristic, true)
                val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
            if (rxCharacteristic != null) {
                notifyBleManagerReady()
            }
        }
    }

    @Suppress("MissingPermission")
    fun writePacketToEsp32(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val chara = rxCharacteristic ?: return false
        if (!isConnected) return false

        return try {
            chara.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            chara.value = data
            gatt.writeCharacteristic(chara)
        } catch (_: Exception) {
            false
        }
    }

    fun updateBleStatus(status: String) {
        runOnUiThread {
            tvBleStatus?.text = "BLE: $status"
        }
    }

    fun updateLiveDataPreview(nav: NavData) {
        runOnUiThread {
            tvLiveData?.text = if (nav.active) {
                "DIR: ${nav.directions} | DIST: ${nav.distance}\nTITLE: ${nav.title} | ETA: ${nav.eta}"
            } else {
                "Navigation Idle (Waiting for Google Maps)"
            }
        }
    }
}
