package com.young.waterremote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = WaterBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E8FF),
    onPrimaryContainer = DeepNavy,
    secondary = Color(0xFF4D6075),
    secondaryContainer = Color(0xFFD5E4F7),
    background = IceSurface,
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF5),
    error = HotRed
)

private val DarkColors = darkColorScheme(
    primary = WaterBlueDark,
    primaryContainer = Color(0xFF004A78),
    secondary = Color(0xFFB5C8DE),
    background = Color(0xFF0E141B),
    surface = Color(0xFF151C24),
    surfaceVariant = Color(0xFF27313C),
    error = Color(0xFFFFB4AB)
)

@Composable
fun WaterRemoteTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
