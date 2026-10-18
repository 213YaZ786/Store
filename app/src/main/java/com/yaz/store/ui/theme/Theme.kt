package com.yaz.store.ui.theme

// Ported from Files (YAZ/Files at HEAD, ui/theme/Theme.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, ui/theme/Theme.kt), package changed.
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/** Material 3 with the wallpaper's colours, the same way as the other apps. */
// Files' AppTheme.
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    textScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        // minSdk is 31, so dynamic colour is always available. The flag exists
        // for the fallback palette, should it ever be offered as a choice.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> AppDarkColors
        else -> AppLightColors
    }.let { scheme ->
        if (darkTheme && pureBlack) {
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black
            )
        } else {
            scheme
        }
    }

    val typography = remember(textScale) { AppTypography.scaled(textScale) }

    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Text size steps offered in Settings. Applied on top of Android's own font size. */
val TEXT_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)

// Files' textScaleLabel.
fun textScaleLabel(scale: Float): String = when (scale) {
    0.9f -> "Small"
    1f -> "Default"
    1.15f -> "Large"
    1.3f -> "Largest"
    else -> "${(scale * 100).toInt()}%"
}

/**
 * Every style scaled by [factor], line height included so lines keep their
 * rhythm. Scaling the typography rather than the density leaves icons,
 * padding and touch targets alone, only words grow.
 */
// Files' Typography.scaled.
fun Typography.scaled(factor: Float): Typography {
    if (factor == 1f) return this
    fun TextStyle.s() = copy(
        fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
    )
    return copy(
        displayLarge = displayLarge.s(),
        displayMedium = displayMedium.s(),
        displaySmall = displaySmall.s(),
        headlineLarge = headlineLarge.s(),
        headlineMedium = headlineMedium.s(),
        headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(),
        titleMedium = titleMedium.s(),
        titleSmall = titleSmall.s(),
        bodyLarge = bodyLarge.s(),
        bodyMedium = bodyMedium.s(),
        bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s(),
        labelMedium = labelMedium.s(),
        labelSmall = labelSmall.s()
    )
}
