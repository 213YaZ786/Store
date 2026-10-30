package com.yaz.store.feature.settings

// Settings as tiles, ported from Contacts' SettingsScreen.kt (our own app, /home/yghouar/Claude Code/APK/YAZ/Contacts at
// app/src/main/java/com/yaz/contacts/feature/settings/SettingsScreen.kt: SettingsSection, SettingsSwitchTile, SettingsActionTile,
// SettingsTileGrid, SettingsTileDialogs, SettingsListDetail, SettingsFoldedSections, LargeTitle), compared with SMS's
// (YAZ/SMS, feature/settings/SettingsScreen.kt). Both follow Android Quick Settings' tile, AOSP SystemUI QSTileViewImpl: a label and
// under it the value, a tap toggles or opens, a long press explains
// (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt).
// The tiles are Modules' SettingsTiles (ui/component), the choices its ChoiceDialog.
// Store's own: "Check in the background" starts or cancels the once-a-day job (data/update, GrapheneOS' App Store AutoUpdatePrefs,
// https://github.com/GrapheneOS/AppStore) and asks for the notification permission when it is missing, with Compose's permission launcher
// (https://developer.android.com/training/permissions/requesting); "Only on Wi-Fi" is read by StoreBackend at each download.
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yaz.store.BuildConfig
import com.yaz.store.R
import com.yaz.store.data.settings.Settings
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.data.settings.ThemeMode
import com.yaz.store.data.update.UpdateJobs
import com.yaz.store.navigation.LocalReadableInset
import com.yaz.store.navigation.WidthClass
import com.yaz.store.ui.component.ChoiceDialog
import com.yaz.store.ui.component.FloatingAction
import com.yaz.store.ui.component.FloatingFrame
import com.yaz.store.ui.component.FloatingTop
import com.yaz.store.ui.component.LargeTitle
import com.yaz.store.ui.component.SettingsActionTile
import com.yaz.store.ui.component.SettingsFoldedSections
import com.yaz.store.ui.component.SettingsHeaderSpacer
import com.yaz.store.ui.component.SettingsListDetail
import com.yaz.store.ui.component.SettingsSection
import com.yaz.store.ui.component.SettingsSectionTitle
import com.yaz.store.ui.component.SettingsSwitchTile
import com.yaz.store.ui.component.SettingsTile
import com.yaz.store.ui.component.SettingsTileDialogs
import com.yaz.store.ui.component.SettingsTileGrid
import com.yaz.store.ui.icon.AppIcons
import com.yaz.store.ui.theme.TEXT_SCALES
import com.yaz.store.ui.theme.textScaleLabel
import org.koin.compose.koinInject

private enum class OpenDialog { NONE, THEME, TEXT_SIZE }

/** The groups of settings: folded one under another on a phone, a list and the open one's page on a wide window. */
private const val UPDATES = "updates"
private const val APPEARANCE = "appearance"
private const val ABOUT = "about"

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

