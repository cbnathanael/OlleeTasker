package com.naate.olleetasker

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast

/** Configuration screen for the "Ollee Watch" Tasker event (Profile > Event > Plugin). */
class EventEditActivity : AppCompatActivity() {

    private lateinit var type: Spinner
    private lateinit var match: Spinner
    private lateinit var pattern: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_edit)

        type = findViewById(R.id.event_type)
        match = findViewById(R.id.match_mode)
        pattern = findViewById(R.id.pattern)

        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EventType.entries.map { it.label })
        match.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MatchMode.entries.map { it.label })
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        type.onItemSelectedListener = listener
        match.onItemSelectedListener = listener

        if (savedInstanceState == null) {
            EventConfig.fromBundle(intent.getBundleExtra(Tasker.EXTRA_BUNDLE))?.let {
                type.setSelection(it.type.ordinal)
                match.setSelection(it.match.ordinal)
                pattern.setText(it.pattern)
            }
        }
        updateVisibility()

        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    private fun selectedType() = EventType.entries[type.selectedItemPosition]
    private fun selectedMatch() = MatchMode.entries[match.selectedItemPosition]

    private fun updateVisibility() {
        val isMessage = selectedType() == EventType.MESSAGE
        findViewById<View>(R.id.match_group).visibility = if (isMessage) View.VISIBLE else View.GONE
        pattern.visibility = if (isMessage && selectedMatch() != MatchMode.ANY) View.VISIBLE else View.GONE
    }

    private fun save() {
        val config = EventConfig(selectedType(), selectedMatch(), pattern.text.toString())
        if (config.type == EventType.MESSAGE && config.match != MatchMode.ANY) {
            if (config.pattern.isEmpty()) return toast("Enter the text to match")
            if (config.match == MatchMode.REGEX && runCatching { Regex(config.pattern) }.isFailure) {
                return toast("Invalid regex")
            }
        }
        val result = Intent()
            .putExtra(Tasker.EXTRA_BUNDLE, config.toBundle())
            .putExtra(Tasker.EXTRA_BLURB, config.blurb())
            .putExtra(Tasker.EXTRA_RELEVANT_VARIABLES, relevantVariables(config.type))
        setResult(RESULT_OK, result)
        finish()
    }

    private fun relevantVariables(type: EventType): Array<String> {
        val vars = mutableListOf(
            "${TaskerEvents.VAR_EVENT}\nEvent\nmessage, connected or disconnected",
            "${TaskerEvents.VAR_ADDRESS}\nWatch address\nAddress of the watch",
        )
        when (type) {
            EventType.MESSAGE -> {
                vars += "${TaskerEvents.VAR_MESSAGE}\nMessage\nMessage from the watch, as text (one character per byte)"
                vars += "${TaskerEvents.VAR_HEX}\nMessage (hex)\nMessage from the watch, as hex"
            }
            EventType.DISCONNECTED -> vars += "${TaskerEvents.VAR_REASON}\nReason\nlost, paused, stopped or switched"
            EventType.CONNECTED -> Unit
        }
        return vars.toTypedArray()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
