package com.esp32.navlite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class NavData(
    val active: Boolean,
    val distance: String = "",
    val directions: String = "",
    val title: String = "",
    val duration: String = "",
    val eta: String = ""
)

object ChronosProtocol {
    // Nordic UART Service UUIDs used by ChronosESP32
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val CHAR_WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val CHAR_NOTIFY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /**
     * Builds the exact ChronosESP32 navigation packet (CF_NAV_DATA):
     * Frame: 0xAB, lenHi, lenLo, 0xFE, 0xEF, 0x80,
     *        hasIcon(1B), isNavigation(1B), iconCRC(4B),
     *        title\0, duration\0, distance\0, eta\0, directions\0
     */
    fun buildNavFrame(nav: NavData): ByteArray {
        val payload = ByteArrayOutputStream()

        // Header bytes after length: marker(0xFE), command(0xEF), subtype(0x80)
        payload.write(0xFE)
        payload.write(0xEF)
        payload.write(0x80)

        // hasIcon = 0 (ESP32 draws custom arrows from directions text)
        payload.write(0x00)
        // isNavigation = 1 when active, 0 when stopped
        payload.write(if (nav.active) 0x01 else 0x00)
        // iconCRC = 4 zero bytes
        payload.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        // Null-terminated strings expected by ChronosESP32
        writeCString(payload, nav.title.take(32))
        writeCString(payload, nav.duration.take(20))
        writeCString(payload, nav.distance.take(20))
        writeCString(payload, nav.eta.take(24))
        writeCString(payload, nav.directions.take(40))

        val body = payload.toByteArray()
        val length = body.size

        val frame = ByteArrayOutputStream()
        frame.write(0xAB)
        frame.write((length shr 8) and 0xFF)
        frame.write(length and 0xFF)
        frame.write(body)

        return frame.toByteArray()
    }

    /**
     * Splits a frame for BLE transmission.
     * If MTU allows sending in a single write, returns a single packet.
     * Otherwise uses Chronos/DaFit continuation framing (seq 0x00, 0x01, ... + 19 bytes).
     */
    fun splitIntoBleChunks(frame: ByteArray, maxPayloadPerWrite: Int): List<ByteArray> {
        if (frame.size <= maxPayloadPerWrite) {
            return listOf(frame)
        }
        val chunks = mutableListOf<ByteArray>()
        val firstSize = minOf(20, frame.size)
        chunks.add(frame.copyOfRange(0, firstSize))

        var offset = firstSize
        var seq = 0
        while (offset < frame.size) {
            val take = minOf(19, frame.size - offset)
            val chunk = ByteArray(1 + take)
            chunk[0] = (seq and 0xFF).toByte()
            System.arraycopy(frame, offset, chunk, 1, take)
            chunks.add(chunk)
            offset += take
            seq++
        }
        return chunks
    }

    private fun writeCString(out: ByteArrayOutputStream, text: String) {
        out.write(text.toByteArray(StandardCharsets.UTF_8))
        out.write(0x00)
    }
}
