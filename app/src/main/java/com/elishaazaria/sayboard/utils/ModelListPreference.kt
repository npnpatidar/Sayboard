package com.elishaazaria.sayboard.utils

import android.util.Log
import com.elishaazaria.sayboard.data.InstalledModelReference
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ModelListSerializer : PreferenceSerializer<List<InstalledModelReference>> {
    private val serializer = ListSerializer(InstalledModelReference.serializer())
    override fun deserialize(value: String): List<InstalledModelReference> {
        return try {
            Json.decodeFromString(serializer, value)
        } catch (e: Exception) {
            // U11 fail-safe: a corrupt order never bricks settings.
            // Persistent marker via CorruptPrefRepair (AppPrefs mirrors it).
            CorruptPrefRepair.markRepaired()
            Log.e("ModelList", "corrupt models order, repaired to empty", e)
            emptyList()
        }
    }

    override fun serialize(value: List<InstalledModelReference>): String {
        return Json.encodeToString(serializer, value)
    }

}