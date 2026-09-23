package com.elishaazaria.sayboard.utils

import android.util.Log
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** String map pref, e.g. download URL -> "bytes:sha256". */
class StringMapSerializer : PreferenceSerializer<Map<String, String>> {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    /** U12 keep-last-good: last successfully-decoded map for this pref. */
    @Volatile
    private var lastGood: Map<String, String>? = null
    override fun deserialize(value: String): Map<String, String> {
        return try {
            val decoded = Json.decodeFromString(serializer, value)
            lastGood = decoded
            decoded
        } catch (e: Exception) {
            // U11/U12 fail-safe with persistent marker: restore the last
            // good map when one was decoded this session, else empty.
            CorruptPrefRepair.markRepaired()
            Log.e("StringMap", "corrupt string map, repaired", e)
            lastGood ?: emptyMap()
        }
    }

    override fun serialize(value: Map<String, String>): String {
        lastGood = value
        return Json.encodeToString(serializer, value)
    }
}

/**
 * U11/U12 persistent repair marker bridge (cycle-free).
 *
 * Serializers live in `utils` while the `prefsRepaired` boolean lives on
 * [com.elishaazaria.sayboard.AppPrefs] (which owns these serializers), so
 * they cannot set the pref directly without an init cycle. Serializers call
 * [markRepaired]; AppPrefs registers [persistHook] at construction to mirror
 * the flag into the persistent pref. [pendingRepair] covers a repair that
 * fired before the hook was registered.
 */
object CorruptPrefRepair {
    @Volatile
    var pendingRepair: Boolean = false
        private set

    @Volatile
    var persistHook: (() -> Unit)? = null

    fun markRepaired() {
        pendingRepair = true
        try {
            persistHook?.invoke()
        } catch (_: Exception) {
        }
    }
}
