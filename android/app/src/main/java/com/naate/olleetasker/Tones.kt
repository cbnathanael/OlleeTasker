package com.naate.olleetasker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One beep: [style] (0 or 1) followed by [gapMs] of silence before the next beep. */
data class Beep(val style: Int, val gapMs: Int)

/**
 * A named beep pattern. It is sent to the watch as `OWT|`, the tone's slot digit (0–4), then one letter per beep:
 * lowercase for style 0, uppercase for style 1, and the letter's position (a = 0) times [STEP_MS] as the gap.
 * For example [[0,300],[0,300],[1,0]] in slot 2 is sent as "OWT|2ggA".
 * To play a pattern once without storing it on the watch, it is sent as `OWA|` plus the letters, e.g. "OWA|ggA".
 */
data class Tone(val name: String, val beeps: List<Beep>) {

    /** The beep letters only, e.g. "ggA". */
    fun pattern(): String = beeps.take(MAX_BEEPS).joinToString("") { beep ->
        val base = if (beep.style == 1) 'A' else 'a'
        (base + gapSteps(beep.gapMs)).toString()
    }

    /** The full message for this tone stored in [slot], e.g. "OWT|2ggA". */
    fun message(slot: Int): String = PREFIX + slot + pattern()

    fun messageBytes(slot: Int): ByteArray = Ble.encodeWatchText(message(slot))

    /** The message that plays this pattern once on the watch without storing it, e.g. "OWA|ggA". */
    fun testMessage(): String = TEST_PREFIX + pattern()

    fun testMessageBytes(): ByteArray = Ble.encodeWatchText(testMessage())

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("beeps", JSONArray(beeps.map { JSONArray(listOf(it.style, it.gapMs)) }))

    companion object {
        const val PREFIX = "OWT|"
        const val TEST_PREFIX = "OWA|"
        const val STEP_MS = 50
        const val MAX_GAP_MS = 25 * STEP_MS
        const val MAX_BEEPS = Ble.MAX_WRITE_BYTES - PREFIX.length - 1
        const val MAX_TONES = 5

        /** Rounds [ms] to the nearest step the encoding can represent. */
        fun roundGap(ms: Int): Int = gapSteps(ms) * STEP_MS

        private fun gapSteps(ms: Int): Int = ((ms + STEP_MS / 2) / STEP_MS).coerceIn(0, 25)

        /** The factory tone for each slot. Gaps are rounded to [STEP_MS] (e.g. 70 → 50, 125 → 150), as sent. */
        val DEFAULTS: List<Tone> = listOf(
            listOf(0 to 300, 1 to 70, 1 to 0),
            listOf(1 to 800, 0 to 40, 0 to 40, 0 to 40, 0 to 0),
            listOf(1 to 100, 1 to 100, 1 to 100, 1 to 0),
            listOf(0 to 50, 1 to 800, 0 to 125, 0 to 125, 0 to 0),
            listOf(0 to 50, 1 to 500, 1 to 70, 0 to 0),
        ).mapIndexed { slot, beeps -> Tone("Tone $slot", beeps.map { (style, gap) -> Beep(style, roundGap(gap)) }) }

        fun fromJson(o: JSONObject): Tone {
            val arr = o.getJSONArray("beeps")
            val beeps = (0 until arr.length()).map {
                val b = arr.getJSONArray(it)
                Beep(b.getInt(0), b.getInt(1))
            }
            return Tone(o.getString("name"), beeps)
        }
    }
}

/**
 * The tones in the [Tone.MAX_TONES] fixed slots (0–4). Every slot always holds a tone: the user's saved one,
 * or [Tone.DEFAULTS] for that slot.
 */
class ToneStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tones", Context.MODE_PRIVATE)

    fun slots(): List<Tone> {
        val json = prefs.getString(KEY_TONES, null)
        val stored = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { if (arr.isNull(it)) null else Tone.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return List(Tone.MAX_TONES) { stored.getOrNull(it) ?: Tone.DEFAULTS[it] }
    }

    fun find(name: String): Tone? = slots().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** The slot of the tone named [name], or -1. */
    fun slotOf(name: String): Int = slots().indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }

    /** Saves [tone] in [slot]. Returns an error message, or null on success. */
    fun put(slot: Int, tone: Tone): String? {
        val tones = slots().toMutableList()
        val others = tones.filterIndexed { i, _ -> i != slot }
        when {
            slot !in tones.indices -> return "Tone number must be 0–${Tone.MAX_TONES - 1}"
            tone.name.isBlank() -> return "Enter a name"
            tone.beeps.isEmpty() -> return "Add at least one beep"
            tone.beeps.size > Tone.MAX_BEEPS -> return "A tone can have at most ${Tone.MAX_BEEPS} beeps"
            others.any { it.name.equals(tone.name, ignoreCase = true) } -> return "Another tone is already named \"${tone.name}\""
        }
        tones[slot] = tone
        write(tones)
        return null
    }

    private fun write(tones: List<Tone>) {
        prefs.edit().putString(KEY_TONES, JSONArray(tones.map { it.toJson() }).toString()).apply()
    }

    private companion object {
        const val KEY_TONES = "tones"
    }
}
