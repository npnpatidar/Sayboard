package com.elishaazaria.sayboard

import com.elishaazaria.sayboard.data.KeepScreenAwakeMode
import com.elishaazaria.sayboard.data.ThemeMode
import com.elishaazaria.sayboard.utils.CorruptPrefRepair
import com.elishaazaria.sayboard.utils.KeysListSerializer
import com.elishaazaria.sayboard.utils.ModelListSerializer
import com.elishaazaria.sayboard.utils.StringMapSerializer
import com.elishaazaria.sayboard.utils.defaultCustomKeysList
import dev.patrickgold.jetpref.datastore.JetPref
import dev.patrickgold.jetpref.datastore.model.PreferenceModel

// Defining a getter function for easy retrieval of the AppPrefs model.
// You can name this however you want, the convention is <projectName>PreferenceModel
fun sayboardPreferenceModel() = JetPref.getOrCreatePreferenceModel(AppPrefs::class, ::AppPrefs)

// Defining a preference model for our app prefs
// The name we give here is the file name of the preferences and is saved
// within the app's `jetpref_datastore` directory.
// U20 DEFERRED (migration risk): the "example-app-preferences" store name is
// a remnant and should be renamed on the next datastore migration with a
// copy-over; no credentials are stored here, so plaintext MODE_PRIVATE stays.
class AppPrefs : PreferenceModel("example-app-preferences") {
    val modelsOrder = custom(
        key = "sl_models_order",
        default = listOf(),
        serializer = ModelListSerializer()
    )

    /**
     * Download URL -> "bytes:sha256" of the last good download. Used to
     * resume interrupted downloads and to reject corrupt/changed files.
     */
    val downloadHashes = custom(
        key = "m_download_hashes",
        default = emptyMap<String, String>(),
        serializer = StringMapSerializer()
    )

    val logicKeepScreenAwake = enum(
        key = "e_keep_screen_awake",
        default = KeepScreenAwakeMode.NEVER
    )

    val logicListenImmediately = boolean(
        key = "b_listen_immediately",
        default = false
    )

    val logicAutoSwitchBack = boolean(
        key = "b_auto_switch_back_ime",
        default = false
    )

    val logicKeepModelInRam = boolean(
        key = "b_keep_model_in_ram",
        default = true
    )

    /**
     * Per-model RAM pins: paths explicitly UNPINNED by the user (value
     * ignored, presence matters). Absent = pinned (stays resident once
     * loaded). Default empty = every model stays loaded; unpin per model
     * on low-RAM devices to restore free-on-switch for that model only.
     */
    val unpinnedModels = custom(
        key = "m_unpinned_models",
        default = emptyMap<String, String>(),
        serializer = StringMapSerializer()
    )

    val logicAutoCapitalize = boolean(
        key = "b_auto_capitalize",
        default = true
    )

    /**
     * End Whisper/Parakeet takes automatically after ~1s of trailing
     * silence (bundled Silero VAD). Off = stop with a second mic tap.
     */
    val logicVadAutoStop = boolean(
        key = "b_vad_auto_stop",
        default = false
    )

    /** Switch to the model matching the text field's locale, when known. */
    val logicAutoSwitchModelByLocale = boolean(
        key = "b_auto_switch_model_by_locale",
        default = true
    )

    /**
     * Remember the last used locale per app package and re-apply it when
     * Sayboard opens there. Covers apps that do not report a field locale
     * (most do not), e.g. switching from another keyboard for voice input.
     * Opt-in (I-16): package->locale history is sensitive, so default off.
     */
    val logicRememberLocalePerApp = boolean(
        key = "b_remember_locale_per_app",
        default = false
    )

    /** Package name -> BCP-47 language tag of the last used locale. */
    val localePerApp = custom(
        key = "m_locale_per_app",
        default = emptyMap<String, String>(),
        serializer = StringMapSerializer()
    )

