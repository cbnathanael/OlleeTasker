package com.naate.olleetasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the link after a reboot or app update if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = LinkPrefs(context)
        if (!prefs.enabled || prefs.address == null || !Perms.canConnect(context)) return
        try {
            context.startForegroundService(Intent(context, LinkService::class.java))
        } catch (e: Exception) {
            Log.w("BootReceiver", "Couldn't restart the link", e)
        }
    }
}
