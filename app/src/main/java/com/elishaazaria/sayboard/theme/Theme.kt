package com.elishaazaria.sayboard.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.ThemeMode
import com.elishaazaria.sayboard.sayboardPreferenceModel
import dev.patrickgold.jetpref.datastore.model.observeAsState

/**
 * Settings-screen theme: soft surface-container background,
 * blue primary (or the system dynamic accent when enabled), full day/night
 * palettes following the selected theme mode (system/light/dark).
 */
@Composable
fun AppTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val prefs by sayboardPreferenceModel()
    // Observed (not .get()) so theme changes apply live, no restart needed
    val themeMode by prefs.uiThemeMode.observeAsState()
    val dynamicColors by prefs.uiDynamicColors.observeAsState()
    val dark = darkTheme ?: when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val background = Color(if (dark) 0xFF1D2024 else 0xFFEDEDF4)
    val useDynamic = dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Separate vals per scheme: each theme resolves its own background color
    // (dynamic resource or fallback), so no background value is ever paired
    // with two different on-colors.
    val colors = if (dark) {
        val primaryDark =
            if (useDynamic) colorResource(id = R.color.materialYouForegroundDark)
            else Color(0xFFA9C7FF)
        darkColors(
            primary = primaryDark,
            onPrimary = Color(0xFF08305F),
            secondary = primaryDark,
            background = background,
            onBackground = Color(0xFFE2E2E9),
            surface = Color(0xFF43474E),
            onSurface = Color(0xFFD9E3F9),
            error = Color(0xFFFFB4AB),
        )
    } else {
        val primaryLight =
            if (useDynamic) colorResource(id = R.color.materialYouForegroundLight)
            else Color(0xFF405F90)
        lightColors(
            primary = primaryLight,
            onPrimary = Color.White,
            secondary = primaryLight,
            background = background,
            onBackground = Color(0xFF191C20),
            surface = Color(0xFFE0E2EC),
            onSurface = Color(0xFF3E4758),
            error = Color(0xFFBA1A1A),
        )
    }

    MaterialTheme(
        colors = colors,
        shapes = Shapes,
        content = content,
    )
}
