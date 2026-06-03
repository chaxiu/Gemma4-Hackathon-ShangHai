package com.winter.durianai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color
import com.winter.durianai.ui.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreenDark,
    onPrimary = Color.Black,
    primaryContainer = PrimaryGreenDarkContainer,
    onPrimaryContainer = PrimaryGreenDark,
    secondary = SecondaryGreen,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF334B22),
    onSecondaryContainer = SecondaryGreenLight,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = Color(0xFF42473E),
    onSurfaceVariant = TextSecondaryDark,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A9285),
    outlineVariant = Color(0xFF42473E),
    surfaceTint = PrimaryGreenDark,
    surfaceContainerLowest = Color(0xFF141612),
    surfaceContainerLow = Color(0xFF1A1C18),
    surfaceContainer = Color(0xFF1E201C),
    surfaceContainerHigh = Color(0xFF242621),
    surfaceContainerHighest = Color(0xFF2A2C26),
    inverseSurface = Color(0xFFE2E3DD),
    inverseOnSurface = Color(0xFF1A1C18),
    inversePrimary = PrimaryGreen
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = Color.White,
    primaryContainer = PrimaryGreenLight,
    onPrimaryContainer = PrimaryGreen,
    secondary = SecondaryGreen,
    onSecondary = Color.White,
    secondaryContainer = SecondaryGreenLight,
    onSecondaryContainer = PrimaryGreen,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFDFE4D8),
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFB9C2AE),
    outlineVariant = Color(0xFFCDD5C3),
    surfaceTint = PrimaryGreen,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBF8),
    surfaceContainer = Color(0xFFF7F9F5),
    surfaceContainerHigh = Color(0xFFF4F7F1),
    surfaceContainerHighest = Color(0xFFF0F5EA),
    inverseSurface = Color(0xFF30332C),
    inverseOnSurface = Color(0xFFF1F3EC),
    inversePrimary = PrimaryGreenDark
)

@Composable
fun DurianaiTheme(
    themeMode: ThemeMode = ThemeMode.Auto,
    // Disable dynamic color to enforce our brand colors
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Auto -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

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
