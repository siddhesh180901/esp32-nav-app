package com.esp32.navlite

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.math.abs

class NavNotificationService : NotificationListenerService() {

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PACKAGE) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val rawBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val turnBitmap = extractTurnBitmap(notification, extras)

        // Ignore non-navigation Maps popups (like "Rate this place")
        if (!sbn.isOngoing && turnBitmap == null) return
        if (rawTitle.isEmpty() && rawText.isEmpty() && turnBitmap == null) return

        val combined = "$rawTitle $rawText $rawBigText".lowercase()

        // 1. First check if Maps wrote turn text (English / Marathi / Hindi)
        val textDirection = when {
            combined.contains("u-turn") || combined.contains("uturn") ||
                combined.contains("u turn") || combined.contains("यू-टर्न") -> "U-Turn"
            combined.contains("left") || combined.contains("डावीकडे") ||
                combined.contains("डाव्या") || combined.contains("बाएं") -> "Turn Left"
            combined.contains("right") || combined.contains("उजवीकडे") ||
                combined.contains("उजव्या") || combined.contains("दाएं") -> "Turn Right"
            else -> null
        }

        // 2. If text has no direction, read the small side arrow icon from Maps notification
        val directionLabel = textDirection
            ?: turnBitmap?.let { detectDirectionFromBitmap(it) }
            ?: "Straight"

        // Extract distance (e.g. "250 m", "1.2 km")
        val distanceRegex = Regex("\\b\\d+(\\.\\d+)?\\s?(m|km|ft|mi|मी|किमी)\\b", RegexOption.IGNORE_CASE)
        val distanceMatch = distanceRegex.find(rawTitle) ?: distanceRegex.find(rawText)
        val distance = distanceMatch?.value ?: rawTitle.take(12)

        val streetTitle = if (rawText.isNotEmpty()) rawText else rawTitle

        // Parse ETA / duration from subText
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

        BleNavManager.sendNavigation(navData)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PACKAGE) return
        if (!sbn.isOngoing) return
        BleNavManager.sendNavigation(NavData(active = false), force = true)
    }

    private fun extractTurnBitmap(notification: Notification, extras: Bundle): Bitmap? {
        return try {
            // Standard notification largeIcon (where Google Maps puts the side turn arrow)
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

    /**
     * Analyzes Google Maps's turn arrow icon shape:
     * - Two vertical legs in bottom half -> U-Turn
     * - Bottom entry stem on right, arrow leaning left -> Turn Left
     * - Bottom entry stem on left, arrow leaning right -> Turn Right
     * - Symmetric vertical shaft -> Straight
     */
    private fun detectDirectionFromBitmap(bmp: Bitmap): String {
        val size = 48
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)

        // Inspect 4 corners to see if background is transparent or solid colored
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
            // Transparent background: find brightest opaque pixels (ignores dimmed fork branches)
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
            // Solid background (e.g. Maps dark green): compare color distance from corner background
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

        // Find tight bounding box of the arrow
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

        // 1. Check for U-Turn: two distinct vertical legs in the lower section (65%..88% height)
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

        // Check top arch presence for U-Turn
        var topCenterPixels = 0
        for (y in minY..(minY + (h * 0.30).toInt())) {
            for (x in (minX + (w * 0.35).toInt())..(minX + (w * 0.65).toInt())) {
                if (fg[y][x]) topCenterPixels++
            }
        }

        if (twoLegRows.toDouble() / totalCheckedRows >= 0.45 && topCenterPixels >= 4) {
            return "U-Turn"
        }

        // 2. Compare horizontal center of top half vs bottom stem
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

        // Standard Google Maps turn icons:
        // Left turn: stem at bottom-right (cxBottom ~0.75), arrow bends left (cxTop ~0.42) -> shift < 0
        // Right turn: stem at bottom-left (cxBottom ~0.25), arrow bends right (cxTop ~0.58) -> shift > 0
        if (shift < -0.09) return "Turn Left"
        if (shift > 0.09) return "Turn Right"

        // Fallback for flat horizontal arrows (⬅ / ➡)
        val totalSide = (leftMass + rightMass).coerceAtLeast(1)
        val sideBias = (rightMass - leftMass).toDouble() / totalSide
        if (sideBias < -0.22) return "Turn Left"
        if (sideBias > 0.22) return "Turn Right"

        return "Straight"
    }
}
