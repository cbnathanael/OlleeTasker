package com.naate.olleetasker

import androidx.appcompat.app.AppCompatActivity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Configuration screen Tasker opens when the user adds/edits one of this plugin's actions.
 * Each action in Tasker's list is an alias of this activity; [kind] decides which operations it offers.
 */
class EditActivity : AppCompatActivity() {

    private lateinit var kind: ActionKind
    private lateinit var operation: Spinner
    private lateinit var address: EditText
    private lateinit var value: EditText
    private lateinit var prefix: EditText
    private lateinit var preview: TextView
    private lateinit var raw: CheckBox
    private lateinit var tone: EditText
    private lateinit var repeat: EditText
    private lateinit var format: Spinner
    private lateinit var outputs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        val initial = PluginConfig.fromBundle(intent.getBundleExtra(Tasker.EXTRA_BUNDLE))
        kind = ActionKind.fromComponent(intent.component?.className)
        // Actions saved before the split all open this component; show the editor that matches them.
        if (initial != null && initial.operation !in kind.operations) kind = ActionKind.of(initial.operation)

        operation = findViewById(R.id.operation)
        address = findViewById(R.id.address)
        value = findViewById(R.id.value)
        prefix = findViewById(R.id.prefix)
        preview = findViewById(R.id.message_limit)
        raw = findViewById(R.id.raw_message)
        raw.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        tone = findViewById(R.id.tone)
        repeat = findViewById(R.id.repeat)
        format = findViewById(R.id.format)
        outputs = findViewById(R.id.outputs)

        findViewById<TextView>(R.id.title).setText(
            when (kind) {
                ActionKind.MESSAGE -> R.string.action_message
                ActionKind.TONE -> R.string.action_tone
                ActionKind.CONNECTION -> R.string.action_connection
            },
        )
        show(R.id.operation_group, kind == ActionKind.CONNECTION)
        operation.adapter = spinnerAdapter(kind.operations.map { it.label })
        format.adapter = spinnerAdapter(ValueFormat.entries.map { it.label })
        operation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        format.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        listOf(value, prefix, tone, repeat).forEach { it.doAfterTextChanged { updatePreview() } }

        if (savedInstanceState == null) {
            populate(initial ?: PluginConfig(operation = kind.operations.first()))
        }
        updateVisibility()

        findViewById<Button>(R.id.pick_device).setOnClickListener {
            DevicePicker(this) { address.setText(it) }.show()
        }
        findViewById<Button>(R.id.choose_tone).setOnClickListener { chooseTone() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }

        val missing = Perms.missing(this, Perms.CONNECT)
        if (missing.isNotEmpty()) requestPermissions(missing, REQUEST_PERMISSION)
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun populate(config: PluginConfig) {
        operation.setSelection(kind.operations.indexOf(config.operation).coerceAtLeast(0))
        address.setText(config.address)
        if (config.operation == Operation.SEND_TONE) {
            tone.setText(config.value)
        } else {
            value.setText(config.value)
            prefix.setText(config.prefix)
            raw.isChecked = config.raw
            // Older actions stored a tone name; show its notification number instead.
            val slot = if (config.tone.length > 1 && !config.tone.contains('%')) ToneStore(this).slotOf(config.tone) else -1
            tone.setText(if (slot >= 0) slot.toString() else config.tone)
            repeat.setText(config.repeat)
        }
        format.setSelection(config.format.ordinal)
    }

    private fun selectedOperation(): Operation = when (kind) {
        ActionKind.MESSAGE -> Operation.SEND
        else -> kind.operations.getOrElse(operation.selectedItemPosition) { kind.operations.first() }
    }

    private fun updateVisibility() {
        val op = selectedOperation()
        val isSend = op == Operation.SEND
        show(R.id.watch_group, op.usesWatch)
        show(R.id.raw_message, isSend)
        show(R.id.tone_group, op == Operation.SEND_TONE || (isSend && !raw.isChecked))
        show(R.id.prefix_group, !raw.isChecked)
        show(R.id.repeat_group, isSend && !raw.isChecked)
        findViewById<TextView>(R.id.tone_label)
            .setText(if (kind == ActionKind.MESSAGE) R.string.label_tone_optional else R.string.label_tone)
        tone.hint = if (kind == ActionKind.MESSAGE) getString(R.string.hint_no_tone) else null
        show(R.id.message_group, isSend)
        show(R.id.message_limit, isSend)
        updatePreview()
        outputs.text = "Outputs: " + relevantVariables().joinToString(", ") { it.substringBefore('\n') } +
            ". On failure, %errmsg is set."
    }

