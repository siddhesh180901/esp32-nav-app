package com.esp32.navlite

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NavNotificationService : NotificationListenerService() {

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PACKAGE) return
        val extras = sbn.notification?.extras ?: return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        // Ignore empty or non-navigation Maps notifications
        if (rawTitle.isEmpty() && rawText.isEmpty()) return

        val combined = "$rawTitle $rawText".lowercase()

        // Detect turn keyword so ESP32 arrow logic (left / right / u-turn / straight) matches cleanly
        val directionLabel = when {
            combined.contains("u-turn") || combined.contains("uturn") || combined.contains("u turn") -> "U-Turn"
            combined.contains("left") -> "Turn Left"
            combined.contains("right") -> "Turn Right"
            combined.contains("roundabout") -> "Roundabout"
            else -> "Straight"
        }

        // Google Maps usually puts distance (e.g. "250 m" or "1.2 km") in title or start of text
        val distanceRegex = Regex("\\b\\d+(\\.\\d+)?\\s?(m|km|ft|mi)\\b", RegexOption.IGNORE_CASE)
        val distanceMatch = distanceRegex.find(rawTitle) ?: distanceRegex.find(rawText)
        val distance = distanceMatch?.value ?: rawTitle.take(12)

        // Street / instruction title
        val streetTitle = if (rawText.isNotEmpty()) rawText else rawTitle

        // Parse ETA / duration from subText (e.g., "12 min · 4.5 km · 11:20 AM ETA")
        val parts = rawSubText.split("·", "•", "-")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val duration = parts.getOrNull(0) ?: ""
        val eta = parts.lastOrNull { it.contains(":") || it.contains("AM", true) || it.contains("PM", true) }
            ?: parts.getOrNull(1)
            ?: rawSubText.take(16)

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
        BleNavManager.sendNavigation(NavData(active = false), force = true)
    }
}
