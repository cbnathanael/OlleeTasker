package com.naate.olleetasker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/** Foreground service that keeps the process (and so [OlleeLink]) alive while the link is wanted. */
class LinkService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val listener = OlleeLink.Listener { handler.post(::updateNotification) }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                OlleeLink.onBluetoothOn()
            }
        }
    }
    private var started = false

    override fun onCreate() {
        super.onCreate()
        OlleeLink.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start foreground service", e)
            stopSelf()
            return
        }
        started = true
        running = true
        OlleeLink.addListener(listener)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_PAUSE -> thread { runCatching { OlleeLink.pause(this) } }
            ACTION_RESUME -> thread { runCatching { OlleeLink.resume(this) } }
            ACTION_STOP -> thread { runCatching { OlleeLink.stop(this) } }
            else -> if (LinkPrefs(this).enabled) OlleeLink.restore(this) else stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) {
            running = false
            OlleeLink.removeListener(listener)
            unregisterReceiver(bluetoothReceiver)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = OlleeLink.state
        val text = state.description + (OlleeLink.address?.let { " · $it" } ?: "")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (state == OlleeLink.State.PAUSED) {
            builder.addAction(action(ACTION_RESUME, getString(R.string.resume)))
        } else {
            builder.addAction(action(ACTION_PAUSE, getString(R.string.pause)))
        }
        builder.addAction(action(ACTION_STOP, getString(R.string.disconnect)))
        return builder.build()
    }

    private fun action(action: String, title: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(), Intent(this, LinkService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null as Icon?, title, pi).build()
    }

    companion object {
        @Volatile var running = false
            private set

        private const val TAG = "LinkService"
        private const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.naate.olleetasker.PAUSE"
        private const val ACTION_RESUME = "com.naate.olleetasker.RESUME"
        private const val ACTION_STOP = "com.naate.olleetasker.STOP"
    }
}
