package com.naate.olleetasker

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.security.SecureRandom

/** Something that happened on the link, delivered to Tasker as a plugin event. */
data class WatchEvent(
    val type: EventType,
    val address: String,
    val bytes: ByteArray = ByteArray(0),
    val reason: String = "",
) {
    val text: String get() = Ble.decodeWatchText(bytes)

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_TYPE, type.name)
        putString(KEY_ADDRESS, address)
        putByteArray(KEY_BYTES, bytes)
        putString(KEY_REASON, reason)
    }

    fun toVariables(): Bundle = Bundle().apply {
        putString(TaskerEvents.VAR_EVENT, type.variableValue)
        putString(TaskerEvents.VAR_ADDRESS, address)
        if (type == EventType.MESSAGE) {
            putString(TaskerEvents.VAR_MESSAGE, text)
            putString(TaskerEvents.VAR_HEX, Ble.bytesToHex(bytes))
        }
        if (type == EventType.DISCONNECTED) putString(TaskerEvents.VAR_REASON, reason)
    }

    companion object {
        private const val KEY_TYPE = "type"
        private const val KEY_ADDRESS = "address"
        private const val KEY_BYTES = "bytes"
        private const val KEY_REASON = "reason"

        fun fromBundle(b: Bundle): WatchEvent? {
            val type = EventType.entries.firstOrNull { it.name == b.getString(KEY_TYPE) } ?: return null
            return WatchEvent(
                type,
                b.getString(KEY_ADDRESS).orEmpty(),
                b.getByteArray(KEY_BYTES) ?: ByteArray(0),
                b.getString(KEY_REASON).orEmpty(),
            )
        }
    }
}

/** Sends events to Tasker and checks that queries coming back really started here. */
object TaskerEvents {
    const val VAR_EVENT = "%ollee_event"
    const val VAR_ADDRESS = "%ollee_address"
    const val VAR_MESSAGE = "%ollee_message"
    const val VAR_HEX = "%ollee_hex"
    const val VAR_REASON = "%ollee_reason"

    private const val MAX_AGE_MS = 120_000L
    private val random = SecureRandom()
    private val issued = LinkedHashMap<Int, Long>()

    fun fire(context: Context, event: WatchEvent) {
        val id = synchronized(issued) {
            prune()
            var id: Int
            do id = random.nextInt(Int.MAX_VALUE) while (id == 0 || id in issued)
            issued[id] = SystemClock.elapsedRealtime()
            id
        }
        val data = event.toBundle().apply { putInt(Tasker.PASS_THROUGH_MESSAGE_ID, id) }
        Tasker.requestQuery(context, EventEditActivity::class.java, data)
    }

    /** IDs stay valid for a while, as Tasker sends one query per matching profile. */
    fun isIssued(id: Int): Boolean = synchronized(issued) {
        prune()
        id in issued
    }

    private fun prune() {
        val cutoff = SystemClock.elapsedRealtime() - MAX_AGE_MS
        issued.entries.removeAll { it.value < cutoff }
    }
}
