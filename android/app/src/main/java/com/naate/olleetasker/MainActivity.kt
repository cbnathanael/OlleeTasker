package com.naate.olleetasker

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** Launcher screen: link status and controls. Sends first-time users to the setup wizard. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var prefs: LinkPrefs
    private val listener = OlleeLink.Listener { runOnUiThread(::refresh) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OlleeLink.init(this)
        prefs = LinkPrefs(this)
        if (!prefs.setupDone) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        status = findViewById(R.id.status)
        warning = findViewById(R.id.warning)
        warning.setOnClickListener { openSettings() }
        findViewById<Button>(R.id.tones).setOnClickListener { startActivity(Intent(this, TonesActivity::class.java)) }

        findViewById<Button>(R.id.connect).setOnClickListener {
            if (OlleeLink.state != OlleeLink.State.STOPPED) {
                SetupHelpers.background(this, ::refresh) { OlleeLink.stop(this) }
                return@setOnClickListener
            }
            val address = prefs.address ?: return@setOnClickListener toast(getString(R.string.choose_watch_first))
            SetupHelpers.background(this, ::refresh) {
                if (prefs.paused) OlleeLink.resume(this)
                OlleeLink.start(this, address)
            }
        }
        findViewById<CheckBox>(R.id.auto_reconnect).setOnClickListener { view ->
            val on = (view as CheckBox).isChecked
            SetupHelpers.background(this, ::refresh) { OlleeLink.setAutoReconnect(this, on) }
        }
        findViewById<Button>(R.id.pause).setOnClickListener {
            SetupHelpers.background(this, ::refresh) {
                if (prefs.paused) OlleeLink.resume(this) else OlleeLink.pause(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OlleeLink.addListener(listener)
        refresh()
    }

    override fun onPause() {
        OlleeLink.removeListener(listener)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            openSettings()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun refresh() {
        status.text = getString(
            R.string.main_status,
            prefs.address ?: getString(R.string.watch_not_chosen),
            OlleeLink.state.description,
        )
        val problems = buildList {
            if (prefs.address == null) add(getString(R.string.warning_no_watch))
            if (!SetupHelpers.permissionsGranted(this@MainActivity)) add(getString(R.string.warning_permissions))
            if (!SetupHelpers.batteryExempt(this@MainActivity)) add(getString(R.string.warning_battery))
        }
        warning.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        warning.text = (problems + getString(R.string.warning_fix)).joinToString("\n")
        val running = OlleeLink.state != OlleeLink.State.STOPPED
        findViewById<Button>(R.id.connect).setText(if (running) R.string.disconnect else R.string.connect)
        findViewById<Button>(R.id.pause).apply {
            setText(if (prefs.paused) R.string.resume else R.string.pause)
            isEnabled = running
        }
        val auto = prefs.autoReconnect
        findViewById<CheckBox>(R.id.auto_reconnect).isChecked = auto
        findViewById<TextView>(R.id.auto_reconnect_summary).setText(
            if (auto) R.string.auto_reconnect_on else R.string.auto_reconnect_off,
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
