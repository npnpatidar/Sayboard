package com.elishaazaria.sayboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elishaazaria.sayboard.AppPrefs
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.utils.Key
import com.elishaazaria.sayboard.utils.MaxCustomKeysPerRow
import com.elishaazaria.sayboard.utils.sanitized
import kotlin.math.roundToInt
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import dev.patrickgold.jetpref.datastore.ui.ScrollablePreferenceLayout
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun KeyboardSettingsUi() = ScrollablePreferenceLayout(sayboardPreferenceModel()) {
    PreferenceGroup(title = stringResource(id = R.string.keyboard_height_header)) {
        SteppedHeightPreference(
            prefs.keyboardHeightPortrait,
            stringResource(id = R.string.keyboard_height_portrait_title),
            min = 0.25f,
            default = 0.3f
        )
        SteppedHeightPreference(
            prefs.keyboardHeightLandscape,
            stringResource(id = R.string.keyboard_height_landscape_title),
            min = 0.25f,
            default = 0.45f
        )
    }

    PreferenceGroup(title = stringResource(id = R.string.keyboard_keys_header)) {

        KeysPreference(
            prefs.keyboardKeysCustom,
            stringResource(id = R.string.keyboard_keys_custom_title)
        )

        SwitchPreference(
            pref = prefs.keyboardShowCustomRowPortrait,
            title = stringResource(id = R.string.keyboard_show_custom_row_portrait_title),
            summary = stringResource(id = R.string.keyboard_show_custom_row_portrait_summary)
        )

        SwitchPreference(
            pref = prefs.keyboardShowCustomRowLandscape,
            title = stringResource(id = R.string.keyboard_show_custom_row_landscape_title),
            summary = stringResource(id = R.string.keyboard_show_custom_row_landscape_summary)
        )
    }
}


/**
 * Height slider with true 0.05 steps. JetPref's DialogSliderPreference maps
 * stepIncrement to M2 Slider steps via ((max-min)/step).toInt()-1, which only
 * lands on exact increments when the range is an even multiple of the step
 * (0.25-1.0 and 0.01-1.0 are not), so the thumb snapped to 0.107/0.11
 * instead of tenths. M2 Slider always divides evenly, so snapping is done
 * manually here instead.
 */
@Composable
private fun PreferenceUiScope<AppPrefs>.SteppedHeightPreference(
    heightPref: PreferenceData<Float>, title: String, min: Float, default: Float
) {
    val step = 0.05f
    fun snap(value: Float): Float =
        ((value / step).roundToInt() * step).coerceIn(min, 1f)

    val current by heightPref.observeAsState(heightPref.default)

    var dialog by remember {
        mutableStateOf(false)
    }
    var sliderValue by remember {
        mutableStateOf(current)
    }

    Preference(
        title = title,
        summary = "${(snap(current) * 100).roundToInt()}%"
    ) {
        sliderValue = snap(current)
        dialog = true
    }

    if (dialog) {
        JetPrefAlertDialog(
            title = title,
            onDismiss = { dialog = false },
            onConfirm = {
                heightPref.set(snap(sliderValue))
                dialog = false
            },
            onNeutral = {
                heightPref.set(default)
                dialog = false
            },
            confirmLabel = stringResource(id = R.string.button_confirm),
            dismissLabel = stringResource(id = R.string.button_cancel),
            neutralLabel = stringResource(id = R.string.button_default)
        ) {
            Column {
                Text(
                    text = "${(snap(sliderValue) * 100).roundToInt()}%",
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = snap(it) },
                    valueRange = min..1f,
                    steps = 0
                )
            }
        }
    }
}

