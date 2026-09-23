package com.elishaazaria.sayboard.ui

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.SettingsActivity
import com.elishaazaria.sayboard.data.KeepScreenAwakeMode
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.services.KeepAlive
import dev.patrickgold.jetpref.datastore.model.observeAsState
import com.mikepenz.aboutlibraries.ui.compose.LibrariesContainer
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.ListPreferenceEntry
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.ScrollablePreferenceLayout
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries

@Composable
fun getEnabledInputMethodPreferenceEntries(context: Context): List<ListPreferenceEntry<String>> {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val pm = context.packageManager
    return listPrefEntries {
        entry(key = "", label = stringResource(R.string.option_none))

        for (im in imm.enabledInputMethodList) {
            if (im.packageName == context.packageName) continue
            entry(
                key = im.id,
                label = im.loadLabel(pm).toString()
            )
        }
    }
}

@Composable
fun LogicSettingsUi(context: Context) = ScrollablePreferenceLayout(sayboardPreferenceModel()) {
    ListPreference(
        listPref = prefs.logicKeepScreenAwake,
        title = stringResource(id = R.string.logic_keep_screen_awake_title),
        entries = KeepScreenAwakeMode.listEntries()
    )
    SwitchPreference(
        pref = prefs.logicListenImmediately,
        title = stringResource(id = R.string.logic_listen_immediately_title),
        summary = stringResource(
            id = R.string.logic_listen_immediately_summary
        )
    )
    SwitchPreference(
        pref = prefs.logicAutoSwitchBack,
        title = stringResource(id = R.string.logic_auto_switch_back_title),
        summary = stringResource(
            id = R.string.logic_auto_switch_back_summary
        )
    )
    SwitchPreference(
        pref = prefs.logicKeepModelInRam,
        title = stringResource(id = R.string.logic_keep_model_in_ram_title),
        summary = stringResource(
            id = R.string.logic_keep_model_in_ram_summary
        )
    )
    SwitchPreference(
        pref = prefs.logicKeepAliveService,
        title = stringResource(id = R.string.logic_keep_alive_title),
        summary = stringResource(id = R.string.logic_keep_alive_summary)
    )
    val context = LocalContext.current
    val keepAlive by prefs.logicKeepAliveService.observeAsState()
    LaunchedEffect(keepAlive) {
        KeepAlive.sync(context, keepAlive)
    }
    SwitchPreference(
        pref = prefs.logicAutoCapitalize,
        title = stringResource(id = R.string.logic_auto_capitalize_title),
        summary = stringResource(id = R.string.logic_auto_capitalize_summary)
    )
    SwitchPreference(
        pref = prefs.logicVadAutoStop,
        title = stringResource(id = R.string.logic_vad_auto_stop_title),
        summary = stringResource(id = R.string.logic_vad_auto_stop_summary)
    )
    SwitchPreference(
        pref = prefs.logicAutoSwitchModelByLocale,
        title = stringResource(id = R.string.logic_auto_switch_model_title),
        summary = stringResource(id = R.string.logic_auto_switch_model_summary)
    )
    SwitchPreference(
        pref = prefs.logicRememberLocalePerApp,
        title = stringResource(id = R.string.logic_remember_locale_title),
        summary = stringResource(id = R.string.logic_remember_locale_summary)
    )
    // I-16: clear remembered per-app languages (opt-in history).
    Preference(
        title = stringResource(id = R.string.logic_clear_app_locales_title),
        summary = stringResource(id = R.string.logic_clear_app_locales_summary),
        onClick = {
            prefs.localePerApp.set(emptyMap())
            android.widget.Toast.makeText(
                context, R.string.logic_app_locales_cleared, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    )
    // B4/I-05: default-off kill-switch with disclosure text.
    SwitchPreference(
        pref = prefs.logicAllowExternalRecognition,
        title = stringResource(id = R.string.logic_allow_external_recognition_title),
        summary = stringResource(id = R.string.logic_allow_external_recognition_summary)
    )
    // U1: opt-out of the private on-device log file.
    SwitchPreference(
        pref = prefs.logicFileLogging,
        title = stringResource(id = R.string.logic_file_logging_title),
        summary = stringResource(id = R.string.logic_file_logging_summary)
    )
    val fileLogging by prefs.logicFileLogging.observeAsState()
    LaunchedEffect(fileLogging) {
        com.elishaazaria.sayboard.utils.AppLog.fileLoggingEnabled = fileLogging
    }
    // D16: offline mode blocks downloads and update checks.
    SwitchPreference(
        pref = prefs.logicOfflineMode,
        title = stringResource(id = R.string.logic_offline_mode_title),
        summary = stringResource(id = R.string.logic_offline_mode_summary)
    )
    // I-09: opt-in voice dictation in password fields (default blocked).
    SwitchPreference(
        pref = prefs.logicAllowVoiceInPassword,
        title = stringResource(id = R.string.logic_allow_voice_in_password_title),
        summary = stringResource(id = R.string.logic_allow_voice_in_password_summary)
    )
    // U8: include private install paths when restoring a backup (default off).
    SwitchPreference(
        pref = prefs.logicRestoreModelPaths,
        title = stringResource(id = R.string.logic_restore_paths_title),
        summary = stringResource(id = R.string.logic_restore_paths_summary)
    )

    ListPreference(
        listPref = prefs.logicDefaultIME,
        title = stringResource(id = R.string.logic_default_ime),
        entries = getEnabledInputMethodPreferenceEntries(context)
    )
    SwitchPreference(
        pref = prefs.logicReturnToDefaultIME,
        title = stringResource(id = R.string.logic_return_to_default_ime_title),
        summary = stringResource(id = R.string.logic_return_to_default_ime_summary)
    )
    Preference(
        title = stringResource(id = R.string.logic_backup_title),
        summary = stringResource(id = R.string.logic_backup_summary),
        onClick = { (context as? SettingsActivity)?.exportBackup() }
    )
    Preference(
        title = stringResource(id = R.string.logic_restore_title),
        summary = stringResource(id = R.string.logic_restore_summary),
        onClick = { (context as? SettingsActivity)?.importBackup() }
    )

    var showLibrariesPopup by remember { mutableStateOf(false) }
    Preference(
        title = stringResource(id = R.string.show_libraries),
        onClick = { showLibrariesPopup = true })
    if (showLibrariesPopup) {
        Dialog(onDismissRequest = {
            showLibrariesPopup = false
        }) {
            LibrariesContainer(
                Modifier.fillMaxSize()
            )
        }
    }
}