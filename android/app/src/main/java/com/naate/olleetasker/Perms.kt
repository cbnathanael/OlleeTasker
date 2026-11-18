package com.naate.olleetasker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Perms {
    /** Needed to connect to the watch. */
    val CONNECT: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    /** Needed to scan for the watch (and read device names). */
    val SCAN: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Everything the setup screen asks for. */
    val ALL: Array<String> = (SCAN + CONNECT).distinct().toTypedArray() +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun missing(context: Context, perms: Array<String>): Array<String> =
        perms.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun canConnect(context: Context): Boolean = missing(context, CONNECT).isEmpty()
}
