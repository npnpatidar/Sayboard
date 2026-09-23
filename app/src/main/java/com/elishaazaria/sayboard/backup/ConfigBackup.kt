package com.elishaazaria.sayboard.backup

import com.elishaazaria.sayboard.AppPrefs
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.KeepScreenAwakeMode
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.ThemeMode
import com.elishaazaria.sayboard.downloader.Sanitize
import com.elishaazaria.sayboard.utils.AppLog
import com.elishaazaria.sayboard.utils.Key
import com.elishaazaria.sayboard.utils.MaxCustomKeysPerRow
import com.elishaazaria.sayboard.utils.sanitized
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Configuration backup (settings + model order/aliases + custom keys).
 * Model weight files are NOT included: restoring on a device without the
 * model directories simply prunes those entries on the next reload.
 *
 * Excluded from export (U8/I-16): `m_download_hashes` (bounded
 * trust-on-first-use records) and `m_locale_per_app` (per-app package
 * history). The installed-model list holds private install paths and is
 * only restored when the include-paths toggle is on (U8).
 */
@Serializable
data class ConfigBackup(
    val app: String = "sayboard",
    val version: Int = 1,
    val modelsOrder: List<InstalledModelReference> = listOf(),
    val customKeys: List<Key> = listOf(),
    val prefs: Map<String, String> = mapOf()
)

