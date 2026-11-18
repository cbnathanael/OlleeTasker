package com.naate.olleetasker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** Lists the [Tone.MAX_TONES] beep tones; tap one to edit it. */
class TonesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tones)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = findViewById(R.id.tone_list)
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
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        ToneStore(this).slots().forEachIndexed { index, tone ->
            val row = inflater.inflate(R.layout.item_tone, list, false)
            row.findViewById<TextView>(R.id.tone_name).text = getString(R.string.tone_slot_name, index, tone.name)
            row.findViewById<TextView>(R.id.tone_summary).text = resources.getQuantityString(
                R.plurals.tone_summary, tone.beeps.size, tone.beeps.size, tone.message(index),
            )
            row.setOnClickListener { edit(index) }
            list.addView(row)
        }
    }

    private fun edit(index: Int) {
        startActivity(Intent(this, ToneEditActivity::class.java).putExtra(ToneEditActivity.EXTRA_INDEX, index))
    }
}
