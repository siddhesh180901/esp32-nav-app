package com.esp32.navlite

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), BleNavManager.Listener, LocationListener {

    companion object {
        private const val PREFS_NAME = "esp32_nav_hud_prefs"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_GPS_SPEED = "gps_speed_enabled"
        private const val KEY_TRIP_HISTORY = "trip_history"
        private const val KEY_FUEL_HISTORY = "fuel_history"
    }

    private lateinit var tvBlePill: TextView
    private lateinit var tvHudCompassTape: TextView
    private lateinit var tvHudSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvTripDist: TextView
    private lateinit var tvTripTime: TextView
    private lateinit var tvTripSummary: TextView
    private lateinit var tvCardTemp: TextView
    private lateinit var tvCardCity: TextView
    private lateinit var tvCardAqiAlt: TextView
    private lateinit var tvBleStatus: TextView
    private lateinit var tvNotifStatus: TextView

    private lateinit var switchAutoReconnect: Switch
    private lateinit var switchGpsSpeed: Switch
    private lateinit var btnTripToggle: Button
    private lateinit var btnTripsFuelLog: Button
    private lateinit var btnNotifAccess: Button
    private lateinit var btnScanConnect: Button
    private lateinit var btnDisconnect: Button

    private val handler = Handler(Looper.getMainLooper())
    private var isBleConnected = false

    private var isTripRunning = false
    private var tripStartMs = 0L
    private var tripDistanceMeters = 0.0
    private var maxSpeedKmh = 0
    private var currentSpeedKmh = 0
    private var lastTripLocation: Location? = null

    private val tripTicker = object : Runnable {
        override fun run() {
            if (isTripRunning) {
                refreshTripUi()
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        HomeSyncManager.tryStartLocationUpdates()
        if (switchGpsSpeed.isChecked && !HomeSyncManager.isBatterySaverActive()) {
            startTripLocationUpdates()
        }
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

        if (switchGpsSpeed.isChecked && !HomeSyncManager.isBatterySaverActive()) {
            startTripLocationUpdates()
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
            if (isChecked && !HomeSyncManager.isAutoEcoActive()) {
                startTripLocationUpdates()
            } else {
                stopTripLocationUpdates()
                currentSpeedKmh = 0
                tvHudSpeed.text = "0"
            }
        }

        btnTripToggle.setOnClickListener {
            if (!isTripRunning) {
                startNewTrip()
            } else {
                stopAndSaveTrip()
            }
        }

        btnTripsFuelLog.setOnClickListener {
            showTripsAndFuelDialog()
        }

        btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnScanConnect.setOnClickListener {
            ensureBlePermissionsAndConnect()
        }

        btnDisconnect.setOnClickListener {
            BleNavManager.disconnect()
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

        refreshTripUi()
        refreshSummaryText()
        handler.removeCallbacks(tripTicker)
        handler.post(tripTicker)
    }

    private fun bindViews() {
        tvBlePill = findViewById(R.id.tvBlePill)
        tvHudCompassTape = findViewById(R.id.tvHudCompassTape)
        tvHudSpeed = findViewById(R.id.tvHudSpeed)
        tvMaxSpeed = findViewById(R.id.tvMaxSpeed)
        tvTripDist = findViewById(R.id.tvTripDist)
        tvTripTime = findViewById(R.id.tvTripTime)
        tvTripSummary = findViewById(R.id.tvTripSummary)
        tvCardTemp = findViewById(R.id.tvCardTemp)
        tvCardCity = findViewById(R.id.tvCardCity)
        tvCardAqiAlt = findViewById(R.id.tvCardAqiAlt)
        tvBleStatus = findViewById(R.id.tvBleStatus)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)

        switchAutoReconnect = findViewById(R.id.switchAutoReconnect)
        switchGpsSpeed = findViewById(R.id.switchGpsSpeed)
        btnTripToggle = findViewById(R.id.btnTripToggle)
        btnTripsFuelLog = findViewById(R.id.btnTripsFuelLog)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        btnScanConnect = findViewById(R.id.btnScanConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchAutoReconnect.isChecked = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        switchGpsSpeed.isChecked = prefs.getBoolean(KEY_GPS_SPEED, true)
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
        handler.removeCallbacks(tripTicker)
        stopTripLocationUpdates()
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
            val city = metaParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "--"
            val spd = metaParts.getOrNull(4)?.toIntOrNull()

            val headParts = etaMeta.split("|")
            val heading = headParts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "NE"
            val alt = headParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "--"
            val aqi = headParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "--"

            tvCardTemp.text = "${temp}°C"
            tvCardCity.text = city
            tvCardAqiAlt.text = "AQI $aqi · $alt"
            tvHudCompassTape.text = "←  $heading  →"

            if (switchGpsSpeed.isChecked && !HomeSyncManager.isBatterySaverActive() && spd != null && spd >= 0) {
                currentSpeedKmh = spd
                tvHudSpeed.text = spd.toString()
                if (isTripRunning && spd > maxSpeedKmh) {
                    maxSpeedKmh = spd
                    refreshTripUi()
                }
            }
        }
    }

    private fun startNewTrip() {
        isTripRunning = true
        tripStartMs = System.currentTimeMillis()
        tripDistanceMeters = 0.0
        maxSpeedKmh = currentSpeedKmh
        lastTripLocation = null
        btnTripToggle.text = "STOP & SAVE TRIP"
        refreshTripUi()
        Toast.makeText(this, "Trip started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAndSaveTrip() {
        if (!isTripRunning) return
        val elapsedMin = ((System.currentTimeMillis() - tripStartMs) / 60_000L).coerceAtLeast(1L)
        val distKm = tripDistanceMeters / 1000.0
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())
        val entry = "$stamp | ${String.format(Locale.US, "%.1f", distKm)}km | Max ${maxSpeedKmh}km/h | ${elapsedMin}m"

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TRIP_HISTORY, "") ?: ""
        val updated = if (existing.isEmpty()) entry else "$entry\n$existing"
        prefs.edit().putString(KEY_TRIP_HISTORY, updated.lines().take(25).joinToString("\n")).apply()

        isTripRunning = false
        lastTripLocation = null
        btnTripToggle.text = "START TRIP"
        refreshTripUi()
        refreshSummaryText()
        Toast.makeText(this, "Trip saved", Toast.LENGTH_SHORT).show()
    }

    private fun refreshTripUi() {
        val elapsedSec = if (isTripRunning) ((System.currentTimeMillis() - tripStartMs) / 1000L) else 0L
        val mins = elapsedSec / 60L
        val hrs = mins / 60L
        val remMin = mins % 60L
        tvTripTime.text = if (hrs > 0) "${hrs}h ${remMin}m" else "${mins}m"
        tvTripDist.text = String.format(Locale.US, "%.1f km", tripDistanceMeters / 1000.0)
        tvMaxSpeed.text = "$maxSpeedKmh km/h"
    }

    private fun refreshSummaryText() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trips = (prefs.getString(KEY_TRIP_HISTORY, "") ?: "").lines().filter { it.isNotBlank() }
        val fuel = (prefs.getString(KEY_FUEL_HISTORY, "") ?: "").lines().filter { it.isNotBlank() }
        val lastTrip = trips.firstOrNull() ?: "No trips yet"
        val lastFuel = fuel.firstOrNull() ?: "No fuel entries"
        tvTripSummary.text = "Trip: $lastTrip\nFuel: $lastFuel"
    }

    private fun showTripsAndFuelDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trips = prefs.getString(KEY_TRIP_HISTORY, "")?.ifBlank { "No saved trips yet." } ?: "No saved trips yet."
        val fuel = prefs.getString(KEY_FUEL_HISTORY, "")?.ifBlank { "No fuel entries yet." } ?: "No fuel entries yet."

        AlertDialog.Builder(this)
            .setTitle("Previous Trips & Fuel Log")
            .setMessage("--- PREVIOUS TRIPS ---\n$trips\n\n--- FUEL LOG ---\n$fuel")
            .setPositiveButton("Add Fuel Entry") { _, _ -> showAddFuelDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddFuelDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 5.0L @ ₹530 (Full Tank)"
        }
        AlertDialog.Builder(this)
            .setTitle("Log Fuel Top-Up / Full Tank")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val note = input.text?.toString()?.trim().orEmpty()
                if (note.isNotEmpty()) {
                    val stamp = SimpleDateFormat("dd MMM", Locale.US).format(Date())
                    val entry = "$stamp: $note"
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val existing = prefs.getString(KEY_FUEL_HISTORY, "") ?: ""
                    val updated = if (existing.isEmpty()) entry else "$entry\n$existing"
                    prefs.edit().putString(KEY_FUEL_HISTORY, updated.lines().take(25).joinToString("\n")).apply()
                    refreshSummaryText()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("MissingPermission")
    private fun startTripLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 2f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
    }

    @Suppress("MissingPermission")
    private fun stopTripLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        if (!switchGpsSpeed.isChecked || HomeSyncManager.isBatterySaverActive()) return
        val rawKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        val cleanKmh = if (rawKmh >= 3) rawKmh else 0
        currentSpeedKmh = cleanKmh
        tvHudSpeed.text = cleanKmh.toString()

        if (isTripRunning) {
            if (cleanKmh > maxSpeedKmh) {
                maxSpeedKmh = cleanKmh
            }
            val prev = lastTripLocation
            if (prev != null && cleanKmh >= 3) {
                val dist = prev.distanceTo(location).toDouble()
                if (dist in 1.5..500.0) {
                    tripDistanceMeters += dist
                }
            }
            lastTripLocation = location
            refreshTripUi()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

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
            if (switchGpsSpeed.isChecked && !HomeSyncManager.isBatterySaverActive()) {
                startTripLocationUpdates()
            }
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
