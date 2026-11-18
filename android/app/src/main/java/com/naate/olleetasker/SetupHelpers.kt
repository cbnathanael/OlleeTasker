package com.naate.olleetasker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import kotlin.concurrent.thread

/** Setup steps shared by the wizard, the main screen and settings. */
object SetupHelpers {
    const val REQUEST_PERMISSIONS = 1

    fun permissionsGranted(context: Context) = Perms.missing(context, Perms.ALL).isEmpty()

    fun requestPermissions(activity: Activity) {
        val missing = Perms.missing(activity, Perms.ALL)
        if (missing.isNotEmpty()) activity.requestPermissions(missing, REQUEST_PERMISSIONS)
    }

    /** Opens this app's system settings page, for permissions the user previously denied permanently. */
    fun openAppSettings(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
        )
    }

    fun batteryExempt(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(activity: Activity) {
        if (batteryExempt(activity)) return
        activity.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
        )
    }

    /** Shows the device picker, saves the chosen watch and (unless paused) starts the link to it. */
    fun chooseWatch(activity: Activity, onChanged: () -> Unit) {
        DevicePicker(activity) { address ->
            val prefs = LinkPrefs(activity)
            prefs.address = address
            onChanged()
            if (!prefs.paused) background(activity, onChanged) { OlleeLink.start(activity.applicationContext, address) }
        }.show()
    }

    /** Runs [block] off the main thread, toasting any error, then calls [after] on the main thread. */
    fun background(activity: Activity, after: () -> Unit = {}, block: () -> Unit) {
        thread {
            try {
                block()
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_LONG).show() }
            }
            activity.runOnUiThread(after)
        }
    }
}
