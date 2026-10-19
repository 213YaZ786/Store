package com.yaz.store.feature.settings

// Ported from Files (YAZ/Files at HEAD, feature/settings/SettingsScreen.kt), itself from Calculator
// (https://github.com/213YaZ786/Calculator, feature/settings/SettingsScreen.kt): the sections of glass rows, SettingRow, SwitchRow with a
// GlassSwitch, ChoiceDialog with GlassRadio, the appearance choices (theme, pure black, glass, text size), the About credits.
// Store's own: "Check in the background" starts or cancels the once-a-day job (data/update, GrapheneOS' App Store AutoUpdatePrefs,
// https://github.com/GrapheneOS/AppStore) and asks for the notification permission when it is missing, with Compose's permission launcher
// (https://developer.android.com/training/permissions/requesting); "Only on Wi-Fi" is read by StoreBackend at each download.
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yaz.store.BuildConfig
import com.yaz.store.R
import com.yaz.store.data.settings.Settings
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.data.settings.ThemeMode
import com.yaz.store.data.update.UpdateJobs
import com.yaz.store.navigation.LocalReadableInset
import com.yaz.store.ui.component.FloatingAction
import com.yaz.store.ui.component.FloatingFrame
import com.yaz.store.ui.component.FloatingTop
import com.yaz.store.ui.component.GlassRadio
import com.yaz.store.ui.component.GlassSwitch
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ZoneAlertDialog
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.rememberHaptics
import com.yaz.store.ui.icon.AppIcons
import com.yaz.store.ui.theme.TEXT_SCALES
import com.yaz.store.ui.theme.textScaleLabel
import org.koin.compose.koinInject

private enum class OpenDialog { NONE, THEME, TEXT_SIZE }

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onTheme = { mode -> store.update { it.copy(themeMode = mode) } },
        onPureBlack = { on -> store.update { it.copy(pureBlack = on) } },
        onGlass = { on -> store.update { it.copy(glass = on) } },
        onTextScale = { scale -> store.update { it.copy(textScale = scale) } },
        onBackground = { on ->
            store.update { it.copy(backgroundCheck = on) }
            UpdateJobs.sync(context, on)
            // The check only tells by notification: asked here when it is missing, as the setup guide asks it.
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onWifiOnly = { on -> store.update { it.copy(wifiOnly = on) } }
    )
}

/** Stateless, so a test draws it with fixed data. */
@Composable
fun SettingsContent(
    settings: Settings,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit = {},
    onPureBlack: (Boolean) -> Unit = {},
    onGlass: (Boolean) -> Unit = {},
    onTextScale: (Float) -> Unit = {},
    onBackground: (Boolean) -> Unit = {},
    onWifiOnly: (Boolean) -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf(OpenDialog.NONE) }

    FloatingFrame(
        bottom = 0.dp,
        top = { FloatingTop(stringResource(R.string.settings), leading = { FloatingAction(AppIcons.ArrowBack, stringResource(R.string.back), onBack) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))

            Section(stringResource(R.string.settings_updates)) {
                SwitchRow(
                    stringResource(R.string.settings_background), stringResource(R.string.settings_background_summary),
                    settings.backgroundCheck, onBackground
                )
                SwitchRow(
                    stringResource(R.string.settings_wifi_only), stringResource(R.string.settings_wifi_only_summary),
                    settings.wifiOnly, onWifiOnly
                )
            }

            Section(stringResource(R.string.settings_appearance)) {
                SettingRow(stringResource(R.string.settings_theme), themeLabel(settings.themeMode)) { dialog = OpenDialog.THEME }
                SwitchRow(stringResource(R.string.settings_pure_black), stringResource(R.string.settings_pure_black_summary), settings.pureBlack, onPureBlack)
                SwitchRow(stringResource(R.string.settings_glass), stringResource(R.string.settings_glass_summary), settings.glass, onGlass)
                SettingRow(
                    stringResource(R.string.settings_text_size),
                    stringResource(R.string.settings_text_size_summary, textScaleLabel(settings.textScale))
                ) { dialog = OpenDialog.TEXT_SIZE }
            }

            Section(stringResource(R.string.settings_about)) {
                SettingRow(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), null)
                SettingRow(stringResource(R.string.settings_credit_grapheneos), stringResource(R.string.settings_credit_grapheneos_summary)) {
                    uriHandler.openUri("https://github.com/GrapheneOS/AppStore")
                }
                SettingRow(stringResource(R.string.settings_credit_komi), stringResource(R.string.settings_credit_komi_summary)) {
                    uriHandler.openUri("https://github.com/komi-store/komi-store")
                }
                SettingRow(stringResource(R.string.settings_credit_manager), stringResource(R.string.settings_credit_manager_summary)) {
                    uriHandler.openUri("https://github.com/MuntashirAkon/AppManager")
                }
                SettingRow(stringResource(R.string.settings_source_code), stringResource(R.string.settings_source_code_summary)) {
                    uriHandler.openUri("https://github.com/213YaZ786/Store")
                }
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    when (dialog) {
        OpenDialog.THEME -> ChoiceDialog(stringResource(R.string.settings_theme), ThemeMode.entries.map { it to themeLabel(it) }, settings.themeMode, onTheme) { dialog = OpenDialog.NONE }
        OpenDialog.TEXT_SIZE -> ChoiceDialog(stringResource(R.string.settings_text_size), TEXT_SCALES.map { it to textScaleLabel(it) }, settings.textScale, onTextScale) { dialog = OpenDialog.NONE }
        OpenDialog.NONE -> Unit
    }
}

/** A titled group of rows on one rounded zone. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(title: String, summary: String?, quiet: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val haptics = rememberHaptics()
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable { if (!quiet) haptics.tick(); onClick() } else Modifier
    )
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val change = { on: Boolean -> haptics.toggle(on); onChange(on) }
    SettingRow(title, summary, quiet = true, trailing = { GlassSwitch(checked = checked, onCheckedChange = change) }, onClick = { change(!checked) })
}

@Composable
internal fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(value); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassRadio(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { QuietButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)
