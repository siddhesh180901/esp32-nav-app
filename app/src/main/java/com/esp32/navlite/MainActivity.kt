package com.esp32.navlite

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BleNavManager.Listener {

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var tvLivePreview: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            BleNavManager.startScanAndConnect(this)
        } else {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        tvLivePreview = findViewById(R.id.tvLivePreview)

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            ensureBlePermissionsAndConnect()
        HomeSyncManager.start(applicationContext)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                201
            )
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            BleNavManager.disconnect()
        }

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnTestLeft).setOnClickListener {
            BleNavManager.sendNavigation(
                NavData(true, "200 m", "Turn Left", "MG Road", "5 min", "11:15 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestStraight).setOnClickListener {
            BleNavManager.sendNavigation(
                NavData(true, "500 m", "Straight", "Highway", "8 min", "11:18 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestRight).setOnClickListener {
            BleNavManager.sendNavigation(
                NavData(true, "150 m", "Turn Right", "Station Rd", "4 min", "11:14 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestUturn).setOnClickListener {
            BleNavManager.sendNavigation(
                NavData(true, "50 m", "U-Turn", "Main Chowk", "2 min", "11:12 AM"),
                force = true
            )
        }

        findViewById<Button>(R.id.btnTestStop).setOnClickListener {
            BleNavManager.sendNavigation(NavData(active = false), force = true)
        }
    }

    override fun onResume() {
        super.onResume()
        BleNavManager.setListener(this)
        updateNotificationAccessStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        BleNavManager.setListener(null)
    }

    private fun updateNotificationAccessStatus() {
        val enabled = isNotificationServiceEnabled()
        tvNotifStatus.text = if (enabled) {
            "Google Maps Notification Access: ENABLED"
        } else {
            "Google Maps Notification Access: DISABLED (Tap below)"
        }
        tvNotifStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.accent_green else R.color.accent_red
            )
        )
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, NavNotificationService::class.java).flattenToString()
        return flat.contains(cn)
    }

    private fun ensureBlePermissionsAndConnect() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            BleNavManager.startScanAndConnect(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStatusChanged(connected: Boolean, statusText: String, deviceName: String) {
        tvStatus.text = statusText
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) R.color.accent_green else R.color.accent_red
            )
        )
        tvDeviceInfo.text = "Target: $deviceName"
    }

    override fun onNavPreviewUpdated(preview: String) {
        tvLivePreview.text = preview
    }
}
