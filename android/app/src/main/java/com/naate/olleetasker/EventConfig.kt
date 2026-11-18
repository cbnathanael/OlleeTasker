package com.naate.olleetasker

import android.os.Bundle

enum class EventType(val label: String, val variableValue: String) {
    MESSAGE("Message received from watch", "message"),
    CONNECTED("Watch connected", "connected"),
    DISCONNECTED("Watch disconnected", "disconnected"),
}

enum class MatchMode(val label: String) {
    ANY("Any message"),
    EQUALS("Is exactly"),
    STARTS_WITH("Starts with"),
    CONTAINS("Contains"),
    REGEX("Matches regex"),
}

/** Settings for one event-plugin instance (Tasker profile). */
data class EventConfig(
    val type: EventType,
    val match: MatchMode,
    val pattern: String,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putInt(KEY_VERSION, 1)
        putString(KEY_TYPE, type.name)
        putString(KEY_MATCH, match.name)
        putString(KEY_PATTERN, pattern)
    }

    fun blurb(): String = when {
        type != EventType.MESSAGE -> type.label
        match == MatchMode.ANY -> "Any message from watch"
        else -> "Message ${match.label.lowercase()} \"$pattern\""
    }

    fun matches(event: WatchEvent): Boolean {
        if (event.type != type) return false
        if (type != EventType.MESSAGE) return true
        val text = event.text
        return when (match) {
            MatchMode.ANY -> true
            MatchMode.EQUALS -> text == pattern
            MatchMode.STARTS_WITH -> text.startsWith(pattern)
            MatchMode.CONTAINS -> text.contains(pattern)
            MatchMode.REGEX -> runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }
    }

    companion object {
        private const val PREFIX = "com.naate.olleetasker.event."
        private const val KEY_VERSION = PREFIX + "VERSION"
        private const val KEY_TYPE = PREFIX + "TYPE"
        private const val KEY_MATCH = PREFIX + "MATCH"
        private const val KEY_PATTERN = PREFIX + "PATTERN"

        fun fromBundle(bundle: Bundle?): EventConfig? {
            if (bundle == null || !bundle.containsKey(KEY_TYPE)) return null
            return EventConfig(
                type = EventType.entries.firstOrNull { it.name == bundle.getString(KEY_TYPE) } ?: return null,
                match = MatchMode.entries.firstOrNull { it.name == bundle.getString(KEY_MATCH) } ?: MatchMode.ANY,
                pattern = bundle.getString(KEY_PATTERN).orEmpty(),
            )
        }
    }
}
