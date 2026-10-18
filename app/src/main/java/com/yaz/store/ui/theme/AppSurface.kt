package com.yaz.store.ui.theme

// Ported from Files (YAZ/Files at HEAD, ui/theme/AppSurface.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, ui/theme/AppSurface.kt), package changed.
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.data.settings.ThemeMode
import com.yaz.store.ui.glass.LocalGlass
import com.yaz.store.ui.glass.glassGround
import com.yaz.store.ui.glass.rememberGlassLook
import org.koin.compose.koinInject

/**
 * The frame every window of the app draws in: the user's theme, glass over
 * Material You, and the page's ground with its ambient light under
 * everything.
 */
// Files' AppSurface.
@Composable
fun ComponentActivity.AppSurface(content: @Composable () -> Unit) {
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Status and navigation bar icons follow the app's theme, not only the
    // system's, so a forced light theme keeps dark icons.
    DisposableEffect(dark) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        )
        onDispose { }
    }

    AppTheme(darkTheme = dark, pureBlack = settings.pureBlack, textScale = settings.textScale) {
        val look = rememberGlassLook(MaterialTheme.colorScheme, settings.glass)
        // Text and icons take the theme's colour in every window: without it
        // Compose draws them black, unreadable on a dark ground.
        CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().glassGround(look, MaterialTheme.colorScheme.background)) {
                content()
            }
        }
    }
}
