package com.naate.olleetasker

import android.content.Context

/** Persistent link settings, so the connection can be restored after a reboot or process restart. */
class LinkPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ollee_link", Context.MODE_PRIVATE)

    /** MAC address of the watch the link connects to. */
    var address: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    /** True while the user wants the link running (between Connect and Disconnect). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** True while the link is paused so the Ollee app can use the watch. */
    var paused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).apply()

    /** When false, a lost or failed connection stops the link instead of retrying; only Connect starts it again. */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    /** When true, asks Android for a low-power connection (longer interval, more latency); otherwise balanced. */
    var lowPower: Boolean
        get() = prefs.getBoolean(KEY_LOW_POWER, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_POWER, value).apply()

    /** True once the first-time setup wizard has been completed (or skipped). Existing users count as set up. */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, address != null)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    private companion object {
        const val KEY_SETUP_DONE = "setup_done"
        const val KEY_ADDRESS = "address"
        const val KEY_ENABLED = "enabled"
        const val KEY_PAUSED = "paused"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_LOW_POWER = "low_power"
    }
}
