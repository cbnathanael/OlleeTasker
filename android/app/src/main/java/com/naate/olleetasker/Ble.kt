package com.naate.olleetasker

import java.util.UUID

object Ble {
    /** Ollee "Forge" BLE service (docs: forge_emulator/docs/concepts/input.md). */
    val FORGE_SERVICE: UUID = UUID.fromString("c6fb0001-a37d-40ac-89be-887d25e1f5b3")
    /** Phone writes here; each write arrives as one ble_rx(data) call on the watch. */
    val FORGE_RX: UUID = UUID.fromString("c6fb0002-a37d-40ac-89be-887d25e1f5b3")
    /** Watch notifies here (Ollee.send). */
    val FORGE_TX: UUID = UUID.fromString("c6fb0003-a37d-40ac-89be-887d25e1f5b3")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The watch accepts at most 20 bytes per write, and notifies in 20-byte chunks. */
    const val MAX_WRITE_BYTES = 20

    private val HEX = Regex("(?i)[0-9a-f]*")

    /** Accepts "01ff", "01 ff", "01:FF", "0x01 0xff", etc. */
    fun hexToBytes(input: String): ByteArray {
        val clean = input.replace(Regex("(?i)0x"), "").replace(Regex("[\\s:,-]"), "")
        require(clean.length % 2 == 0 && HEX.matches(clean)) { "Invalid hex value: $input" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Encodes text the way the watch reads it: one byte per character (codes 0–255). */
    fun encodeWatchText(text: String): ByteArray {
        text.forEachIndexed { i, c ->
            require(c.code <= 0xFF) {
                "Character '$c' (position ${i + 1}) can't be sent: the watch uses one byte per character (codes 0–255)"
            }
        }
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    fun decodeWatchText(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    /** Matches a characteristic by its first 32 bits, in case a device varies the rest of the UUID. */
    fun samePrefix(a: UUID, b: UUID): Boolean = a.mostSignificantBits ushr 32 == b.mostSignificantBits ushr 32
}
