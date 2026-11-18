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

    /** True once the first-time setup wizard has been completed (or skipped). Existing users count as set up. */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, address != null)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    private companion object {
        const val KEY_SETUP_DONE = "setup_done"
        const val KEY_ADDRESS = "address"
        const val KEY_ENABLED = "enabled"
        const val KEY_PAUSED = "paused"
    }
}
