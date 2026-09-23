package com.elishaazaria.sayboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.ThemeMode
import com.elishaazaria.sayboard.ime.contrastingOn
import com.elishaazaria.sayboard.ime.parseHexColor
import com.elishaazaria.sayboard.sayboardPreferenceModel
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.datastore.ui.ScrollablePreferenceLayout
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference

@Composable
fun UISettingsUi() = ScrollablePreferenceLayout(sayboardPreferenceModel()) {
    val mode by prefs.uiThemeMode.observeAsState()
    val themePref = prefs.uiThemeMode

    Text(
        text = stringResource(id = R.string.ui_theme_mode_title),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        ThemeModeButton(
            selected = mode == ThemeMode.SYSTEM,
            label = stringResource(id = R.string.ui_theme_mode_system),
            onClick = { themePref.set(ThemeMode.SYSTEM) },
            modifier = Modifier.weight(1f)
        )
        ThemeModeButton(
            selected = mode == ThemeMode.LIGHT,
            label = stringResource(id = R.string.ui_theme_mode_light),
            onClick = { themePref.set(ThemeMode.LIGHT) },
            modifier = Modifier.weight(1f)
        )
        ThemeModeButton(
            selected = mode == ThemeMode.DARK,
            label = stringResource(id = R.string.ui_theme_mode_dark),
            onClick = { themePref.set(ThemeMode.DARK) },
            modifier = Modifier.weight(1f)
        )
    }

    SwitchPreference(
        pref = prefs.uiDynamicColors,
        title = stringResource(id = R.string.ui_dynamic_colors_title),
        summary = stringResource(id = R.string.ui_dynamic_colors_summary)
    )

    SwitchPreference(
        pref = prefs.uiKeyBorders,
        title = stringResource(id = R.string.ui_key_borders_title),
        summary = stringResource(id = R.string.ui_key_borders_summary)
    )

    val accent by prefs.uiAccent.observeAsState()
    ColorChoiceRow(
        title = stringResource(id = R.string.ui_accent_title),
        summary = stringResource(id = R.string.ui_accent_summary),
        current = accent,
        defaultKey = "system",
        presets = listOf("274690", "3A6B4F", "6B4FA1", "9C5D1E", "BA1A1A"),
        onPick = { prefs.uiAccent.set(it) }
    )

    val keyboardBackground by prefs.uiKeyboardBackground.observeAsState()
    ColorChoiceRow(
        title = stringResource(id = R.string.ui_keyboard_background_title),
        summary = stringResource(id = R.string.ui_keyboard_background_summary),
        current = keyboardBackground,
        defaultKey = "default",
        presets = listOf("000000", "1D2024", "EDEDF4", "FFFFFF"),
        onPick = { prefs.uiKeyboardBackground.set(it) }
    )
}

@Composable
private fun ThemeModeButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text = label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text = label)
        }
    }
}

/**
 * A preset swatch row (default entry + [presets]) with an optional custom
 * hex field, HeliBoard-style. [current]/[defaultKey]/[onPick] use the raw
 * pref values ("system"/"default" or #RRGGBB).
 */
@Composable
private fun ColorChoiceRow(
    title: String,
    summary: String,
    current: String,
    defaultKey: String,
    presets: List<String>,
    onPick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = title)
        Text(
            text = summary,
            style = MaterialTheme.typography.caption
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Swatch(
                selected = current == defaultKey,
                onClick = { onPick(defaultKey) },
                contentDescription = defaultKey,
                fill = null
            )
            presets.forEach { hex ->
                Swatch(
                    selected = current.equals("#$hex", ignoreCase = true),
                    onClick = { onPick("#$hex") },
                    contentDescription = hex,
                    fill = parseHexColor(hex)
                )
            }
        }
        var custom by remember(current) {
            mutableStateOf(
                if (current != defaultKey) current.removePrefix("#") else ""
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.filter { c ->
                    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
                }.take(6) },
                label = { Text(text = "#RRGGBB") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            val parsed = parseHexColor(custom)
            Button(
                onClick = { parsed?.let { onPick("#${custom.uppercase()}") } },
                enabled = parsed != null
            ) {
                Text(text = stringResource(id = R.string.ui_custom_color_apply))
            }
        }
    }
}

@Composable
private fun Swatch(
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    fill: Color?
) {
    val ring = if (selected) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(2.dp, ring, CircleShape)
            .clickable(onClick = onClick)
    ) {
        if (fill != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(fill)
            )
        } else {
            Text(
                text = "A",
                color = contrastingOn(MaterialTheme.colors.background)
            )
        }
    }
}
