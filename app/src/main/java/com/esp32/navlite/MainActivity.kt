package com.esp32.navlite

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BleNavManager.Listener {

    companion object {
        private const val PREFS_NAME = "esp32_nav_hud_prefs"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_GPS_SPEED = "gps_speed_enabled"
    }

    private lateinit var tvBlePill: TextView
    private lateinit var tvHudSpeed: TextView
    private lateinit var tvHudCompass: TextView
    private lateinit var tvHudTemp: TextView
    private lateinit var tvHudMeta: TextView
    private lateinit var tvBleStatus: TextView
    private lateinit var tvNotifStatus: TextView

    private lateinit var etDestination: EditText
    private lateinit var btnStartNav: Button
    private lateinit var btnOpenMaps: Button
    private lateinit var btnScanConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnNotifAccess: Button
    private lateinit var switchAutoReconnect: Switch
    private lateinit var switchGpsSpeed: Switch

    private var isBleConnected = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        HomeSyncManager.tryStartLocationUpdates()
        val bleGranted = requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (bleGranted) {
            BleNavManager.startScanAndConnect(this)
        } else {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadPreferences()
        HomeSyncManager.setBatterySaverEnabled(!switchGpsSpeed.isChecked)
        HomeSyncManager.start(applicationContext)

        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                launchDirectBikeNavigation()
                true
            } else {
                false
            }
        }

        btnStartNav.setOnClickListener {
            launchDirectBikeNavigation()
        }

        btnOpenMaps.setOnClickListener {
            openGoogleMapsApp()
        }

        btnScanConnect.setOnClickListener {
            ensureBlePermissionsAndConnect()
        }

        btnDisconnect.setOnClickListener {
            BleNavManager.disconnect()
        }

        btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_RECONNECT, isChecked).apply()
            if (isChecked && !isBleConnected) {
                triggerAutoReconnect()
            }
        }

        switchGpsSpeed.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_GPS_SPEED, isChecked).apply()
            HomeSyncManager.setBatterySaverEnabled(!isChecked)
            if (!isChecked) {
                tvHudSpeed.text = "0"
            }
        }

        findViewById<Button>(R.id.btnTestLeft).setOnClickListener {
            HomeSyncManager.setNavigationActive(true)
            BleNavManager.sendNavigation(
                NavData(true, "200 m", "Turn Left", "MG Road", "5 min", "11:15 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestStraight).setOnClickListener {
            HomeSyncManager.setNavigationActive(true)
            BleNavManager.sendNavigation(
                NavData(true, "500 m", "Straight", "Highway", "8 min", "11:18 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestRight).setOnClickListener {
            HomeSyncManager.setNavigationActive(true)
            BleNavManager.sendNavigation(
                NavData(true, "150 m", "Turn Right", "Station Rd", "4 min", "11:14 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestUTurn).setOnClickListener {
            HomeSyncManager.setNavigationActive(true)
            BleNavManager.sendNavigation(
                NavData(true, "50 m", "U-Turn", "Main Chowk", "2 min", "11:12 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestStop).setOnClickListener {
            BleNavManager.sendNavigation(NavData(active = false), force = true)
            HomeSyncManager.setNavigationActive(false)
        }
    }

    private fun bindViews() {
        tvBlePill = findViewById(R.id.tvBlePill)
        tvHudSpeed = findViewById(R.id.tvHudSpeed)
        tvHudCompass = findViewById(R.id.tvHudCompass)
        tvHudTemp = findViewById(R.id.tvHudTemp)
        tvHudMeta = findViewById(R.id.tvHudMeta)
        tvBleStatus = findViewById(R.id.tvBleStatus)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)

        etDestination = findViewById(R.id.etDestination)
        btnStartNav = findViewById(R.id.btnStartNav)
        btnOpenMaps = findViewById(R.id.btnOpenMaps)
        btnScanConnect = findViewById(R.id.btnScanConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        switchAutoReconnect = findViewById(R.id.switchAutoReconnect)
        switchGpsSpeed = findViewById(R.id.switchGpsSpeed)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchAutoReconnect.isChecked = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        switchGpsSpeed.isChecked = prefs.getBoolean(KEY_GPS_SPEED, true)
    }

    private fun launchDirectBikeNavigation() {
        val dest = etDestination.text?.toString()?.trim().orEmpty()
        if (dest.isEmpty()) {
            openGoogleMapsApp()
            return
        }
        try {
            // mode=l opens Google Maps directly in Two-Wheeler (Bike) navigation
            val uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=l")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackUri = Uri.parse("google.navigation:q=${Uri.encode(dest)}")
                startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
            } catch (_: Exception) {
                Toast.makeText(this, "Google Maps not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openGoogleMapsApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            try {
                val uri = Uri.parse("geo:0,0?q=")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(this, "Google Maps not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerAutoReconnect() {
        try {
            val method = BleNavManager::class.java.getMethod("ensureAutoReconnect", Context::class.java)
            method.invoke(BleNavManager, this)
        } catch (_: Exception) {
            val bleGranted = requiredBlePermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (bleGranted && !isBleConnected) {
                BleNavManager.startScanAndConnect(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BleNavManager.setListener(this)
        updateNotificationAccessStatus()
        HomeSyncManager.setBatterySaverEnabled(!switchGpsSpeed.isChecked)
        HomeSyncManager.start(applicationContext)
        if (switchAutoReconnect.isChecked && !isBleConnected) {
            triggerAutoReconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BleNavManager.setListener(null)
    }

    override fun onStatusChanged(connected: Boolean, statusText: String, deviceName: String) {
        isBleConnected = connected
        val ecoTag = if (HomeSyncManager.isBatterySaverActive()) " [ECO]" else ""
        tvBleStatus.text = "BLE ($deviceName): $statusText$ecoTag"
        if (connected) {
            tvBlePill.text = "● CONNECTED"
            tvBlePill.setTextColor(0xFF34D399.toInt())
        } else {
            tvBlePill.text = "● STANDBY"
            tvBlePill.setTextColor(0xFF94A3B8.toInt())
        }
    }

    override fun onNavPreviewUpdated(preview: String) {
        val lines = preview.lines()
        val dir = lines.firstOrNull { it.startsWith("DIR") }?.substringAfter(":")?.trim().orEmpty()
        if (dir == "#HOME#") {
            val roadMeta = lines.firstOrNull { it.startsWith("ROAD") }?.substringAfter(":")?.trim().orEmpty()
            val etaMeta = lines.firstOrNull { it.startsWith("ETA") }?.substringAfter(":")?.trim().orEmpty()

            val metaParts = roadMeta.split("|")
            val temp = metaParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "--"
            val city = metaParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Locating..."
            val spd = metaParts.getOrNull(4)?.toIntOrNull() ?: 0

            val headParts = etaMeta.split("|")
            val heading = headParts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "N"
            val alt = headParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "--"
            val aqi = headParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "--"

            tvHudTemp.text = "${temp}°C"
            tvHudCompass.text = heading
            tvHudMeta.text = "$city  ·  ALT $alt  ·  AQI $aqi"
            tvHudSpeed.text = if (switchGpsSpeed.isChecked && !HomeSyncManager.isBatterySaverActive()) {
                spd.toString()
            } else {
                "0"
            }
        }
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun ensureBlePermissionsAndConnect() {
        val permsToAsk = mutableListOf<String>()
        permsToAsk.addAll(requiredBlePermissions())
        permsToAsk.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = permsToAsk.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            HomeSyncManager.tryStartLocationUpdates()
            BleNavManager.startScanAndConnect(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updateNotificationAccessStatus() {
        val enabled = isNotificationServiceEnabled()
        if (enabled) {
            tvNotifStatus.text = "Notification Access: ENABLED"
            tvNotifStatus.setTextColor(0xFF34D399.toInt())
            btnNotifAccess.text = "Notification Access Granted"
        } else {
            tvNotifStatus.text = "Notification Access: DISABLED (Tap below)"
            tvNotifStatus.setTextColor(0xFFEF4444.toInt())
            btnNotifAccess.text = "Enable Notification Access"
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(this, NavNotificationService::class.java).flattenToString()
        return flat.contains(target)
    }
}
