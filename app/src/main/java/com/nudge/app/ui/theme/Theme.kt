package com.nudge.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    secondary = Sage80,
    tertiary = Amber80,
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    error = ChartRed
)

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = SurfaceLight,
    primaryContainer = Teal80,
    secondary = Sage40,
    tertiary = Amber40,
    background = SurfaceLight,
    surface = SurfaceLight,
    surfaceContainer = SurfaceContainerLight,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    error = ChartRed
)

@Composable
fun NudgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
