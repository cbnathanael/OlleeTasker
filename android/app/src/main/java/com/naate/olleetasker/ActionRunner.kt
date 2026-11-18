package com.naate.olleetasker

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Bundle

/** Executes one configured plugin action and returns the Tasker output variables. */
object ActionRunner {
    const val VAR_STATE = "%ollee_state"
    const val VAR_ADDRESS = "%ollee_address"

    fun run(context: Context, config: PluginConfig): Bundle {
        OlleeLink.init(context)
        val vars = Bundle()
        when (config.operation) {
            Operation.STATUS -> Unit
            Operation.PAUSE -> OlleeLink.pause(context)
            Operation.RESUME -> OlleeLink.resume(context)
            Operation.DISCONNECT -> OlleeLink.stop(context)
            Operation.CONNECT -> OlleeLink.connect(context, resolveAddress(context, config))
            Operation.SEND -> {
                val digit = if (config.raw) PluginConfig.NO_TONE else toneDigit(context, config.tone)
                val repeat = if (config.raw) PluginConfig.NO_REPEAT else repeatDigit(config.repeat)
                val bytes = messageBytes(config, digit, repeat)
                OlleeLink.send(context, resolveAddress(context, config), listOf(bytes))
            }
            Operation.SEND_TONE -> {
                val (slot, tone) = findTone(context, config.value)
                OlleeLink.send(context, resolveAddress(context, config), listOf(tone.messageBytes(slot)))
            }
        }
        vars.putString(VAR_STATE, OlleeLink.state.label)
        vars.putString(VAR_ADDRESS, OlleeLink.address.orEmpty())
        return vars
    }

    fun encode(config: PluginConfig): ByteArray = try {
        when (config.format) {
            ValueFormat.TEXT -> Ble.encodeWatchText(config.value)
            ValueFormat.UTF8 -> config.value.toByteArray(Charsets.UTF_8)
            ValueFormat.HEX -> Ble.hexToBytes(config.value)
        }
    } catch (e: IllegalArgumentException) {
        throw GattException(e.message ?: "Invalid value")
    }

    /**
     * Builds the single write for a SEND: prefix, notification number (0–4 or "-"), chime repeat count (0–9)
     * and the message, dropping anything past the watch's 20-byte limit. E.g. "OW23Hello".
     * With [PluginConfig.raw], just the message.
     */
    fun messageBytes(config: PluginConfig, toneDigit: Char, repeatDigit: Char): ByteArray {
        if (config.raw) return truncate(encode(config), Ble.MAX_WRITE_BYTES, config.format)
        val prefix = config.prefix.trim().ifEmpty { PluginConfig.DEFAULT_PREFIX }
        if (UNSET_VARIABLE.containsMatchIn(prefix)) throw GattException("The prefix variable $prefix isn't set")
        val header = try {
            Ble.encodeWatchText("$prefix$toneDigit$repeatDigit")
        } catch (e: IllegalArgumentException) {
            throw GattException("Prefix: ${e.message}")
        }
        val data = encode(config)
        val room = Ble.MAX_WRITE_BYTES - header.size
        if (room <= 0) return header.copyOf(Ble.MAX_WRITE_BYTES)
        return header + truncate(data, room, config.format)
    }

    private fun truncate(data: ByteArray, max: Int, format: ValueFormat): ByteArray {
        var end = minOf(data.size, max)
        // Don't cut a UTF-8 character in half: back up to the start of the character at the cut.
        if (format == ValueFormat.UTF8 && end < data.size) {
            while (end > 0 && (data[end].toInt() and 0xC0) == 0x80) end--
        }
        return data.copyOf(end)
    }

    /** Resolves a SEND's notification number: blank = none ("-"), 0–4, or (older actions) a tone name. */
    fun toneDigit(context: Context, tone: String): Char {
        val t = tone.trim()
        if (t.isEmpty() || t == PluginConfig.NO_TONE.toString()) return PluginConfig.NO_TONE
        if (t.length == 1 && t[0] in '0' until '0' + Tone.MAX_TONES) return t[0]
        if (UNSET_VARIABLE.containsMatchIn(t)) throw GattException("The notification number variable $t isn't set")
        val slot = ToneStore(context).slotOf(t)
        if (slot >= 0) return '0' + slot
        throw GattException("Notification number must be 0–${Tone.MAX_TONES - 1} or blank, not \"$t\"")
    }

    /** Resolves a SEND's chime repeat count: blank = 0, or 0–9. */
    fun repeatDigit(repeat: String): Char {
        val r = repeat.trim()
        if (r.isEmpty()) return PluginConfig.NO_REPEAT
        if (r.length == 1 && r[0] in '0'..'9') return r[0]
        if (UNSET_VARIABLE.containsMatchIn(r)) throw GattException("The repeat variable $r isn't set")
        throw GattException("Repeat must be 0–9 or blank, not \"$r\"")
    }

    /** The slot and tone named [name]. */
    private fun findTone(context: Context, name: String): Pair<Int, Tone> {
        val n = name.trim()
        if (n.isEmpty()) throw GattException("No tone chosen")
        if (n.contains('%')) throw GattException("The tone variable $n isn't set")
        val store = ToneStore(context)
        val slot = store.slotOf(n)
        return store.slots().getOrNull(slot)?.let { slot to it }
            ?: throw GattException("There's no tone named \"$n\". Create it under Notification tones in the Ollee Watch app.")
    }

    private val UNSET_VARIABLE = Regex("%[A-Za-z0-9_]+")

    private fun resolveAddress(context: Context, config: PluginConfig): String {
        val raw = config.address.trim().ifEmpty { LinkPrefs(context).address.orEmpty() }
        if (raw.isEmpty()) throw GattException("No watch chosen. Pick one in the action, or in the Ollee Watch plugin app.")
        if (raw.contains('%')) throw GattException("The address variable $raw isn't set")
        val normalized = raw.uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(normalized)) throw GattException("Invalid Bluetooth address: $raw")
        return normalized
    }
}
