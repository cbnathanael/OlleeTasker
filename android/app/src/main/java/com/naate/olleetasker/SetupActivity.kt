package com.naate.olleetasker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator

/** First-time setup wizard. Also reachable from Settings → Run setup again. */
class SetupActivity : AppCompatActivity() {

    private enum class Step(
        @param:StringRes val title: Int,
        @param:StringRes val body: Int,
        @param:StringRes val action: Int? = null,
    ) {
        WELCOME(R.string.setup_welcome_title, R.string.setup_welcome_body),
        PERMISSIONS(R.string.setup_permissions_title, R.string.setup_permissions_body, R.string.grant_permissions),
        BATTERY(R.string.setup_battery_title, R.string.setup_battery_body, R.string.battery_optimization),
        WATCH(R.string.setup_watch_title, R.string.setup_watch_body, R.string.pick_watch),
        TASKER(R.string.setup_tasker_title, R.string.setup_tasker_body),
    }

    private lateinit var prefs: LinkPrefs
    private lateinit var progress: LinearProgressIndicator
    private lateinit var count: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var action: Button
    private lateinit var stepStatus: TextView
    private lateinit var back: Button
    private lateinit var next: Button
    private var step = Step.WELCOME
    private val listener = OlleeLink.Listener { runOnUiThread(::render) }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = go(-1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        OlleeLink.init(this)
        prefs = LinkPrefs(this)
        progress = findViewById(R.id.progress)
        count = findViewById(R.id.step_count)
        title = findViewById(R.id.step_title)
        body = findViewById(R.id.step_body)
        action = findViewById(R.id.step_action)
        stepStatus = findViewById(R.id.step_status)
        back = findViewById(R.id.back)
        next = findViewById(R.id.next)

        step = Step.entries[savedInstanceState?.getInt(STATE_STEP) ?: 0]
        progress.max = Step.entries.size
        onBackPressedDispatcher.addCallback(this, backCallback)

        back.setOnClickListener { go(-1) }
        next.setOnClickListener { if (step == Step.entries.last()) finishSetup() else go(+1) }
        action.setOnClickListener {
            when (step) {
                Step.PERMISSIONS -> SetupHelpers.requestPermissions(this)
                Step.BATTERY -> SetupHelpers.requestBatteryExemption(this)
                Step.WATCH -> SetupHelpers.chooseWatch(this, ::render)
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OlleeLink.addListener(listener)
        render()
    }

    override fun onPause() {
        OlleeLink.removeListener(listener)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step.ordinal)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun go(delta: Int) {
        step = Step.entries[(step.ordinal + delta).coerceIn(0, Step.entries.lastIndex)]
        render()
    }

    /** Null for steps with nothing to do; otherwise whether the step is complete. */
    private fun isDone(s: Step): Boolean? = when (s) {
        Step.PERMISSIONS -> SetupHelpers.permissionsGranted(this)
        Step.BATTERY -> SetupHelpers.batteryExempt(this)
        Step.WATCH -> prefs.address != null
        else -> null
    }

    private fun render() {
        val done = isDone(step)
        progress.setProgressCompat(step.ordinal + 1, true)
        count.text = getString(R.string.setup_step_count, step.ordinal + 1, Step.entries.size)
        title.setText(step.title)
        body.setText(step.body)

        action.visibility = if (step.action != null && done != true) View.VISIBLE else View.GONE
        step.action?.let(action::setText)
        stepStatus.visibility = if (done == null) View.GONE else View.VISIBLE
        stepStatus.text = when {
            done == null -> ""
            step == Step.WATCH && done ->
                getString(R.string.setup_watch_status, prefs.address, OlleeLink.state.description)
            done -> getString(R.string.setup_step_done)
            else -> getString(R.string.setup_step_pending)
        }

        back.visibility = if (step.ordinal == 0) View.INVISIBLE else View.VISIBLE
        backCallback.isEnabled = step.ordinal > 0
        next.setText(
            when {
                step == Step.entries.last() -> R.string.finish
                done == false -> R.string.skip
                else -> R.string.next
            },
        )
    }

    private fun finishSetup() {
        prefs.setupDone = true
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private companion object {
        const val STATE_STEP = "step"
    }
}