@Composable
private fun PreferenceUiScope<AppPrefs>.KeysPreference(
    keysPref: PreferenceData<List<Key>>, title: String
) {
    val keys = keysPref.observeAsState(keysPref.default)

    var keysDialog by remember {
        mutableStateOf(false)
    }

    var keysData by remember {
        mutableStateOf(keys.value)
    }

    // Clamp once per load: an oversized row (e.g. from an edited backup)
    // renders "6 of 5" and breaks the add-button contract.
    keysData = keys.value.take(MaxCustomKeysPerRow)

    val summary = keys.value.map { p -> p.label }.joinToString(" ")

    Preference(title = title, summary = summary) {
        keysDialog = true
    }

    if (keysDialog) {
        JetPrefAlertDialog(
            title = title,
            onDismiss = { keysDialog = false },
            onConfirm = {
                // I-18: sanitize at commit (32-char cap, strip bidi/controls).
                keysPref.set(keysData.map { it.sanitized() }
                    .filter { it.label.isNotBlank() || it.text.isNotBlank() }
                    .take(MaxCustomKeysPerRow))
                keysDialog = false
            },
            onNeutral = {
                keysPref.reset()
                keysDialog = false
            },
            confirmLabel = stringResource(id = R.string.button_confirm),
            dismissLabel = stringResource(id = R.string.button_cancel),
            neutralLabel = stringResource(id = R.string.button_default)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                fun moveKey(fromIndex: Int, toIndex: Int) {
                    if (fromIndex < 0 || toIndex < 0 || fromIndex >= keysData.size || toIndex >= keysData.size) return
                    keysData = keysData.toMutableList().apply {
                        add(toIndex, removeAt(fromIndex))
                    }
                }
                Text(
                    text = stringResource(
                        id = R.string.keyboard_keys_dialog_keys_title,
                        keysData.size, MaxCustomKeysPerRow
                    ),
                    style = MaterialTheme.typography.subtitle2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                if (keysData.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.keyboard_keys_dialog_empty),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(0.dp, (LocalConfiguration.current.screenHeightDp * 0.5).dp)
                ) {
                    itemsIndexed(
                        keysData,
                        key = { index, item ->
                            "$index:${item.label}\n${item.text}\n${item.longLabel}\n${item.longText}"
                        }
                    ) { index, item ->
                        Surface(
                            color = MaterialTheme.colors.onSurface.copy(0.08f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                )
                            ) {
                                // Miniature of the key as it appears on the keyboard
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(width = 52.dp, height = 44.dp)
                                        .background(
                                            MaterialTheme.colors.surface,
                                            RoundedCornerShape(10.dp)
                                        )
                                ) {
                                    Text(
                                        text = item.label,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.subtitle1
                                    )
                                    if (item.longLabel.isNotBlank()) {
                                        Text(
                                            text = item.longLabel,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colors.onSurface.copy(
                                                alpha = 0.7f
                                            ),
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 2.dp, end = 5.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(
                                            id = R.string.keyboard_keys_dialog_key_entry
                                        ).format(item.label, item.text),
                                        style = MaterialTheme.typography.body2
                                    )
                                    if (item.longLabel.isNotBlank() || item.longText.isNotBlank()) {
                                        Text(
                                            text = "→ " + stringResource(
                                                id = R.string.keyboard_keys_dialog_key_entry
                                            ).format(item.longLabel, item.longText),
                                            style = MaterialTheme.typography.caption,
                                            color = MaterialTheme.colors.onSurface.copy(
                                                alpha = 0.7f
                                            )
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { moveKey(index, index - 1) },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(id = R.string.desc_move_up)
                                    )
                                }
                                IconButton(
                                    onClick = { moveKey(index, index + 1) },
                                    enabled = index < keysData.size - 1
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(id = R.string.desc_move_down)
                                    )
                                }
                                IconButton(onClick = {
                                    keysData =
                                        keysData.toMutableList().apply {
                                            remove(item)
                                        }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(id = R.string.desc_delete),
                                        tint = MaterialTheme.colors.error
                                    )
                                }
                            }
                        }
                    }
                }
                var labelValue by remember {
                    mutableStateOf("")
                }
                var textValue by remember {
                    mutableStateOf("")
                }
                var longLabelValue by remember {
                    mutableStateOf("")
                }
                var longTextValue by remember {
                    mutableStateOf("")
                }
                Text(
                    text = stringResource(id = R.string.keyboard_keys_dialog_add_title),
                    style = MaterialTheme.typography.subtitle2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                Surface(
                    color = MaterialTheme.colors.primary.copy(0.08f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = labelValue,
                                onValueChange = { labelValue = it },
                                label = {
                                    Text(text = stringResource(id = R.string.keyboard_keys_dialog_key_label_label))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { textValue = it },
                                label = {
                                    Text(text = stringResource(id = R.string.keyboard_keys_dialog_key_text_label))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = longLabelValue,
                                onValueChange = { longLabelValue = it },
                                label = {
                                    Text(text = stringResource(id = R.string.keyboard_keys_dialog_key_long_label_label))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = longTextValue,
                                onValueChange = { longTextValue = it },
                                label = {
                                    Text(text = stringResource(id = R.string.keyboard_keys_dialog_key_long_text_label))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                // I-18: sanitize at entry as well.
                                val newKey = Key(labelValue, textValue, longLabelValue, longTextValue).sanitized()
                                if ((newKey.label.isNotBlank() || newKey.text.isNotBlank()) &&
                                    keysData.none { k ->
                                        k.label == newKey.label && k.text == newKey.text
                                    }
                                ) {
                                    keysData = keysData.toMutableList().apply {
                                        add(newKey)
                                    }.take(MaxCustomKeysPerRow)
                                    labelValue = ""
                                    textValue = ""
                                    longLabelValue = ""
                                    longTextValue = ""
                                }
                            },
                            enabled = keysData.size < MaxCustomKeysPerRow,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(id = R.string.desc_add_key)
                                )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(id = R.string.keyboard_keys_dialog_add_button))
                        }
                    }
                }
            }
        }

    }
}