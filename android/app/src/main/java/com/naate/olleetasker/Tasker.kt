package com.naate.olleetasker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.net.URISyntaxException

/**
 * Minimal implementation of the Locale / Tasker plugin protocol
 * (equivalent to the relevant parts of the official TaskerPlugin.java helper).
 */
object Tasker {
    val PACKAGES = listOf("net.dinglisch.android.taskerm", "net.dinglisch.android.tasker")

    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY"

    private const val BASE_KEY = "net.dinglisch.android.tasker"
    private const val EXTRAS_PREFIX = "$BASE_KEY.extras."

    /** Bundle key: space-separated list of bundle keys Tasker should do %variable replacement in. */
    const val BUNDLE_KEY_VARIABLE_REPLACE_KEYS = EXTRAS_PREFIX + "VARIABLE_REPLACE_KEYS"

    /** Result-intent extra: how long (ms) Tasker should wait for the completion broadcast. */
    const val EXTRA_REQUESTED_TIMEOUT = EXTRAS_PREFIX + "REQUESTED_TIMEOUT"

    /** Result-intent extra: output variables shown in Tasker's variable picker. */
    const val EXTRA_RELEVANT_VARIABLES = "$BASE_KEY.RELEVANT_VARIABLES"

    /** Event plugins: data passed from REQUEST_QUERY through to the matching QUERY_CONDITION. */
    const val EXTRA_PASS_THROUGH_DATA = EXTRAS_PREFIX + "PASS_THROUGH_DATA"
    const val PASS_THROUGH_MESSAGE_ID = "$BASE_KEY.MESSAGE_ID"

    /** Output variables, in a fire completion intent or a query result's extras. */
    const val EXTRA_VARIABLES = EXTRAS_PREFIX + "VARIABLES"

    private const val EXTRA_COMPLETION_INTENT = EXTRAS_PREFIX + "COMPLETION_INTENT"
    private const val EXTRA_RESULT_CODE = EXTRAS_PREFIX + "RESULT_CODE"

    const val RESULT_CODE_OK = Activity.RESULT_OK
    const val RESULT_CODE_FAILED = Activity.RESULT_FIRST_USER + 1
    const val RESULT_CODE_PENDING = Activity.RESULT_FIRST_USER + 2

    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17

    const val VAR_ERROR_MESSAGE = "%errmsg"

    /** Tells Tasker an asynchronous (pending) action finished, optionally returning variables. */
    fun signalFinish(context: Context, fireIntent: Intent, resultCode: Int, vars: Bundle?) {
        val uri = fireIntent.getStringExtra(EXTRA_COMPLETION_INTENT) ?: return
        val completion = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Bad completion intent from Tasker", e)
            return
        }
        completion.putExtra(EXTRA_RESULT_CODE, resultCode)
        if (vars != null) completion.putExtra(EXTRA_VARIABLES, vars)
        context.sendBroadcast(completion)
    }

    /** Asks Tasker to query the event plugin whose edit activity is [activityClass], passing [data] through. */
    fun requestQuery(context: Context, activityClass: Class<*>, data: Bundle) {
        for (pkg in PACKAGES) {
            context.sendBroadcast(
                Intent(ACTION_REQUEST_QUERY)
                    .setPackage(pkg)
                    .putExtra(EXTRA_ACTIVITY, activityClass.name)
                    .putExtra(EXTRA_PASS_THROUGH_DATA, data),
            )
        }
    }

    private const val TAG = "OlleeTasker"
}
