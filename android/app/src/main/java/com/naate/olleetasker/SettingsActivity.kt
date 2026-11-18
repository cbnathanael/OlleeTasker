package com.naate.olleetasker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: LinkPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        OlleeLink.init(this)
        prefs = LinkPrefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<View>(R.id.row_watch).setOnClickListener { SetupHelpers.chooseWatch(this, ::refresh) }
        findViewById<View>(R.id.row_permissions).setOnClickListener {
            if (SetupHelpers.permissionsGranted(this)) SetupHelpers.openAppSettings(this)
            else SetupHelpers.requestPermissions(this)
        }
        findViewById<View>(R.id.row_battery).setOnClickListener {
            if (SetupHelpers.batteryExempt(this)) {
                Snackbar.make(it, R.string.battery_ok, Snackbar.LENGTH_SHORT).show()
            } else {
                SetupHelpers.requestBatteryExemption(this)
            }
        }
        findViewById<View>(R.id.row_setup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.summary_about).text = getString(R.string.settings_version, version)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // If Android didn't show a prompt (permanently denied), point the user at the system settings page.
        if (requestCode == SetupHelpers.REQUEST_PERMISSIONS && !SetupHelpers.permissionsGranted(this)) {
            Snackbar.make(findViewById(R.id.row_permissions), R.string.permissions_denied, Snackbar.LENGTH_LONG)
                .setAction(R.string.app_settings) { SetupHelpers.openAppSettings(this) }
                .show()
        }
    }

    private fun refresh() {
        findViewById<TextView>(R.id.summary_watch).text = prefs.address ?: getString(R.string.watch_not_chosen)
        findViewById<TextView>(R.id.summary_permissions).setText(
            if (SetupHelpers.permissionsGranted(this)) R.string.permissions_ok else R.string.permissions_missing,
        )
        findViewById<TextView>(R.id.summary_battery).setText(
            if (SetupHelpers.batteryExempt(this)) R.string.battery_ok else R.string.battery_on,
        )
    }
}
