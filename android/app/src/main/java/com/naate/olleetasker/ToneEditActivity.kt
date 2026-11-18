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

/** Creates or edits one beep tone. */
class ToneEditActivity : AppCompatActivity() {

    private var index = -1
    private lateinit var name: EditText
    private lateinit var beepList: LinearLayout
    private lateinit var addBeep: Button
    private lateinit var preview: TextView
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

        index = intent.getIntExtra(EXTRA_INDEX, -1)
        name = findViewById(R.id.tone_name)
        beepList = findViewById(R.id.beep_list)
        addBeep = findViewById(R.id.add_beep)
        preview = findViewById(R.id.preview)
        setTitle(if (index == -1) R.string.new_tone else R.string.edit_tone)

        val tone = savedInstanceState?.getString(STATE_TONE)?.let { Tone.fromJson(JSONObject(it)) }
            ?: ToneStore(this).slots().getOrNull(index)
            ?: Tone("", listOf(Beep(0, 300), Beep(0, 300), Beep(1, 0)))
        name.setText(tone.name)
        name.addTextChangedListener(watcher)
        tone.beeps.forEach(::addBeepRow)
        updatePreview()

        addBeep.setOnClickListener {
            addBeepRow(Beep(0, 300))
            updatePreview()
        }
        findViewById<Button>(R.id.test).setOnClickListener { test() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TONE, currentTone().toJson().toString())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (index != -1) menuInflater.inflate(R.menu.tone_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_delete) {
            confirmDelete()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
        val message = tone.message(slot())
        preview.text = getString(R.string.tone_preview, message, message.length, Ble.MAX_WRITE_BYTES, pattern)
    }

    /** This tone's slot, or the slot it will get when saved. */
    private fun slot(): Int = if (index != -1) index else ToneStore(this).firstFreeSlot().coerceAtLeast(0)

    private fun test() {
        val address = LinkPrefs(this).address ?: return toast(getString(R.string.choose_watch_first))
        val tone = roundedTone()
        if (tone.beeps.isEmpty()) return toast(getString(R.string.tone_needs_beeps))
        SetupHelpers.background(this) {
            OlleeLink.ensureService()
            OlleeLink.send(this, address, listOf(tone.messageBytes(slot())))
            runOnUiThread { toast(getString(R.string.tone_sent, tone.message(slot()))) }
        }
    }

    private fun save() {
        val error = ToneStore(this).put(index, roundedTone())
        if (error != null) return toast(error)
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_tone_title, name.text.toString().ifBlank { getString(R.string.edit_tone) }))
            .setMessage(R.string.delete_tone_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ToneStore(this).delete(index)
                finish()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_INDEX = "index"
        private const val STATE_TONE = "tone"
    }
}
