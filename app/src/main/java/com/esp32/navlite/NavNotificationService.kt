package com.esp32.navlite

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.math.abs

class NavNotificationService : NotificationListenerService() {

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"

        private val CALL_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.oneplus.dialer",
            "com.miui.phone",
            "com.truecaller"
        )

        private val MSG_APP_LABELS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WA Biz",
            "com.google.android.apps.messaging" to "SMS",
            "com.android.mms" to "SMS",
            "com.samsung.android.messaging" to "SMS",
            "com.oneplus.mms" to "SMS",
            "com.coloros.mms" to "SMS",
            "com.miui.mms" to "SMS",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram"
        )
    }

    private var activeCallKey: String? = null
    private var lastMsgKey: String = ""
    private var lastMsgTimeMs: Long = 0L

    // Live Music Tracking (Spotify, YT Music, Local Player)
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = mutableListOf<MediaController>()
    private var lastSentMusicKey: String = ""

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        attachMediaControllers(controllers ?: emptyList())
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncCurrentMusic(force = false)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncCurrentMusic(force = true)
        }
    }

    private fun cleanSpaces(s: String?): String {
        return s.orEmpty()
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\u2007', ' ')
            .replace('\u2009', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun toOledAscii(s: String, fallback: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.code in 32..126) sb.append(ch)
        }
        val clean = sb.toString().replace(Regex("\\s+"), " ").trim()
        return if (clean.isNotEmpty()) clean else fallback
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        HomeSyncManager.start(applicationContext)
        BleNavManager.ensureAutoReconnect(applicationContext) // <--- HI 1 LINE ADD KAR
        setupMediaTracking()
    }

    override fun onDestroy() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {}
        detachAllMediaControllers()
        super.onDestroy()
    }

    private fun setupMediaTracking() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        mediaSessionManager = msm
        val comp = ComponentName(this, NavNotificationService::class.java)
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp)
            attachMediaControllers(msm.getActiveSessions(comp))
        } catch (_: Exception) {}
    }

    private fun refreshActiveMediaSessions() {
        val msm = mediaSessionManager ?: run {
            setupMediaTracking()
            return
        }
        val comp = ComponentName(this, NavNotificationService::class.java)
        try {
            attachMediaControllers(msm.getActiveSessions(comp))
        } catch (_: Exception) {}
    }

    private fun detachAllMediaControllers() {
        for (c in activeControllers) {
            try { c.unregisterCallback(mediaCallback) } catch (_: Exception) {}
        }
        activeControllers.clear()
    }

    private fun attachMediaControllers(controllers: List<MediaController>) {
        detachAllMediaControllers()
        for (c in controllers) {
            activeControllers.add(c)
            try { c.registerCallback(mediaCallback) } catch (_: Exception) {}
        }
        syncCurrentMusic(force = true)
    }

    private fun syncCurrentMusic(force: Boolean) {
        val playing = activeControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        if (playing == null) {
            if (lastSentMusicKey != "0") {
                lastSentMusicKey = "0"
                BleNavManager.sendNavigation(
                    NavData(
                        active = true,
                        distance = "0",
                        directions = "#MUSIC#",
                        title = "",
                        duration = "",
                        eta = "0|0"
                    ),
                    force = true
                )
            }
            return
        }

        val meta = playing.metadata
        val rawSong = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Playing"
        val songTitle = toOledAscii(cleanSpaces(rawSong), "Playing").take(28)

        val durMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = playing.playbackState
        var posMs = state?.position ?: 0L
        if (state != null && state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) {
            val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
            posMs += (delta * speed).toLong()
        }
        if (durMs > 0L) {
            posMs = posMs.coerceIn(0L, durMs)
        } else if (posMs < 0L) {
            posMs = 0L
        }

        val posSec = (posMs / 1000L).toInt()
        val durSec = (durMs / 1000L).toInt()
        val key = "1|$songTitle|$durSec|${posSec / 3}"

        if (!force && key == lastSentMusicKey) return
        lastSentMusicKey = key

        BleNavManager.sendNavigation(
            NavData(
                active = true,
                distance = "1",
                directions = "#MUSIC#",
                title = songTitle,
                duration = "",
                eta = "$posSec|$durSec"
            ),
            force = true
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Media player notification aali tar session refresh kar
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            notification.category == Notification.CATEGORY_TRANSPORT
        ) {
            refreshActiveMediaSessions()
        }

        // 1. Google Maps Turn-by-Turn Navigation
        if (pkg == MAPS_PACKAGE) {
            handleMapsNotification(sbn, notification, extras)
            return
        }

        // 2. Incoming Call Alert
        if (handleCallNotification(sbn, pkg, notification, extras)) {
            return
        }

        // 3. Incoming Message Alert
        handleMessageNotification(sbn, pkg, notification, extras)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        if (pkg == MAPS_PACKAGE) {
            if (!sbn.isOngoing) return
            BleNavManager.sendNavigation(NavData(active = false), force = true)
            HomeSyncManager.setNavigationActive(false)
            return
        }

        if (sbn.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            sbn.notification?.category == Notification.CATEGORY_TRANSPORT
        ) {
            refreshActiveMediaSessions()
        }

        if (activeCallKey != null && (sbn.key == activeCallKey || pkg in CALL_PACKAGES || sbn.notification?.category == Notification.CATEGORY_CALL)) {
            activeCallKey = null
            BleNavManager.sendNavigation(
                NavData(active = true, directions = "#CALL_END#"),
                force = true
            )
        }
    }

    private fun handleCallNotification(
        sbn: StatusBarNotification,
        pkg: String,
        notification: Notification,
        extras: Bundle
    ): Boolean {
        val rawTitle = cleanSpaces(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val rawText = cleanSpaces(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val combined = "$rawTitle $rawText".lowercase()

        val hasAnswerAction = notification.actions?.any { act ->
            val t = act.title?.toString()?.lowercase().orEmpty()
            t.contains("answer") || t.contains("accept") || t.contains("decline")
        } == true

        val isCallCategory = notification.category == Notification.CATEGORY_CALL
        val isIncomingText = combined.contains("incoming") ||
            combined.contains("ringing") ||
            combined.contains("voice call") ||
            combined.contains("video call")

        val isCallRelated = isCallCategory || hasAnswerAction || (pkg in CALL_PACKAGES)
        if (!isCallRelated) return false

        val isOngoingActiveCall = combined.contains("ongoing") ||
            combined.contains("on hold") ||
            combined.contains("dialing") ||
            combined.contains("missed call") ||
            Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(rawText)

        if (isOngoingActiveCall && !hasAnswerAction) {
            if (activeCallKey != null) {
                activeCallKey = null
                BleNavManager.sendNavigation(
                    NavData(active = true, directions = "#CALL_END#"),
                    force = true
                )
            }
            return true
        }

        if (!hasAnswerAction && !isIncomingText && !isCallCategory) return true

        val callerRaw = if (rawTitle.isNotEmpty() && !rawTitle.equals("Phone", true) && !rawTitle.equals("WhatsApp", true)) {
            rawTitle
        } else {
            rawText
        }
        val callerName = toOledAscii(callerRaw, "Unknown").take(18)
        val callLabel = if (pkg.contains("whatsapp")) "WHATSAPP CALL" else "INCOMING CALL"

        activeCallKey = sbn.key
        BleNavManager.sendNavigation(
            NavData(
                active = true,
                distance = callLabel,
                directions = "#CALL#",
                title = callerName,
                duration = "",
                eta = ""
            ),
            force = true
        )
        return true
    }

    private fun handleMessageNotification(
        sbn: StatusBarNotification,
        pkg: String,
        notification: Notification,
        extras: Bundle
    ) {
        if (sbn.isOngoing) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val appLabel = MSG_APP_LABELS[pkg]
            ?: if (notification.category == Notification.CATEGORY_MESSAGE) "Message" else return

        val rawTitle = cleanSpaces(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val rawText = cleanSpaces(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        if (rawTitle.isEmpty()) return

        val lowerTitle = rawTitle.lowercase()
        val lowerText = rawText.lowercase()

        if (lowerTitle == "whatsapp" ||
            lowerTitle == "messages" ||
            lowerTitle.contains("checking for") ||
            lowerTitle.contains("backing up") ||
            lowerText.contains("checking for new") ||
            lowerText.contains("whatsapp web") ||
            lowerText.contains("new messages") ||
            lowerText.contains("messages from")
        ) return

        val cleanedTitle = rawTitle
            .replace(Regex("\\s*\\(\\d+\\s+messages?\\)", RegexOption.IGNORE_CASE), "")
            .trim()

        val senderName = toOledAscii(cleanedTitle, "New Msg").take(18)
        val dedupKey = "$appLabel|$senderName|$rawText"
        val now = System.currentTimeMillis()
        if (dedupKey == lastMsgKey && (now - lastMsgTimeMs) < 4_000L) return
        lastMsgKey = dedupKey
        lastMsgTimeMs = now

        BleNavManager.sendNavigation(
            NavData(
                active = true,
                distance = appLabel,
                directions = "#MSG#",
                title = senderName,
                duration = "",
                eta = ""
            ),
            force = true
        )
    }

    private fun handleMapsNotification(
        sbn: StatusBarNotification,
        notification: Notification,
        extras: Bundle
    ) {
        if (!sbn.isOngoing) return

        val rawTitle = cleanSpaces(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val rawText = cleanSpaces(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val rawBigText = cleanSpaces(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        val rawSubText = cleanSpaces(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())

        val combined = "$rawTitle $rawText $rawBigText".lowercase()

        if (combined.contains("location sharing") ||
            combined.contains("sharing your") ||
            combined.contains("offline map") ||
            combined.contains("downloading") ||
            combined.contains("timeline") ||
            combined.contains("using your location") ||
            combined.contains("commute")
        ) return

        val turnBitmap = extractTurnBitmap(notification, extras)
        val distanceRegex = Regex("\\b\\d+(\\.\\d+)?\\s?(m|km|ft|mi|मी|किमी)\\b", RegexOption.IGNORE_CASE)
        val distanceMatch = distanceRegex.find(rawTitle) ?: distanceRegex.find(rawText)

        val isNavCategory = notification.category == Notification.CATEGORY_NAVIGATION
        val hasExitAction = notification.actions?.any {
            it.title?.toString()?.contains("exit", ignoreCase = true) == true
        } == true
        val hasEtaSubText = rawSubText.contains("min", true) ||
            rawSubText.contains("hr", true) ||
            rawSubText.contains("·") ||
            rawSubText.contains("•")

        if (!isNavCategory && !hasExitAction && distanceMatch == null && !hasEtaSubText) return
        if (rawTitle.isEmpty() && rawText.isEmpty() && turnBitmap == null) return

        val textDirection = when {
            combined.contains("u-turn") || combined.contains("uturn") ||
                combined.contains("u turn") || combined.contains("यू-टर्न") -> "U-Turn"
            combined.contains("left") || combined.contains("डावीकडे") ||
                combined.contains("डाव्या") || combined.contains("बाएं") -> "Turn Left"
            combined.contains("right") || combined.contains("उजवीकडे") ||
                combined.contains("उजव्या") || combined.contains("दाएं") -> "Turn Right"
            else -> null
        }

        val directionLabel = textDirection
            ?: turnBitmap?.let { detectDirectionFromBitmap(it) }
            ?: "Straight"

        val distance = distanceMatch?.value ?: rawTitle.take(12)
        val streetTitle = if (rawText.isNotEmpty()) rawText else rawTitle

        val parts = rawSubText.split("·", "•", "-")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val duration = parts.getOrNull(0) ?: ""
        val eta = parts.lastOrNull {
            it.contains(":") || it.contains("AM", true) || it.contains("PM", true)
        } ?: parts.getOrNull(1) ?: rawSubText.take(16)

        val navData = NavData(
            active = true,
            distance = distance,
            directions = directionLabel,
            title = streetTitle,
            duration = duration,
            eta = eta
        )

        HomeSyncManager.setNavigationActive(true)
        BleNavManager.sendNavigation(navData)
    }

    private fun extractTurnBitmap(notification: Notification, extras: Bundle): Bitmap? {
        return try {
            notification.getLargeIcon()?.let { loadFromIcon(it) }
                ?: extractFromExtra(extras, "android.largeIcon.big")
                ?: extractFromExtra(extras, "android.largeIcon")
                ?: extractFromExtra(extras, "android.progressTrackerIcon")
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun extractFromExtra(extras: Bundle, key: String): Bitmap? {
        return when (val obj = extras.get(key)) {
            is Bitmap -> toSoftware48Bitmap(obj)
            is Icon -> loadFromIcon(obj)
            else -> null
        }
    }

    private fun loadFromIcon(icon: Icon): Bitmap? {
        val drawable = icon.loadDrawable(this) ?: return null
        return drawableTo48Bitmap(drawable)
    }

    private fun toSoftware48Bitmap(src: Bitmap): Bitmap {
        val safeSrc = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            src
        }
        return Bitmap.createScaledBitmap(safeSrc, 48, 48, true)
    }

    private fun drawableTo48Bitmap(drawable: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(canvas)
        return bmp
    }

    private fun detectDirectionFromBitmap(bmp: Bitmap): String {
        val size = 48
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)

        val corners = listOf(
            pixels[0],
            pixels[size - 1],
            pixels[(size - 1) * size],
            pixels[size * size - 1]
        )
        val avgCornerAlpha = corners.map { Color.alpha(it) }.average()
        val bgR = corners.map { Color.red(it) }.average()
        val bgG = corners.map { Color.green(it) }.average()
        val bgB = corners.map { Color.blue(it) }.average()

        val fg = Array(size) { BooleanArray(size) }

        if (avgCornerAlpha < 50.0) {
            var maxLum = 0.0
            var minLum = 255.0
            for (p in pixels) {
                if (Color.alpha(p) >= 160) {
                    val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                    if (lum > maxLum) maxLum = lum
                    if (lum < minLum) minLum = lum
                }
            }
            val filterDimmed = (maxLum > 200.0 && (maxLum - minLum) > 60.0)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val p = pixels[y * size + x]
                    val a = Color.alpha(p)
                    if (a >= 165) {
                        if (filterDimmed) {
                            val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                            fg[y][x] = lum >= maxLum * 0.72
                        } else {
                            fg[y][x] = true
                        }
                    }
                }
            }
        } else {
            var maxDist = 0.0
            val dists = DoubleArray(size * size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val d = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)
                dists[i] = d
                if (d > maxDist) maxDist = d
            }
            if (maxDist < 60.0) return "Straight"
            val thresh = maxDist * 0.55
            for (y in 0 until size) {
                for (x in 0 until size) {
                    fg[y][x] = dists[y * size + x] >= thresh
                }
            }
        }

        var minX = size
        var maxX = -1
        var minY = size
        var maxY = -1
        var count = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (fg[y][x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    count++
                }
            }
        }

        val w = maxX - minX + 1
        val h = maxY - minY + 1
        if (count < 15 || w < 5 || h < 5) return "Straight"

        var twoLegRows = 0
        val yStart = minY + (h * 0.65).toInt()
        val yEnd = minY + (h * 0.88).toInt()
        val totalCheckedRows = (yEnd - yStart + 1).coerceAtLeast(1)

        for (y in yStart..yEnd) {
            var runs = 0
            var inRun = false
            var runLen = 0
            var gapLen = 0
            for (x in minX..maxX) {
                if (fg[y][x]) {
                    if (!inRun) {
                        if (runs == 0 || gapLen >= 2) {
                            inRun = true
                            runLen = 1
                        } else {
                            inRun = true
                        }
                    } else {
                        runLen++
                    }
                    gapLen = 0
                } else {
                    if (inRun) {
                        if (runLen >= 2) runs++
                        inRun = false
                        runLen = 0
                    }
                    gapLen++
                }
            }
            if (inRun && runLen >= 2) runs++
            if (runs >= 2) twoLegRows++
        }

        var topCenterPixels = 0
        for (y in minY..(minY + (h * 0.30).toInt())) {
            for (x in (minX + (w * 0.35).toInt())..(minX + (w * 0.65).toInt())) {
                if (fg[y][x]) topCenterPixels++
            }
        }

        if (twoLegRows.toDouble() / totalCheckedRows >= 0.45 && topCenterPixels >= 4) {
            return "U-Turn"
        }

        var sumXTop = 0.0
        var cntTop = 0
        var sumXBottom = 0.0
        var cntBottom = 0
        var leftMass = 0
        var rightMass = 0

        for (y in minY..maxY) {
            val ny = (y - minY).toDouble() / (h - 1).coerceAtLeast(1)
            for (x in minX..maxX) {
                if (!fg[y][x]) continue
                val nx = (x - minX).toDouble() / (w - 1).coerceAtLeast(1)
                if (ny <= 0.52) {
                    sumXTop += nx
                    cntTop++
                }
                if (ny >= 0.72) {
                    sumXBottom += nx
                    cntBottom++
                }
                if (nx < 0.45) leftMass++
                if (nx > 0.55) rightMass++
            }
        }

        val cxTop = if (cntTop > 0) sumXTop / cntTop else 0.5
        val cxBottom = if (cntBottom > 0) sumXBottom / cntBottom else 0.5
        val shift = cxTop - cxBottom

        if (shift < -0.09) return "Turn Left"
        if (shift > 0.09) return "Turn Right"

        val totalSide = (leftMass + rightMass).coerceAtLeast(1)
        val sideBias = (rightMass - leftMass).toDouble() / totalSide
        if (sideBias < -0.22) return "Turn Left"
        if (sideBias > 0.22) return "Turn Right"

        return "Straight"
    }
}
