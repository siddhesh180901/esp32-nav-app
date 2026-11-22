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

    override fun onListenerConnected() {
        super.onListenerConnected()
        HomeSyncManager.start(applicationContext)
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

        if (!sbn.isOngoing && turnBitmap == null) return
        if (rawTitle.isEmpty() && rawText.isEmpty() && turnBitmap == null) return

        val combined = "$rawTitle $rawText $rawBigText".lowercase()

        // 1. Check Maps text first (Roundabout, Destination, Keep L/R, U-Turn, Turn L/R)
        val textDirection = when {
            combined.contains("roundabout") || combined.contains("exit") -> when {
                combined.contains("1st") || combined.contains("first") || combined.contains("1 ला") -> "Roundabout 1"
                combined.contains("3rd") || combined.contains("third") || combined.contains("3 रा") -> "Roundabout 3"
                else -> "Roundabout 2"
            }
            combined.contains("destination") || combined.contains("arrive") ||
                combined.contains("reached") || combined.contains("पोहोचला") -> "Destination"
            combined.contains("keep left") || combined.contains("slight left") ||
                combined.contains("stay left") || combined.contains("bear left") ||
                combined.contains("fork left") || combined.contains("डावीकडे राहा") -> "Keep Left"
            combined.contains("keep right") || combined.contains("slight right") ||
                combined.contains("stay right") || combined.contains("bear right") ||
                combined.contains("fork right") || combined.contains("उजवीकडे राहा") -> "Keep Right"
            combined.contains("u-turn") || combined.contains("uturn") ||
                combined.contains("u turn") || combined.contains("यू-टर्न") -> "U-Turn"
            combined.contains("left") || combined.contains("डावीकडे") ||
                combined.contains("डाव्या") || combined.contains("बाएं") -> "Turn Left"
            combined.contains("right") || combined.contains("उजवीकडे") ||
                combined.contains("उजव्या") || combined.contains("दाएं") -> "Turn Right"
            else -> null
        }

        // 2. If text has no direction, analyze the side arrow icon shape
        val directionLabel = textDirection
            ?: turnBitmap?.let { detectDirectionFromBitmap(it) }
            ?: "Straight"

        val distanceRegex = Regex("\\b\\d+(\\.\\d+)?\\s?(m|km|ft|mi|मी|किमी)\\b", RegexOption.IGNORE_CASE)
        val distanceMatch = distanceRegex.find(rawTitle) ?: distanceRegex.find(rawText)
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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PACKAGE) return
        if (!sbn.isOngoing) return
        BleNavManager.sendNavigation(NavData(active = false), force = true)
        HomeSyncManager.setNavigationActive(false)
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

    /**
     * Distinguishes:
     * - U-Turn (two bottom legs + top arch)
     * - Keep Left / Keep Right (45° diagonal pointing into top corner, no full horizontal bar)
     * - Turn Left / Turn Right (90° bend with horizontal arm + empty top corner)
     * - Straight
     */
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
            val filterDimmed = (maxLum > 200.0 && (maxLum - minLum) > 55.0)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val p = pixels[y * size + x]
                    if (Color.alpha(p) >= 165) {
                        if (filterDimmed) {
                            val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                            fg[y][x] = lum >= maxLum * 0.74
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
            val thresh = maxDist * 0.62
            for (y in 0 until size) {
                for (x in 0 until size) {
                    fg[y][x] = dists[y * size + x] >= thresh
                }
            }
        }

        // Tight bounding box
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

        // 1. Check U-Turn
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
                        inRun = true
                        if (runs == 0 || gapLen >= 2) runLen = 1
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

        // 2. Horizontal shift between top half and bottom stem
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

        // 3. Features to separate 90° Turn vs 45° Keep/Slight arrow:
        //    - Turn L/R has a horizontal bar spanning ~90-100% width and an empty top corner
        //    - Keep L/R points diagonally into the top corner (filled top corner, maxRun < 80%)
        var maxRun = 0
        for (y in minY..maxY) {
            var cur = 0
            for (x in minX..maxX) {
                if (fg[y][x]) {
                    cur++
                    if (cur > maxRun) maxRun = cur
                } else {
                    cur = 0
                }
            }
        }
        val maxRunRatio = maxRun.toDouble() / w.coerceAtLeast(1)

        val cw = (w * 0.22).toInt().coerceAtLeast(2).coerceAtMost(w)
        val ch = (h * 0.22).toInt().coerceAtLeast(2).coerceAtMost(h)
        var tlCount = 0
        var trCount = 0
        for (y in minY until (minY + ch)) {
            for (x in minX until (minX + cw)) {
                if (fg[y][x]) tlCount++
            }
            for (x in (maxX - cw + 1)..maxX) {
                if (fg[y][x]) trCount++
            }
        }
        val cornerArea = (cw * ch).toDouble().coerceAtLeast(1.0)
        val tlFill = tlCount / cornerArea
        val trFill = trCount / cornerArea

        var sumYAtLeft = 0.0
        var cntAtLeft = 0
        var sumYAtRight = 0.0
        var cntAtRight = 0
        val leftEdgeMax = minOf(maxX, minX + 1)
        val rightEdgeMin = maxOf(minX, maxX - 1)
        for (y in minY..maxY) {
            val ny = (y - minY).toDouble() / (h - 1).coerceAtLeast(1)
            for (x in minX..leftEdgeMax) {
                if (fg[y][x]) {
                    sumYAtLeft += ny
                    cntAtLeft++
                }
            }
            for (x in rightEdgeMin..maxX) {
                if (fg[y][x]) {
                    sumYAtRight += ny
                    cntAtRight++
                }
            }
        }
        val nyAtLeft = if (cntAtLeft > 0) sumYAtLeft / cntAtLeft else 0.5
        val nyAtRight = if (cntAtRight > 0) sumYAtRight / cntAtRight else 0.5

        if (shift < -0.09) {
            val isKeepLeft = (tlFill >= 0.25 && maxRunRatio < 0.82) ||
                (nyAtLeft < 0.16 && maxRunRatio < 0.85)
            return if (isKeepLeft) "Keep Left" else "Turn Left"
        }
        if (shift > 0.09) {
            val isKeepRight = (trFill >= 0.25 && maxRunRatio < 0.82) ||
                (nyAtRight < 0.16 && maxRunRatio < 0.85)
            return if (isKeepRight) "Keep Right" else "Turn Right"
        }

        val totalSide = (leftMass + rightMass).coerceAtLeast(1)
        val sideBias = (rightMass - leftMass).toDouble() / totalSide
        if (sideBias < -0.22) return "Turn Left"
        if (sideBias > 0.22) return "Turn Right"

        return "Straight"
    }
}
