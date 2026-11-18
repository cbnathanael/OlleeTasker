package com.naate.olleetasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/** Receives Tasker's "run this action" broadcast and performs it. */
class FireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Tasker.ACTION_FIRE_SETTING) return
        val config = PluginConfig.fromBundle(intent.getBundleExtra(Tasker.EXTRA_BUNDLE))
        if (config == null) {
            Log.w(TAG, "Fired with missing/invalid configuration")
            if (isOrderedBroadcast) {
                resultCode = Tasker.RESULT_CODE_FAILED
                getResultExtras(true).putBundle(
                    Tasker.EXTRA_VARIABLES,
                    Bundle().apply { putString(Tasker.VAR_ERROR_MESSAGE, "This action is no longer supported. Remove it from the task.") },
                )
            }
            return
        }

        // Tell Tasker to wait for our completion broadcast (which carries the output variables).
        if (isOrderedBroadcast) resultCode = Tasker.RESULT_CODE_PENDING

        val appContext = context.applicationContext
        OlleeLink.init(appContext)

        // Start the foreground service now, while we're still handling Tasker's broadcast. It keeps the
        // process alive for the work below, so the receiver itself can return right away (no ANR risk).
        val needsService = config.operation.usesWatch ||
            (config.operation == Operation.RESUME && LinkPrefs(appContext).enabled)
        if (needsService) {
            try {
                OlleeLink.ensureService()
            } catch (e: GattException) {
                Tasker.signalFinish(appContext, intent, Tasker.RESULT_CODE_FAILED, errorVars(e))
                return
            }
        }

        thread(name = "ollee-action") {
            var resultCode = Tasker.RESULT_CODE_OK
            val vars = try {
                ActionRunner.run(appContext, config)
            } catch (e: Exception) {
                Log.w(TAG, "Action ${config.operation} failed", e)
                resultCode = Tasker.RESULT_CODE_FAILED
                errorVars(e)
            }
            Tasker.signalFinish(appContext, intent, resultCode, vars)
        }
    }

    private fun errorVars(e: Exception) = Bundle().apply {
        putString(Tasker.VAR_ERROR_MESSAGE, e.message ?: e.toString())
    }

    private companion object {
        const val TAG = "OlleeFire"
    }
}
