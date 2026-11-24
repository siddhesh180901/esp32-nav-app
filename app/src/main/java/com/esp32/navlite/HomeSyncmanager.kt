package com.esp32.navlite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object HomeSyncManager : SensorEventListener, LocationListener {

    private var appContext: Context? = null
    private var started = false
    private var locationStarted = false

    @Volatile
    var isNavActive: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    private var lastAccel: FloatArray? = null
    private var lastMag: FloatArray? = null
    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var hasSmoothInit = false
    private var lastGpsBearingTime = 0L

    private var currentTime = "--:--"
    private var currentBattery = "--"
    private var currentTemp = "--"
    private var currentCity = "Loc..."
    private var currentWeather = ""
    private var currentSpeedKmh = 0
    private var lastSpeedUpdateMs = 0L
    private var currentHeading = "N"
    private var currentSunriseMin = 390   // default 6:30 am
    private var currentSunsetMin = 1095   // default 6:15 pm
    private var currentAltitude = ""
    private var currentAqi = ""

    private var lastSentPayload = ""
    private var lastSendMs = 0L
    private var lastWeatherFetchMs = 0L
    private var lastWeatherLat = 0.0
    private var lastWeatherLon = 0.0
    private var lastGeoFetchMs = 0L
    private var lastGeoLat = 0.0
    private var lastGeoLon = 0.0

    private val periodicSync = object : Runnable {
        override fun run() {
            refreshTimeAndBattery()
            tryStartLocationUpdates()
            val now = System.currentTimeMillis()
            // Bike thambali aslyas 8 sec nantar speed 0 kar
            if (currentSpeedKmh > 0 && (now - lastSpeedUpdateMs) > 8_000L) {
                currentSpeedKmh = 0
            }
            val elapsed = now - lastSendMs
            if (!isNavActive) {
                sendHomePacket(force = elapsed >= 10_000L)
            } else if (elapsed >= 15_000L) {
                sendHomePacket(force = true)
            }
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        refreshTimeAndBattery()
        tryStartLocationUpdates()

        if (started) {
            if (!isNavActive) sendHomePacket(force = true)
            return
        }
        started = true

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        if (sm != null) {
            val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotSensor != null) {
                sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
            } else {
                val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                if (acc != null && mag != null) {
                    sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
                    sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }

        mainHandler.removeCallbacks(periodicSync)
        mainHandler.post(periodicSync)
    }

    fun setNavigationActive(active: Boolean) {
        isNavActive = active
        if (!active) {
            mainHandler.postDelayed({
                if (!isNavActive) {
                    refreshTimeAndBattery()
                    sendHomePacket(force = true)
                }
            }, 600L)
        }
    }

    @SuppressLint("MissingPermission")
    fun tryStartLocationUpdates() {
        val ctx = appContext ?: return
        if (!hasLocationPermission(ctx)) {
            if (currentCity == "Loc...") currentCity = "No GPS"
            return
        }
        if (locationStarted) return
        locationStarted = true

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        try {
            val bestLast = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }

            if (bestLast != null) {
                handleNewLocation(bestLast)
            }

            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    // Live speed sathi 2 sec / 3 meter interval
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2_000L,
                        3f,
                        this,
                        Looper.getMainLooper()
                    )
                }
            } catch (_: Exception) {}

            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        30_000L,
                        200f,
                        this,
                        Looper.getMainLooper()
                    )
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {
            locationStarted = false
        }
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshTimeAndBattery() {
        val ctx = appContext ?: return
        currentTime = SimpleDateFormat("h:mm a", Locale.US)
            .format(Date())
            .lowercase(Locale.US)

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val pct = if (cap in 0..100) {
            cap
        } else {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        }
        if (pct in 0..100) {
            currentBattery = pct.toString()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (System.currentTimeMillis() - lastGpsBearingTime < 4_000L) return

        var azimuthDeg: Float? = null
        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, orientation)
                azimuthDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.clone()
                val acc = lastAccel
                val mag = lastMag
                if (acc != null && mag != null && SensorManager.getRotationMatrix(rotMatrix, null, acc, mag)) {
                    SensorManager.getOrientation(rotMatrix, orientation)
                    azimuthDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = event.values.clone()
            }
        }

        val rawDeg = azimuthDeg ?: return
        val rad = Math.toRadians(rawDeg.toDouble())
        if (!hasSmoothInit) {
            smoothSin = sin(rad)
            smoothCos = cos(rad)
            hasSmoothInit = true
        } else {
            smoothSin = 0.78 * smoothSin + 0.22 * sin(rad)
            smoothCos = 0.78 * smoothCos + 0.22 * cos(rad)
        }
        val smoothDeg = ((Math.toDegrees(atan2(smoothSin, smoothCos)) + 360.0) % 360.0).toFloat()
        updateHeading(degreesToCardinal(smoothDeg))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun degreesToCardinal(deg: Float): String {
        val norm = ((deg % 360f) + 360f) % 360f
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((norm + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return dirs[idx]
    }

    private fun updateHeading(newHeading: String) {
        if (newHeading == currentHeading) return
        currentHeading = newHeading
        val now = System.currentTimeMillis()
        if (!isNavActive && (now - lastSendMs) >= 1_200L) {
            sendHomePacket(force = true)
        }
    }

    override fun onLocationChanged(location: Location) {
        handleNewLocation(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun handleNewLocation(loc: Location) {
        val now = System.currentTimeMillis()

        // Live GPS Speed (3 km/h peksha kami asel tar drift talnyasathi 0 thev)
        val rawKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        val cleanKmh = if (rawKmh >= 3) rawKmh else 0
        lastSpeedUpdateMs = now
        val speedChanged = (cleanKmh != currentSpeedKmh)
        currentSpeedKmh = cleanKmh

        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.0f) {
            lastGpsBearingTime = now
            updateHeading(degreesToCardinal(loc.bearing))
        }

        if (speedChanged && !isNavActive && (now - lastSendMs) >= 1_000L) {
            sendHomePacket(force = true)
        }

        val lat = loc.latitude
        val lon = loc.longitude

        val geoDist = FloatArray(1)
        if (lastGeoFetchMs > 0L) {
            Location.distanceBetween(lastGeoLat, lastGeoLon, lat, lon, geoDist)
        }
        val needCity = (currentCity == "Loc..." || currentCity == "No GPS") && (now - lastGeoFetchMs) > 20_000L
        if (needCity || (now - lastGeoFetchMs) > 120_000L || geoDist[0] > 500f) {
            lastGeoFetchMs = now
            lastGeoLat = lat
            lastGeoLon = lon
            fetchCityName(lat, lon)
        }

        val weatherDist = FloatArray(1)
        if (lastWeatherFetchMs > 0L) {
            Location.distanceBetween(lastWeatherLat, lastWeatherLon, lat, lon, weatherDist)
        }
        val needTemp = (currentTemp == "--") && (now - lastWeatherFetchMs) > 30_000L
        if (needTemp || (now - lastWeatherFetchMs) > 900_000L || weatherDist[0] > 2_000f) {
            lastWeatherFetchMs = now
            lastWeatherLat = lat
            lastWeatherLon = lon
            fetchWeatherTemp(lat, lon)
        }
    }

    @Suppress("DEPRECATION")
   
    private fun isPureEnglish(s: String): Boolean {
        if (s.isBlank()) return false
        var hasLetter = false
        for (ch in s) {
            if (ch.code !in 32..126) return false
            if (ch in 'A'..'Z' || ch in 'a'..'z') hasLetter = true
        }
        return hasLetter
    }

    private fun cleanCityLabel(raw: String): String {
        var name = raw.trim()
        if (name.length > 10) {
            name = name
                .replace(" West", "", ignoreCase = true)
                .replace(" East", "", ignoreCase = true)
                .replace(" North", "", ignoreCase = true)
                .replace(" South", "", ignoreCase = true)
                .trim()
        }
        return name.replace("|", " ").take(10).trim()
    }

    private fun fetchEnglishCityOnline(lat: Double, lon: Double): String? {
        // 1. OpenStreetMap Nominatim (suburb/area exact English madhe deto)
        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&accept-language=en"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "ESP32NavLite/1.0")
                setRequestProperty("Accept-Language", "en")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val addr = JSONObject(body).optJSONObject("address")
                if (addr != null) {
                    val candidates = listOf(
                        addr.optString("suburb"),
                        addr.optString("neighbourhood"),
                        addr.optString("city_district"),
                        addr.optString("town"),
                        addr.optString("city"),
                        addr.optString("state_district")
                    )
                    val match = candidates.firstOrNull { isPureEnglish(it) }
                    if (match != null) {
                        conn.disconnect()
                        return match
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}

        // 2. Backup: BigDataCloud (English forced)
        try {
            val urlStr = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val candidates = listOf(
                    root.optString("locality"),
                    root.optString("city"),
                    root.optString("principalSubdivision")
                )
                val match = candidates.firstOrNull { isPureEnglish(it) }
                if (match != null) {
                    conn.disconnect()
                    return match
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}

        return null
    }

    @Suppress("DEPRECATION")
    private fun fetchCityName(lat: Double, lon: Double) {
        val ctx = appContext ?: return
        Thread {
            try {
                var picked: String? = null

                // Pahile phone chya Geocoder madhun check kar (fakt pure English asel tarach ghe)
                try {
                    val geocoder = Geocoder(ctx, Locale.US)
                    val list = geocoder.getFromLocation(lat, lon, 1)
                    val addr = list?.firstOrNull()
                    if (addr != null) {
                        val candidates = listOfNotNull(
                            addr.subLocality,
                            addr.locality,
                            addr.subAdminArea,
                            addr.adminArea
                        )
                        picked = candidates.firstOrNull { isPureEnglish(it) }
                    }
                } catch (_: Exception) {}

                // Samsung S24 sarkhya phone var Marathi aala tar online English name ghe
                if (picked == null) {
                    picked = fetchEnglishCityOnline(lat, lon)
                }

                val cleanCity = picked?.let { cleanCityLabel(it) }.orEmpty()
                if (cleanCity.isNotEmpty()) {
                    mainHandler.post {
                        currentCity = cleanCity
                        if (!isNavActive) sendHomePacket(force = true)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun weatherCodeToLabel(code: Int, isDay: Int): String {
        return when (code) {
            0 -> if (isDay == 1) "Sunny" else "Clear"
            1, 2 -> "Cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 65, 66, 67, 80, 81, 82 -> "Rain!"
            95, 96, 99 -> "Storm!"
            else -> ""
        }
    }

   private fun parseIsoTimeToMinutes(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        val tIdx = iso.indexOf('T')
        if (tIdx < 0) return null
        val parts = iso.substring(tIdx + 1).split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun fetchWeatherTemp(lat: Double, lon: Double) {
        Thread {
            // 1. Open-Meteo Forecast: Temp + Weather + Sunrise/Sunset + Exact Elevation (Altitude)
            try {
                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&timezone=auto&forecast_days=1"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(body)
                    val cur = root.getJSONObject("current")
                    val temp = cur.getDouble("temperature_2m").roundToInt().toString()
                    val code = cur.optInt("weather_code", -1)
                    val isDay = cur.optInt("is_day", 1)
                    val label = weatherCodeToLabel(code, isDay)

                    val elev = root.optDouble("elevation", Double.NaN)
                    val altLabel = if (!elev.isNaN()) "${elev.roundToInt()}m" else ""

                    val daily = root.optJSONObject("daily")
                    val riseMin = parseIsoTimeToMinutes(daily?.optJSONArray("sunrise")?.optString(0))
                    val setMin = parseIsoTimeToMinutes(daily?.optJSONArray("sunset")?.optString(0))

                    mainHandler.post {
                        currentTemp = temp
                        currentWeather = label
                        if (altLabel.isNotEmpty()) currentAltitude = altLabel
                        if (riseMin != null) currentSunriseMin = riseMin
                        if (setMin != null) currentSunsetMin = setMin
                        sendHomePacket(force = true)
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            // 2. Open-Meteo Air Quality: Live US AQI
            try {
                val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi"
                val conn = (URL(aqiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val aqiVal = JSONObject(body)
                        .optJSONObject("current")
                        ?.optDouble("us_aqi", Double.NaN) ?: Double.NaN
                    if (!aqiVal.isNaN() && aqiVal >= 0.0) {
                        val aqiStr = aqiVal.roundToInt().toString()
                        mainHandler.post {
                            currentAqi = aqiStr
                            sendHomePacket(force = true)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun sendHomePacket(force: Boolean) {
        val meta = "$currentBattery|$currentTemp|$currentCity|$currentWeather|$currentSpeedKmh|$currentSunriseMin|$currentSunsetMin|$currentAltitude|$currentAqi"
        val payload = "$currentTime|$meta|$currentHeading"
        if (!force && payload == lastSentPayload) return

        lastSentPayload = payload
        lastSendMs = System.currentTimeMillis()

        val homePacket = NavData(
            active = true,
            distance = currentTime,
            directions = "#HOME#",
            title = meta,
            duration = "",
            eta = currentHeading
        )

        mainHandler.post {
            BleNavManager.sendNavigation(homePacket, force = true)
        }
    }
}
