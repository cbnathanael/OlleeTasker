package com.naate.olleetasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tasker asks this, once per matching profile, whether an event we reported matches its settings. */
class QueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Tasker.ACTION_QUERY_CONDITION || !isOrderedBroadcast) return
        resultCode = Tasker.RESULT_CONDITION_UNSATISFIED

        val config = EventConfig.fromBundle(intent.getBundleExtra(Tasker.EXTRA_BUNDLE)) ?: return
        val data = intent.getBundleExtra(Tasker.EXTRA_PASS_THROUGH_DATA) ?: return
        // Only answer queries for events this process actually sent (blocks spoofed/replayed REQUEST_QUERYs).
        if (!TaskerEvents.isIssued(data.getInt(Tasker.PASS_THROUGH_MESSAGE_ID, -1))) return
        val event = WatchEvent.fromBundle(data) ?: return
        if (!config.matches(event)) return

        resultCode = Tasker.RESULT_CONDITION_SATISFIED
        getResultExtras(true).putBundle(Tasker.EXTRA_VARIABLES, event.toVariables())
    }
}