    val logicDefaultIME = string(
        key = "s_default_keyboard",
        default = "",
    )

    val logicReturnToDefaultIME = boolean(
        key = "b_always_return_default_keyboard",
        default = false
    )

    val keyboardHeightPortrait = float(
        key = "f_keyboard_height_portrait",
        default = 0.3f
    )

    val keyboardHeightLandscape = float(
        key = "f_keyboard_height_landscape",
        default = 0.45f
    )

    val keyboardKeysCustom = custom(
        key = "sl_keyboard_keys_custom",
        default = defaultCustomKeysList,
        serializer = KeysListSerializer()
    )

    val keyboardShowCustomRowPortrait = boolean(
        key = "b_show_custom_row_portrait",
        default = true
    )

    val keyboardShowCustomRowLandscape = boolean(
        key = "b_show_custom_row_landscape",
        default = true
    )

    val uiThemeMode = enum(
        key = "e_ui_theme_mode",
        default = ThemeMode.SYSTEM
    )

    val uiDynamicColors = boolean(
        key = "b_ui_dynamic_colors",
        default = false
    )

    /** Outlined key faces, Gboard/HeliBoard-style. Off = flat keys. */
    val uiKeyBorders = boolean(
        key = "b_ui_key_borders",
        default = false
    )

    /**
     * Mic pill / enter-key accent: "system" follows the theme, otherwise a
     * #RRGGBB hex color.
     */
    val uiAccent = string(
        key = "s_ui_accent",
        default = "system"
    )

    /**
     * Keyboard panel background: "default" follows the theme, otherwise a
     * #RRGGBB hex color applied in both light and dark mode.
     */
    val uiKeyboardBackground = string(
        key = "s_ui_keyboard_background",
        default = "default"
    )

    /**
     * Opt-in keep-alive: a low-priority foreground service (permanent
     * notification) keeps the process — and the loaded model — alive across
     * keyboard switches. Off by default.
     */
    val logicKeepAliveService = boolean(
        key = "b_keep_alive_service",
        default = false
    )

    /**
     * Kill-switch for the exported [SayboardRecognitionService] (B4/I-05).
     * Default off: external apps get ERROR_INSUFFICIENT_PERMISSIONS without
     * the mic or models being touched.
     */
    val logicAllowExternalRecognition = boolean(
        key = "b_allow_external_recognition",
        default = false
    )

    /** Opt-out for the private on-device log file (U1). Default on. */
    val logicFileLogging = boolean(
        key = "b_file_logging",
        default = true
    )

    /** Offline mode (D16): block downloads and update checks. Default off. */
    val logicOfflineMode = boolean(
        key = "b_offline_mode",
        default = false
    )

    /**
     * Allow voice dictation in password fields (I-09). Default off: voice
     * results are refused there with an on-keyboard notice. Password
     * detection reads the live EditorInfo (cached info can go stale across
     * input restarts and false-positive); enable only if dictation is
     * wanted everywhere.
     */
    val logicAllowVoiceInPassword = boolean(
        key = "b_allow_voice_in_password",
        default = false
    )

    /**
     * Restore the installed-model list (private install paths) from a
     * backup (U8). Default off: paths stay out of restores unless opted in.
     */
    val logicRestoreModelPaths = boolean(
        key = "b_restore_model_paths",
        default = false
    )

    /**
     * U11/U12 persistent repair marker: set when a corrupt list/map pref
     * (custom keys, models order, string maps) is repaired. Default false;
     * surfaced so a repair is visible beyond logcat.
     */
    val prefsRepaired = boolean(
        key = "b_prefs_repaired",
        default = false
    )

    init {
        // Bridge serializer repairs (utils, no AppPrefs reference) into the
        // persistent flag without an init cycle.
        CorruptPrefRepair.persistHook = { runCatching { prefsRepaired.set(true) } }
        if (CorruptPrefRepair.pendingRepair) {
            runCatching { prefsRepaired.set(true) }
        }
    }
}