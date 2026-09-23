package com.elishaazaria.sayboard.utils

import android.util.Log
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Key(
    val label: String,
    val text: String,
    /** Long-press secondary label, shown top-right on the key. */
    val longLabel: String = "",
    /** Text inserted on long-press; falls back to [text] when blank. */
    val longText: String = ""
)

class KeysListSerializer : PreferenceSerializer<List<Key>> {
    private val serializer = ListSerializer(Key.serializer())
    override fun deserialize(value: String): List<Key> {
        // Android replaces literal "\n" into \ + newline for some reason
        return try {
            Json.decodeFromString(serializer, value.replace("\\\n", "\\n"))
        } catch (e: Exception) {
            // U11 fail-safe: one corrupt value never bricks the keyboard.
            // Persistent marker via CorruptPrefRepair (AppPrefs mirrors it).
            CorruptPrefRepair.markRepaired()
            Log.e("KeysList", "corrupt custom keys, repaired to empty", e)
            emptyList()
        }
    }

    override fun serialize(value: List<Key>): String {
        return Json.encodeToString(serializer, value)
    }

}

/** Max committed chars for any custom-key field (I-18). */
const val MaxCustomKeyChars = 32

private val BidiControls = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u061C\uFEFF]")

/**
 * I-18/I-22 strict key-field sanitizer: strip bidi + control chars
 * (single-line keys keep no \n\r at all) and cap at [MaxCustomKeyChars].
 */
fun sanitizeKeyField(raw: String): String {
    var out = BidiControls.replace(raw, "")
    out = out.filter { c -> c.code >= 0x20 && c != '\u007F' }
    if (out.length > MaxCustomKeyChars) out = out.take(MaxCustomKeyChars)
    return out
}

/** Sanitized copy of this key; blank label+text keys are dropped by callers. */
fun Key.sanitized(): Key = Key(
    label = sanitizeKeyField(label),
    text = sanitizeKeyField(text),
    longLabel = sanitizeKeyField(longLabel),
    longText = sanitizeKeyField(longText)
)

const val MaxCustomKeysPerRow = 5

val defaultCustomKeysList = listOf(
    Key(",", ",", ":", ":"),
    Key("!", "!", ";", ";"),
    Key("-", "-", "\"", "\""),
    Key("?", "?", "'", "'"),
    Key(".", ".", "/", "/")
)