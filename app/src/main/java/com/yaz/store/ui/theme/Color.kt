package com.yaz.store.ui.theme

// Ported from Files (YAZ/Files at HEAD, ui/theme/Color.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, ui/theme/Color.kt), package changed.
// The palette stays Files' until the user's icon gives the colour.
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Fallback palette for devices where dynamic colour is unavailable or disabled.
// The same teal as the other apps, so they read as one family.
private val Teal = Color(0xFF4FD1B0)
private val TealDark = Color(0xFF00382E)
private val Slate = Color(0xFF101418)
private val SlateLight = Color(0xFFF7FAF9)

internal val AppDarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = TealDark,
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF71EECB),
    secondary = Color(0xFFB1CCC4),
    background = Slate,
    onBackground = Color(0xFFE1E3E1),
    surface = Slate,
    onSurface = Color(0xFFE1E3E1),
    surfaceContainer = Color(0xFF1B1F23),
    surfaceContainerHigh = Color(0xFF252A2E),
    error = Color(0xFFFFB4AB)
)

internal val AppLightColors = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF71EECB),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A635B),
    background = SlateLight,
    onBackground = Color(0xFF191C1B),
    surface = SlateLight,
    onSurface = Color(0xFF191C1B),
    surfaceContainer = Color(0xFFEDF0EE),
    surfaceContainerHigh = Color(0xFFE7EAE8),
    error = Color(0xFFBA1A1A)
)
