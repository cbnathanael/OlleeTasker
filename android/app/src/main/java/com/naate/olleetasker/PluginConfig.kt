package com.naate.olleetasker

import android.os.Bundle

enum class Operation(val label: String, val usesWatch: Boolean) {
    SEND("Send message", true),
    SEND_TONE("Send notification tone", true),
    CONNECT("Connect and stay connected", true),
    DISCONNECT("Disconnect and stop", false),
    PAUSE("Pause link (Ollee app in use)", false),
    RESUME("Resume link", false),
    STATUS("Get link status", false),
}

/**
 * The separate actions Tasker lists for this plugin. Each one is an activity-alias of [EditActivity]
 * (see the manifest); all of them share one bundle format and [FireReceiver].
 */
enum class ActionKind(val component: String, val operations: List<Operation>) {
    MESSAGE("EditActivity", listOf(Operation.SEND)),
    TONE("EditToneAction", listOf(Operation.SEND_TONE)),
    CONNECTION(
        "EditConnectionAction",
        listOf(Operation.CONNECT, Operation.DISCONNECT, Operation.PAUSE, Operation.RESUME, Operation.STATUS),
    );

    companion object {
        fun fromComponent(className: String?): ActionKind =
            entries.firstOrNull { className?.substringAfterLast('.') == it.component } ?: MESSAGE

        fun of(op: Operation): ActionKind = entries.first { op in it.operations }
    }
}

enum class ValueFormat(val label: String) {
    TEXT("Text (one byte per character, as the watch reads it)"),
    UTF8("Text (UTF-8)"),
    HEX("Hex bytes"),
}

/** The settings for one plugin action instance, stored by Tasker as a Bundle. */
data class PluginConfig(
    /** Watch MAC address; empty means the watch saved in the app. */
    val address: String = "",
    val operation: Operation = Operation.SEND,
    /** Message, value to write, or (for SEND_TONE) the tone name. */
    val value: String = "",
    val format: ValueFormat = ValueFormat.TEXT,
    /** SEND: write the message as-is, without the prefix and notification number. */
    val raw: Boolean = false,
    /** SEND: text put before the tone number and message; blank means [DEFAULT_PREFIX]. */
    val prefix: String = DEFAULT_PREFIX,
    /** SEND: notification number 0–4 (a tone slot), blank for none, or a %variable. Older actions hold a tone name. */
    val tone: String = "",
    /** SEND: times to repeat the notification chime, 0–9, blank for 0, or a %variable. Sent after the notification number. */
    val repeat: String = "",
) {
    fun toBundle(): Bundle = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        putString(KEY_ADDRESS, address)
        putString(KEY_OPERATION, operation.name)
        putString(KEY_VALUE, value)
        putString(KEY_FORMAT, format.name)
        putString(KEY_TONE, tone)
        putString(KEY_REPEAT, repeat)
        putString(KEY_PREFIX, prefix)
        putBoolean(KEY_RAW, raw)
        putString(
            Tasker.BUNDLE_KEY_VARIABLE_REPLACE_KEYS,
            listOf(KEY_ADDRESS, KEY_VALUE, KEY_TONE, KEY_REPEAT, KEY_PREFIX).joinToString(" "),
        )
    }

    /** Short summary shown under the action in Tasker. */
    fun blurb(): String {
        val target = if (address.isBlank()) "" else "\non $address"
        return when (operation) {
            Operation.SEND -> "Send \"$value\"" + (
                if (raw) "" else "\nprefix ${prefix.ifBlank { DEFAULT_PREFIX }}, " + (if (tone.isBlank()) "no tone" else "tone $tone") +
                    ", repeat ${repeat.ifBlank { NO_REPEAT.toString() }}"
                ) + target
            Operation.SEND_TONE -> "Send tone \"$value\"$target"
            else -> operation.label + target
        }
    }

    /** How long Tasker should wait for this action before giving up. */
    fun requestedTimeoutMs(): Int {
        return (OlleeLink.MAX_ACTION_MS + 10_000L).toInt()
    }

    companion object {
        const val DEFAULT_PREFIX = "OW"
        const val NO_TONE = '-'
        const val NO_REPEAT = '0'
        private const val VERSION = 2
        private const val PREFIX = "com.naate.olleetasker.extra."
        private const val KEY_VERSION = PREFIX + "VERSION"
        private const val KEY_ADDRESS = PREFIX + "ADDRESS"
        private const val KEY_OPERATION = PREFIX + "OPERATION"
        private const val KEY_VALUE = PREFIX + "VALUE"
        private const val KEY_FORMAT = PREFIX + "FORMAT"
        private const val KEY_TONE = PREFIX + "TONE"
        private const val KEY_REPEAT = PREFIX + "REPEAT"
        private const val KEY_PREFIX = PREFIX + "MESSAGE_PREFIX"
        private const val KEY_RAW = PREFIX + "RAW_MESSAGE"
        private const val KEY_V1_VALUE_IS_TEXT = PREFIX + "VALUE_IS_TEXT"

        fun fromBundle(bundle: Bundle?): PluginConfig? {
            if (bundle == null || !bundle.containsKey(KEY_OPERATION)) return null
            val version = bundle.getInt(KEY_VERSION, 1)
            val opName = bundle.getString(KEY_OPERATION)
            val operation = if (version < 2 && opName == "CONNECT") {
                return null // v1 "Connect & list services" (removed)
            } else if (opName == "SEND_AND_WAIT") {
                Operation.SEND // "wait for reply" was removed; replies arrive through the event instead
            } else {
                Operation.entries.firstOrNull { it.name == opName } ?: return null
            }
            val format = if (version < 2) {
                if (bundle.getBoolean(KEY_V1_VALUE_IS_TEXT, false)) ValueFormat.UTF8 else ValueFormat.HEX
            } else {
                ValueFormat.entries.firstOrNull { it.name == bundle.getString(KEY_FORMAT) } ?: ValueFormat.TEXT
            }
            return PluginConfig(
                address = bundle.getString(KEY_ADDRESS).orEmpty(),
                operation = operation,
                value = bundle.getString(KEY_VALUE).orEmpty(),
                format = format,
                tone = bundle.getString(KEY_TONE).orEmpty(),
                repeat = bundle.getString(KEY_REPEAT).orEmpty(),
                prefix = bundle.getString(KEY_PREFIX) ?: DEFAULT_PREFIX,
                raw = bundle.getBoolean(KEY_RAW, false),
            )
        }
    }
}