    /** Shows exactly what a Send message action will write, e.g. "OW23Hello (9 of 20 bytes)". */
    private fun updatePreview() {
        if (kind != ActionKind.MESSAGE) return
        val c = currentConfig()
        val fields = if (c.raw) listOf(c.value) else listOf(c.prefix, c.tone, c.repeat, c.value)
        if (fields.any { it.contains('%') }) {
            preview.setText(if (c.raw) R.string.message_limit_raw else R.string.message_limit)
            return
        }
        preview.text = runCatching {
            val digit = if (c.raw) PluginConfig.NO_TONE else ActionRunner.toneDigit(this, c.tone)
            val repeatDigit = if (c.raw) PluginConfig.NO_REPEAT else ActionRunner.repeatDigit(c.repeat)
            val bytes = ActionRunner.messageBytes(c, digit, repeatDigit)
            getString(R.string.message_preview, Ble.decodeWatchText(bytes), bytes.size, Ble.MAX_WRITE_BYTES)
        }.getOrElse { it.message }
    }

    private fun show(id: Int, visible: Boolean) {
        findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun currentConfig() = PluginConfig(
        address = address.text.toString().trim(),
        operation = selectedOperation(),
        value = if (selectedOperation() == Operation.SEND_TONE) tone.text.toString().trim() else value.text.toString(),
        prefix = if (kind == ActionKind.MESSAGE) prefix.text.toString().trim() else PluginConfig.DEFAULT_PREFIX,
        tone = if (kind == ActionKind.MESSAGE) tone.text.toString().trim() else "",
        repeat = if (kind == ActionKind.MESSAGE) repeat.text.toString().trim() else "",
        raw = kind == ActionKind.MESSAGE && raw.isChecked,
        format = ValueFormat.entries[format.selectedItemPosition],
    )

    private fun save() {
        val config = currentConfig()
        validate(config)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }

        val result = Intent()
            .putExtra(Tasker.EXTRA_BUNDLE, config.toBundle())
            .putExtra(Tasker.EXTRA_BLURB, config.blurb())
            .putExtra(Tasker.EXTRA_RELEVANT_VARIABLES, relevantVariables())
            .putExtra(Tasker.EXTRA_REQUESTED_TIMEOUT, config.requestedTimeoutMs())
        setResult(RESULT_OK, result)
        finish()
    }

    /** Returns an error message, or null if valid. Fields containing %variables are checked at run time. */
    private fun validate(c: PluginConfig): String? {
        fun isVariable(s: String) = s.contains('%')
        val op = c.operation

        if (op.usesWatch) {
            if (c.address.isEmpty() && LinkPrefs(this).address == null) {
                return "Enter or pick a watch address (or choose one in the Ollee Watch plugin app)"
            }
            if (c.address.isNotEmpty() && !isVariable(c.address) &&
                !BluetoothAdapter.checkBluetoothAddress(c.address.uppercase())
            ) {
                return "Invalid address (expected AA:BB:CC:DD:EE:FF)"
            }
        }
        if (op == Operation.SEND) {
            if (!c.raw && !isVariable(c.tone)) {
                runCatching { ActionRunner.toneDigit(this, c.tone) }.exceptionOrNull()?.let { return it.message }
            }
            if (!c.raw && !isVariable(c.repeat)) {
                runCatching { ActionRunner.repeatDigit(c.repeat) }.exceptionOrNull()?.let { return it.message }
            }
            if (!isVariable(c.value) && (c.raw || !isVariable(c.prefix))) {
                runCatching { ActionRunner.messageBytes(c, PluginConfig.NO_TONE, PluginConfig.NO_REPEAT) }.exceptionOrNull()?.let { return it.message }
            }
        }
        if (op == Operation.SEND_TONE) {
            if (c.value.isEmpty()) return "Choose a tone"
            if (!isVariable(c.value) && ToneStore(this).find(c.value) == null) return "There's no tone named \"${c.value}\""
        }
        return null
    }

    private fun chooseTone() {
        if (kind == ActionKind.MESSAGE) {
            val slots = ToneStore(this).slots()
            val items = listOf(getString(R.string.no_tone)) + slots.mapIndexed { i, t ->
                getString(R.string.tone_slot_name, i, t?.name ?: getString(R.string.empty_slot))
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_tone)
                .setItems(items.toTypedArray()) { _, which -> tone.setText(if (which == 0) "" else (which - 1).toString()) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val names = ToneStore(this).all().map { it.name }
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.no_tones_yet, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_tone)
            .setItems(names.toTypedArray()) { _, which -> tone.setText(names[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun relevantVariables(): Array<String> = arrayOf(
        "${ActionRunner.VAR_STATE}\nLink state\nstopped, paused, waiting, connecting or connected",
        "${ActionRunner.VAR_ADDRESS}\nWatch address\nAddress of the linked watch",
    )

    private companion object {
        const val REQUEST_PERMISSION = 1
    }
}
