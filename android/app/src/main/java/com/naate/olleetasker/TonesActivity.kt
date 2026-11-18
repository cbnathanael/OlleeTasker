package com.naate.olleetasker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** Lists the saved beep tones (up to [Tone.MAX_TONES]). */
class TonesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var add: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tones)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = findViewById(R.id.tone_list)
        empty = findViewById(R.id.empty)
        add = findViewById(R.id.add_tone)
        add.setOnClickListener { edit(-1) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val slots = ToneStore(this).slots()
        val tones = slots.filterNotNull()
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        slots.forEachIndexed { index, tone ->
            if (tone == null) return@forEachIndexed
            val row = inflater.inflate(R.layout.item_tone, list, false)
            row.findViewById<TextView>(R.id.tone_name).text = getString(R.string.tone_slot_name, index, tone.name)
            row.findViewById<TextView>(R.id.tone_summary).text = resources.getQuantityString(
                R.plurals.tone_summary, tone.beeps.size, tone.beeps.size, tone.message(index),
            )
            row.setOnClickListener { edit(index) }
            list.addView(row)
        }
        empty.visibility = if (tones.isEmpty()) View.VISIBLE else View.GONE
        add.isEnabled = tones.size < Tone.MAX_TONES
        add.text = if (add.isEnabled) getString(R.string.add_tone) else getString(R.string.tones_full, Tone.MAX_TONES)
    }

    private fun edit(index: Int) {
        startActivity(Intent(this, ToneEditActivity::class.java).putExtra(ToneEditActivity.EXTRA_INDEX, index))
    }
}