/**
 * Stateless, so a test draws it with fixed data. A large title that folds as the page scrolls, every setting a tile with
 * its value, a tap toggles or opens, a long press explains, the sections side by side on a wide window and folded on a phone.
 */
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
    // The tile whose long press is open (its id), "" when none.
    var tileId by rememberSaveable { mutableStateOf("") }
    var sectionId by rememberSaveable { mutableStateOf(UPDATES) }
    // On a phone every section starts folded; the one open stays open across a turn of the screen.
    var openId by rememberSaveable { mutableStateOf("") }
    // The page's scroll, held here so a drag on the header drives it (FloatingFrame's `scroll`): the folded
    // list on a phone, the chosen section's page on a wide window (it starts at the top again for each section).
    val compactScroll = rememberScrollState()
    val detailScroll = rememberSaveable(sectionId, saver = ScrollState.Saver) { ScrollState(0) }

    val themeName = themeLabel(settings.themeMode)
    val textSizeName = textScaleLabel(settings.textScale)
    val settingsTitle = stringResource(R.string.settings)


    val background = stringResource(R.string.settings_background)
    val wifiOnly = stringResource(R.string.settings_wifi_only)
    val theme = stringResource(R.string.settings_theme)
    val pureBlack = stringResource(R.string.settings_pure_black)
    val glass = stringResource(R.string.settings_glass)
    val textSize = stringResource(R.string.settings_text_size)
    val version = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)
    val grapheneos = stringResource(R.string.settings_credit_grapheneos)
    val komi = stringResource(R.string.settings_credit_komi)
    val manager = stringResource(R.string.settings_credit_manager)
    val source = stringResource(R.string.settings_source_code)
    val mit = stringResource(R.string.settings_license_mit)
    val apache = stringResource(R.string.settings_license_apache)
    val gpl = stringResource(R.string.settings_license_gpl)

    val sections = listOf(
        SettingsSection(UPDATES, stringResource(R.string.settings_updates), AppIcons.Update),
        SettingsSection(APPEARANCE, stringResource(R.string.settings_appearance), AppIcons.Palette),
        SettingsSection(ABOUT, stringResource(R.string.settings_about), AppIcons.Info)
    )
    val titled = sections.first { it.id == sectionId }
    val openSection = sections.firstOrNull { it.id == openId }

    val tiles: Map<String, List<SettingsTile>> = mapOf(
        UPDATES to listOf(
            SettingsSwitchTile(
                id = "background", name = background, title = background, icon = AppIcons.Notifications,
                summary = stringResource(R.string.settings_background_summary),
                checked = settings.backgroundCheck, onChange = onBackground
            ),
            SettingsSwitchTile(
                id = "wifiOnly", name = wifiOnly, title = wifiOnly, icon = AppIcons.Wifi,
                summary = stringResource(R.string.settings_wifi_only_summary),
                checked = settings.wifiOnly, onChange = onWifiOnly
            )
        ),
        APPEARANCE to listOf(
            SettingsActionTile(
                id = "theme", name = theme, title = theme, icon = AppIcons.Brightness,
                value = themeName, summary = themeName,
                onClick = { dialog = OpenDialog.THEME }
            ),
            SettingsSwitchTile(
                id = "black", name = pureBlack, title = pureBlack, icon = AppIcons.Night,
                summary = stringResource(R.string.settings_pure_black_summary),
                checked = settings.pureBlack, onChange = onPureBlack
            ),
            SettingsSwitchTile(
                id = "glass", name = glass, title = glass, icon = AppIcons.AutoAwesome,
                summary = stringResource(R.string.settings_glass_summary),
                checked = settings.glass, onChange = onGlass
            ),
            SettingsActionTile(
                id = "textSize", name = textSize, title = textSize, icon = AppIcons.FormatSize,
                value = textSizeName, summary = stringResource(R.string.settings_text_size_summary, textSizeName),
                onClick = { dialog = OpenDialog.TEXT_SIZE }
            )
        ),
        ABOUT to listOf(
            SettingsActionTile(
                id = "version", name = version, title = version, icon = AppIcons.Info,
                value = BuildConfig.VERSION_NAME, summary = stringResource(R.string.settings_version_summary)
            ),
            SettingsActionTile(
                id = "grapheneos", name = grapheneos, title = grapheneos, icon = AppIcons.Code,
                value = mit, summary = stringResource(R.string.settings_credit_grapheneos_summary),
                onClick = { uriHandler.openUri("https://github.com/GrapheneOS/AppStore") }
            ),
            SettingsActionTile(
                id = "komi", name = komi, title = komi, icon = AppIcons.Code,
                value = apache, summary = stringResource(R.string.settings_credit_komi_summary),
                onClick = { uriHandler.openUri("https://github.com/komi-store/komi-store") }
            ),
            SettingsActionTile(
                id = "manager", name = manager, title = manager, icon = AppIcons.Code,
                value = gpl, summary = stringResource(R.string.settings_credit_manager_summary),
                onClick = { uriHandler.openUri("https://github.com/MuntashirAkon/AppManager") }
            ),
            SettingsActionTile(
                id = "source", name = source, title = source, icon = AppIcons.Code,
                value = gpl, summary = stringResource(R.string.settings_source_code_summary),
                onClick = { uriHandler.openUri("https://github.com/213YaZ786/Store") }
            )
        )
    )

    // One section's page: its name, then its tiles.
    val page: @Composable ColumnScope.(SettingsSection) -> Unit = { which ->
        SettingsSectionTitle(which.title)
        val list = tiles[which.id].orEmpty()
        if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
    }

    // The window decides: a list and a page side by side from Medium up, as a phone held upright
    // keeps folded sections. The panes take the whole width, so the readable inset is dropped.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = WidthClass.of(maxWidth) != WidthClass.COMPACT
        CompositionLocalProvider(LocalReadableInset provides if (wide) 0.dp else LocalReadableInset.current) {
            FloatingFrame(
                bottom = 0.dp,
                scroll = if (wide) detailScroll else compactScroll,
                top = { FloatingTop(settingsTitle, leading = { FloatingAction(AppIcons.ArrowBack, stringResource(R.string.back), onBack) }) },
                largeTitle = LargeTitle(settingsTitle)
            ) { padding ->
                if (wide) {
                    SettingsListDetail(
                        padding = padding,
                        sections = sections,
                        section = titled,
                        onSection = { sectionId = it.id },
                        detailScroll = detailScroll,
                        detail = { page(titled) }
                    )
                } else {
                    // Scrolls at full width, rows pushed in by the readable inset, so the margins of a tablet scroll like the rest.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(compactScroll)
                            .padding(horizontal = LocalReadableInset.current)
                    ) {
                        SettingsHeaderSpacer(padding)
                        Spacer(Modifier.height(24.dp))
                        SettingsFoldedSections(
                            sections, open = openSection,
                            onOpen = { openId = it?.id.orEmpty() }
                        ) { which ->
                            val list = tiles[which.id].orEmpty()
                            if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
                        }
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    tiles.values.flatten().firstOrNull { it.id == tileId }?.let { tile ->
        SettingsTileDialogs(tile, onDismiss = { tileId = "" })
    }

    when (dialog) {
        OpenDialog.THEME -> ChoiceDialog(
            title = theme,
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onPick = onTheme,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.TEXT_SIZE -> ChoiceDialog(
            title = textSize,
            options = TEXT_SCALES.map { it to textScaleLabel(it) },
            selected = settings.textScale,
            onPick = onTextScale,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.NONE -> Unit
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)
