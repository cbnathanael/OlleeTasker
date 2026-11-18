package com.naate.olleetasker

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import kotlin.concurrent.thread

/** Edits the beep tone in one of the [Tone.MAX_TONES] slots. */
class ToneEditActivity : AppCompatActivity() {

    private var index = 0
    private lateinit var name: EditText
    private lateinit var beepList: LinearLayout
    private lateinit var addBeep: Button
    private lateinit var preview: TextView
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = updatePreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tone_edit)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        OlleeLink.init(this)

        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, Tone.MAX_TONES - 1)
        name = findViewById(R.id.tone_name)
        beepList = findViewById(R.id.beep_list)
        addBeep = findViewById(R.id.add_beep)
        preview = findViewById(R.id.preview)
        title = getString(R.string.edit_tone, index)

        val tone = savedInstanceState?.getString(STATE_TONE)?.let { Tone.fromJson(JSONObject(it)) }
            ?: ToneStore(this).slots()[index]
        name.addTextChangedListener(watcher)
        show(tone)

        addBeep.setOnClickListener {
            addBeepRow(Beep(0, 300))
            updatePreview()
        }
        testButton = findViewById(R.id.test)
        saveButton = findViewById(R.id.save)
        testButton.setOnClickListener { test() }
        saveButton.setOnClickListener { save() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TONE, currentTone().toJson().toString())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tone_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_reset) {
            confirmReset()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun show(tone: Tone) {
        name.setText(tone.name)
        beepList.removeAllViews()
        tone.beeps.forEach(::addBeepRow)
        updatePreview()
    }

    private fun addBeepRow(beep: Beep) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_beep, beepList, false)
        // Every row reuses the same view IDs, so restore state from STATE_TONE instead of per-view state.
        row.isSaveFromParentEnabled = false
        val style = row.findViewById<MaterialButtonToggleGroup>(R.id.style)
        style.check(if (beep.style == 1) R.id.style_1 else R.id.style_0)
        style.addOnButtonCheckedListener { _, _, _ -> updatePreview() }
        val gap = row.findViewById<EditText>(R.id.gap)
        gap.setText(beep.gapMs.toString())
        gap.addTextChangedListener(watcher)
        row.findViewById<View>(R.id.remove).setOnClickListener {
            beepList.removeView(row)
            updatePreview()
        }
        beepList.addView(row)
    }

    private fun currentTone(): Tone {
        val beeps = (0 until beepList.childCount).map { i ->
            val row = beepList.getChildAt(i)
            val style = if (row.findViewById<MaterialButtonToggleGroup>(R.id.style).checkedButtonId == R.id.style_1) 1 else 0
            val gap = row.findViewById<EditText>(R.id.gap).text.toString().toIntOrNull() ?: 0
            Beep(style, gap)
        }
        return Tone(name.text.toString().trim(), beeps)
    }

    /** The tone as it will actually be sent, with gaps rounded to the encoding's 50 ms steps. */
    private fun roundedTone(): Tone = currentTone().let { t -> t.copy(beeps = t.beeps.map { it.copy(gapMs = Tone.roundGap(it.gapMs)) }) }

    private fun updatePreview() {
        for (i in 0 until beepList.childCount) {
            beepList.getChildAt(i).findViewById<TextView>(R.id.beep_number).text = (i + 1).toString()
        }
        addBeep.isEnabled = beepList.childCount < Tone.MAX_BEEPS
        val tone = roundedTone()
        val pattern = tone.beeps.joinToString(",", "[", "]") { "[${it.style},${it.gapMs}]" }
        val message = tone.message(index)
        preview.text = getString(
            R.string.tone_preview, message, message.length, Ble.MAX_WRITE_BYTES, tone.testMessage(), pattern,
        )
    }

    /** Plays the pattern as currently edited once on the watch, without saving it here or storing it on the watch. */
    private fun test() {
        val address = LinkPrefs(this).address ?: return toast(getString(R.string.choose_watch_first))
        val tone = roundedTone()
        if (tone.beeps.isEmpty()) return toast(getString(R.string.tone_needs_beeps))
        SetupHelpers.background(this) {
            OlleeLink.ensureService()
            OlleeLink.send(this, address, listOf(tone.testMessageBytes()))
            runOnUiThread { toast(getString(R.string.tone_sent, tone.testMessage())) }
        }
    }

    /** Saves the tone in the app, then stores it on the watch (OWT|). The tone stays saved even if sending fails. */
    private fun save() {
        val tone = roundedTone()
        val error = ToneStore(this).put(index, tone)
        if (error != null) return toast(error)
        val address = LinkPrefs(this).address
        if (address == null) {
            toast(getString(R.string.tone_saved_no_watch))
            return finish()
        }
        saveButton.isEnabled = false
        testButton.isEnabled = false
        saveButton.setText(R.string.sending)
        val app = applicationContext
        val message = tone.message(index)
        thread(name = "ollee-save-tone") {
            val result = try {
                OlleeLink.ensureService()
                OlleeLink.send(app, address, listOf(tone.messageBytes(index)))
                app.getString(R.string.tone_saved_sent, message)
            } catch (e: Exception) {
                app.getString(R.string.tone_saved_not_sent, e.message ?: e.toString())
            }
            runOnUiThread {
                Toast.makeText(app, result, Toast.LENGTH_LONG).show()
                if (!isDestroyed) finish()
            }
        }
    }

    /** Loads the factory tone for this slot into the editor; it's saved only when the user taps Save. */
    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_tone_title)
            .setMessage(R.string.reset_tone_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ -> show(Tone.DEFAULTS[index]) }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_INDEX = "index"
        private const val STATE_TONE = "tone"
    }
}