object ConfigBackupIO {
    private const val TAG = "ConfigBackup"
    private val json = Json { ignoreUnknownKeys = true }
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    /** Cap for the per-app locale memory (also enforced at write time). */
    const val MAX_LOCALE_PER_APP = 100
    /** U6/U9 restore caps. */
    const val MAX_MODELS_RESTORE = 64
    const val MAX_KEYS_RESTORE = 64
    const val MAX_HASHES_RESTORE = 200
    private val HASH_VALUE_PATTERN = Regex("^\\d+:[0-9a-fA-F:+-]{0,256}$")
    private val COLOR_PATTERN = Regex("^(system|default|#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?)$")
    private val BIDI = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u061C\uFEFF]")

    fun export(prefs: AppPrefs, modelsDir: File? = null): String {
        val p = mutableMapOf<String, String>()
        p["b_keep_model_in_ram"] = prefs.logicKeepModelInRam.get().toString()
        // F4: relativize pin keys when the models dir is known so backups
        // never carry absolute device paths (same discipline as modelsOrder).
        p["m_unpinned_models"] = json.encodeToString(
            prefs.unpinnedModels.get().filter { it.key.length <= 256 }
                .toList().take(64).toMap()
                .mapKeys { (k, _) ->
                    if (modelsDir != null) InstalledModelReference.relativeKey(modelsDir, k) else k
                }
        )
        p["b_listen_immediately"] = prefs.logicListenImmediately.get().toString()
        p["b_auto_switch_back_ime"] = prefs.logicAutoSwitchBack.get().toString()
        p["b_auto_capitalize"] = prefs.logicAutoCapitalize.get().toString()
        p["b_vad_auto_stop"] = prefs.logicVadAutoStop.get().toString()
        p["e_keep_screen_awake"] = prefs.logicKeepScreenAwake.get().name
        p["s_default_keyboard"] = prefs.logicDefaultIME.get()
        p["b_always_return_default_keyboard"] = prefs.logicReturnToDefaultIME.get().toString()
        p["f_keyboard_height_portrait"] = prefs.keyboardHeightPortrait.get().toString()
        p["f_keyboard_height_landscape"] = prefs.keyboardHeightLandscape.get().toString()
        p["b_show_custom_row_portrait"] = prefs.keyboardShowCustomRowPortrait.get().toString()
        p["b_show_custom_row_landscape"] = prefs.keyboardShowCustomRowLandscape.get().toString()
        p["ui_theme_mode"] = prefs.uiThemeMode.get().name
        p["ui_dynamic_colors"] = prefs.uiDynamicColors.get().toString()
        p["ui_key_borders"] = prefs.uiKeyBorders.get().toString()
        p["ui_accent"] = prefs.uiAccent.get()
        p["ui_keyboard_background"] = prefs.uiKeyboardBackground.get()
        p["b_keep_alive_service"] = prefs.logicKeepAliveService.get().toString()
        p["b_auto_switch_model_by_locale"] = prefs.logicAutoSwitchModelByLocale.get().toString()
        p["b_remember_locale_per_app"] = prefs.logicRememberLocalePerApp.get().toString()
        p["b_allow_external_recognition"] = prefs.logicAllowExternalRecognition.get().toString()
        p["b_file_logging"] = prefs.logicFileLogging.get().toString()
        p["b_offline_mode"] = prefs.logicOfflineMode.get().toString()
        p["b_allow_voice_in_password"] = prefs.logicAllowVoiceInPassword.get().toString()
        p["b_restore_model_paths"] = prefs.logicRestoreModelPaths.get().toString()
        // U8/I-16: downloadHashes and locale-per-app are never exported.
        // D17/U6: store model refs relative to the models dir when known so
        // backups never carry absolute device paths; legacy absolute entries
        // still restore (confined) via resolveAbsolute.
        val orderForExport = runCatching {
            val order = prefs.modelsOrder.get()
            if (modelsDir != null) order.map { it.toRelative(modelsDir) } else order
        }.getOrDefault(prefs.modelsOrder.get())
        return json.encodeToString(
            ConfigBackup.serializer(),
            ConfigBackup(
                modelsOrder = orderForExport,
                customKeys = prefs.keyboardKeysCustom.get(),
                prefs = p
            )
        )
    }

    /**
     * Applies a backup; one bad value never aborts the rest. Returns a report.
     *
     * @param enabledImeIds validated IME ids for `s_default_keyboard`
     * (U7/I-11-restore): unknown ids are skipped and reported as ignored.
     * @param modelsDir when non-null, restored model paths must stay inside
     * it (U6/D17 confinement via [Sanitize.requireWithin] on the resolved
     * absolute path; relative entries resolve against it, legacy absolute
     * entries are confined as-is). When null, confinement is impossible so
     * every model entry is REJECTED (fail-closed, counted as ignored) —
     * never allowed blindly. Restored entries are stored relative via
     * `toRelative` going forward.
     * @param includeModelPaths override for the include-paths toggle; null
     * reads the `logicRestoreModelPaths` pref (U8, default exclude).
     */
    fun restore(
        raw: String,
        prefs: AppPrefs,
        enabledImeIds: List<String>? = null,
        modelsDir: File? = null,
        includeModelPaths: Boolean? = null
    ): String {
        val root = lenient.parseToJsonElement(raw).jsonObject
        val app = root["app"]?.jsonPrimitive?.contentOrNull
        if (app != "sayboard") throw IllegalArgumentException("Not a Sayboard backup")
        val version = root["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (version != 1) throw IllegalArgumentException("Unsupported backup version $version")
        var applied = 0
        var skipped = 0
        var ignored = 0
        fun set(ok: Boolean) {
            if (ok) applied++ else skipped++
        }
        val p: Map<String, String> = try {
            root["prefs"]?.let { lenient.decodeFromJsonElement<Map<String, String>>(it) }
                ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        p["b_keep_model_in_ram"]?.let { set(runCatching { prefs.logicKeepModelInRam.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_listen_immediately"]?.let { set(runCatching { prefs.logicListenImmediately.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_auto_switch_back_ime"]?.let { set(runCatching { prefs.logicAutoSwitchBack.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_auto_capitalize"]?.let { set(runCatching { prefs.logicAutoCapitalize.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_vad_auto_stop"]?.let { set(runCatching { prefs.logicVadAutoStop.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["e_keep_screen_awake"]?.let { v -> set(runCatching { prefs.logicKeepScreenAwake.set(KeepScreenAwakeMode.valueOf(v)); true }.getOrDefault(false)) }
        p["s_default_keyboard"]?.let { v ->
            // U7/I-11-restore: unknown IME ids are skipped, never stored.
            if (enabledImeIds != null && v.isNotBlank() && v !in enabledImeIds) {
                ignored++
            } else {
                set(runCatching { prefs.logicDefaultIME.set(v); true }.getOrDefault(false))
            }
        }
        p["b_always_return_default_keyboard"]?.let { set(runCatching { prefs.logicReturnToDefaultIME.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["f_keyboard_height_portrait"]?.let { v ->
            set(runCatching {
                val h = v.toFloat()
                require(h.isFinite())
                prefs.keyboardHeightPortrait.set(h.coerceIn(0.1f, 0.9f))
                true
            }.getOrDefault(false))
        }
        p["f_keyboard_height_landscape"]?.let { v ->
            set(runCatching {
                val h = v.toFloat()
                require(h.isFinite())
                prefs.keyboardHeightLandscape.set(h.coerceIn(0.1f, 0.9f))
                true
            }.getOrDefault(false))
        }
        p["b_show_custom_row_portrait"]?.let { set(runCatching { prefs.keyboardShowCustomRowPortrait.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_show_custom_row_landscape"]?.let { set(runCatching { prefs.keyboardShowCustomRowLandscape.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["ui_theme_mode"]?.let { v -> set(runCatching { prefs.uiThemeMode.set(ThemeMode.valueOf(v)); true }.getOrDefault(false)) }
        p["ui_dynamic_colors"]?.let { set(runCatching { prefs.uiDynamicColors.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["ui_key_borders"]?.let { set(runCatching { prefs.uiKeyBorders.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        // U10: accent/background allowlist, else default + report.
        p["ui_accent"]?.let { v ->
            if (COLOR_PATTERN.matches(v) && (v == "system" || v.startsWith("#"))) {
                set(runCatching { prefs.uiAccent.set(v); true }.getOrDefault(false))
            } else {
                prefs.uiAccent.set("system")
                applied++
                ignored++
            }
        }
        p["ui_keyboard_background"]?.let { v ->
            if (COLOR_PATTERN.matches(v) && (v == "default" || v.startsWith("#"))) {
                set(runCatching { prefs.uiKeyboardBackground.set(v); true }.getOrDefault(false))
            } else {
                prefs.uiKeyboardBackground.set("default")
                applied++
                ignored++
            }
        }
        p["b_keep_alive_service"]?.let { set(runCatching { prefs.logicKeepAliveService.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_auto_switch_model_by_locale"]?.let { set(runCatching { prefs.logicAutoSwitchModelByLocale.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_remember_locale_per_app"]?.let { set(runCatching { prefs.logicRememberLocalePerApp.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        p["b_file_logging"]?.let { set(runCatching { prefs.logicFileLogging.set(it.toBooleanStrict()); true }.getOrDefault(false)) }
        // F10 posture fail-safe: a crafted backup must never WEAKEN security
        // posture via import. Refusals are named (not a bare count) so a
        // legitimate post-reset restore is distinguishable from an attack.
        // Strengthening or no-op values apply normally; garbage counts skipped.
        val refusedPosture = mutableListOf<String>()
        fun setPostureKey(key: String, weakensWhenTrue: Boolean, current: Boolean, apply: (Boolean) -> Unit) {
            val raw = p[key] ?: return
            val newValue = raw.toBooleanStrictOrNull() ?: run { skipped++; return }
            val weakens = if (weakensWhenTrue) newValue && !current else !newValue && current
            if (weakens) {
                refusedPosture.add(key)
                ignored++
            } else {
                set(runCatching { apply(newValue); true }.getOrDefault(false))
            }
        }
        setPostureKey("b_allow_external_recognition", true, prefs.logicAllowExternalRecognition.get()) {
            prefs.logicAllowExternalRecognition.set(it)
        }
        // Offline ON is protective: refuse turning it OFF via import.
        setPostureKey("b_offline_mode", false, prefs.logicOfflineMode.get()) {
            prefs.logicOfflineMode.set(it)
        }
        setPostureKey("b_allow_voice_in_password", true, prefs.logicAllowVoiceInPassword.get()) {
            prefs.logicAllowVoiceInPassword.set(it)
        }
        setPostureKey("b_restore_model_paths", true, prefs.logicRestoreModelPaths.get()) {
            prefs.logicRestoreModelPaths.set(it)
        }
        // I-16: per-app locale history is never imported (skip key).
        if (p.containsKey("m_locale_per_app")) ignored++
        // U8: download hashes capped at 200 with a strict value pattern.
        val hashesRaw: Map<String, String>? = p["m_download_hashes"]?.let { v ->
            try {
                json.decodeFromString<Map<String, String>>(v)
            } catch (_: Exception) {
                null
            }
        }
        if (hashesRaw != null) {
            val clean = hashesRaw.entries
                .filter { HASH_VALUE_PATTERN.matches(it.value) }
                .take(MAX_HASHES_RESTORE)
                .associate { it.key.take(512) to it.value }
            ignored += hashesRaw.size - clean.size
            set(runCatching { prefs.downloadHashes.set(clean); true }.getOrDefault(false))
        }
        // Per-model RAM pins: small settings map (not usage history),
        // capped like hashes above. F4: confine + relativize when the
        // models dir is known (absolute keys are legacy); without a dir
        // there is no confinement context, so keep verbatim — pins cannot
        // delete or execute anything, worst case they orphan (dual-form
        // lookup treats misses as pinned, the safe default).
        p["m_unpinned_models"]?.let { v ->
            try {
                val raw = json.decodeFromString<Map<String, String>>(v)
                val clean = mutableMapOf<String, String>()
                for (k in raw.keys.take(64).filter { it.length <= 256 }) {
                    if (modelsDir != null) {
                        try {
                            val f = File(k)
                            Sanitize.requireWithin(
                                modelsDir,
                                if (f.isAbsolute) f else File(modelsDir, k)
                            )
                            clean[InstalledModelReference.relativeKey(modelsDir, k)] = "1"
                        } catch (_: Exception) {
                            ignored++
                        }
                    } else {
                        clean[k] = "1"
                    }
                }
                ignored += raw.size - clean.size
                set(runCatching { prefs.unpinnedModels.set(clean); true }.getOrDefault(false))
            } catch (_: Exception) {
                ignored++
            }
        }
        // U8: installed-model paths are private; skip unless opted in.
        val includePaths = includeModelPaths ?: runCatching { prefs.logicRestoreModelPaths.get() }.getOrDefault(false)
        val orderRefs = parseModelsOrder(root)
        if (orderRefs != null) {
            if (!includePaths) {
                ignored += orderRefs.first + orderRefs.second.size
            } else {
                ignored += orderRefs.first
                // D17/U6 fail-closed: without a modelsDir there is no
                // confinement context, so reject every model entry.
                val confined: List<InstalledModelReference> = if (modelsDir == null) {
                    ignored += orderRefs.second.size
                    emptyList()
                } else {
                    orderRefs.second.mapNotNull { ref ->
                        try {
                            val abs = ref.resolveAbsolute(modelsDir)
                            Sanitize.requireWithin(modelsDir, abs)
                            ref.toRelative(modelsDir)
                        } catch (_: Exception) {
                            ignored++
                            null
                        }
                    }
                }
                if (confined.isNotEmpty() || prefs.modelsOrder.get().isEmpty()) {
                    set(runCatching { prefs.modelsOrder.set(confined); true }.getOrDefault(false))
                }
            }
        }
        // U9/I-18: custom keys capped + sanitized at commit and on restore.
        val keys = parseCustomKeys(root)
        if (keys != null) {
            ignored += keys.first
            val clean = keys.second
            if (clean.isNotEmpty() || prefs.keyboardKeysCustom.get().isEmpty()) {
                set(runCatching { prefs.keyboardKeysCustom.set(clean); true }.getOrDefault(false))
            }
        }
        val report = buildString {
            append("Restored $applied items, skipped $skipped")
            if (ignored > 0) append(", ignored $ignored")
            // F10: name refused posture keys so the toast distinguishes a
            // preserved security posture from a generic skip.
            if (refusedPosture.isNotEmpty()) append(", posture kept: ${refusedPosture.joinToString(",")}")
        }
        AppLog.d(TAG, report)
        if (refusedPosture.isNotEmpty()) AppLog.d(TAG, "posture flags refused: ${refusedPosture.joinToString(",")}")
        return report
    }

    /**
     * Lenient models-order parse (D17/U6/U9): unknown type names map via
     * [ModelType.safeValueOf] (never throw), entries must be
     * [InstalledModelReference.isPlausible] (64/256/128/64 caps),aliases are
     * bidi-stripped. Returns violations + clean list, or null when absent.
     */
    private fun parseModelsOrder(root: kotlinx.serialization.json.JsonObject): Pair<Int, List<InstalledModelReference>>? {
        val arr = root["modelsOrder"]?.jsonArray ?: return null
        var violations = 0
        val out = mutableListOf<InstalledModelReference>()
        for ((index, el) in arr.withIndex()) {
            if (index >= MAX_MODELS_RESTORE) {
                violations++
                continue
            }
            try {
                val obj = el.jsonObject
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: run { violations++; continue }
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: run { violations++; continue }
                val typeName = obj["type"]?.jsonPrimitive?.contentOrNull
                val aliasRaw = obj["alias"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val type = ModelType.safeValueOf(typeName)
                val alias = BIDI.replace(aliasRaw, "").filter { c -> c.code >= 0x20 && c != '\u007F' }
                    .take(InstalledModelReference.MAX_ALIAS_CHARS)
                val ref = InstalledModelReference(path, name, type, alias)
                if (!InstalledModelReference.isPlausible(ref)) {
                    violations++
                    continue
                }
                out.add(ref)
            } catch (_: Exception) {
                violations++
            }
        }
        return violations to out
    }

    /**
     * Lenient custom-keys parse (U9/I-18): count + per-string caps with a
     * [sanitized] pass; blank label+text keys are dropped as violators.
     */
    private fun parseCustomKeys(root: kotlinx.serialization.json.JsonObject): Pair<Int, List<Key>>? {
        val arr = root["customKeys"]?.jsonArray ?: return null
        var violations = 0
        val out = mutableListOf<Key>()
        for ((index, el) in arr.withIndex()) {
            if (index >= MAX_KEYS_RESTORE) {
                violations++
                continue
            }
            try {
                val key = lenient.decodeFromJsonElement(Key.serializer(), el).sanitized()
                if (key.label.isBlank() && key.text.isBlank()) {
                    violations++
                    continue
                }
                out.add(key)
            } catch (_: Exception) {
                violations++
            }
        }
        return violations to out.take(MaxCustomKeysPerRow)
    }
}
