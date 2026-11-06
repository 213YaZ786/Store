package com.yaz.store.feature.setup

// The setup guide at the first start, as tiles: only the choices that matter to the person, everything else already the best setting.
// Ported from Language's WelcomeScreen (our own app, itself after FlorisBoard's SetupScreen: every step is read from the system again each time the
// screen comes back, and turns done by itself), on Modules' SetupTiles:
// https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#rememberSetupTiles
// https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeContent
// Store's steps: allowing installs (PackageManager.canRequestPackageInstalls and Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES with this package),
// notifications (POST_NOTIFICATIONS), and the check in the background, off until the person turns it on (the same code path as Settings: the setting and
// UpdateJobs.sync). The network updates use stays any network (GrapheneOS' pref_def_network_type_for_bg_update_job is 1) and has no tile. Handing an
// app over stays on its card and its page.
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.store.R
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.data.update.UpdateJobs
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.LoadingMark
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.SetupTile
import com.yaz.store.ui.component.SetupTileDialog
import com.yaz.store.ui.component.SetupTileGrid
import com.yaz.store.ui.component.leftText
import com.yaz.store.ui.icon.AppIcons
import org.koin.compose.koinInject

// not ported: WelcomeContent (it is SetupContent below, under its own name: the layout split from the screen so a picture is taken with fixed state)
// not ported: isPhoneVoiceInput, isVoiceKeyboardEnabled, the microphone, the engine, the languages and Updates tiles (Language's steps, not Store's)
// not ported: reopened (Store's Settings Guide reopens these pages with the same Start)
// not ported: requiredLeft and the "Still needed" line (Skip stays as Store's guide had it; a refused install permission is told by the tile's error state)

/**
 * The few choices worth making as tiles, each read from the system again each time the screen comes back: allowing installs (required, in error
 * while refused), notifications, and the check in the background (on only when the person turns it on). The check is not counted among the steps left.
 */
@Composable
fun rememberSetupTiles(
    canInstall: Boolean,
    hasNotificationPermission: Boolean,
    backgroundCheck: Boolean,
    onAllowInstalls: () -> Unit,
    onAllowNotifications: () -> Unit,
    onBackground: () -> Unit
): List<SetupTile> {
    val installs = stringResource(R.string.setup_installs)
    val notifications = stringResource(R.string.setup_notifications)
    val background = stringResource(R.string.settings_background)
    return listOf(
        SetupTile(
            id = "installs", name = installs, title = installs, icon = AppIcons.Apps,
            state = stringResource(if (canInstall) R.string.setup_allowed else R.string.setup_not_allowed_yet),
            summary = stringResource(R.string.setup_installs_summary),
            done = canInstall, required = true, error = !canInstall,
            onClick = onAllowInstalls
        ),
        SetupTile(
            id = "notifications", name = notifications, title = notifications, icon = AppIcons.Notifications,
            state = stringResource(if (hasNotificationPermission) R.string.setup_on else R.string.setup_off),
            summary = stringResource(R.string.setup_notifications_summary),
            done = hasNotificationPermission, required = false,
            onClick = onAllowNotifications
        ),
        SetupTile(
            id = "background", name = background, title = background, icon = AppIcons.Update,
            state = stringResource(if (backgroundCheck) R.string.setup_on else R.string.setup_off),
            summary = stringResource(R.string.settings_background_summary),
            done = backgroundCheck, required = false,
            onClick = onBackground
        )
    )
}

@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val settings: SettingsStore = koinInject()
    val context = LocalContext.current
    val current by settings.settings.collectAsState()
    var checks by remember { mutableIntStateOf(0) }
    // The permissions are granted on Android's own screens: read again when the app comes back.
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val canInstall = remember(checks) { context.packageManager.canRequestPackageInstalls() }
    val hasNotificationPermission = remember(checks) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    SetupContent(
        canInstall = canInstall,
        notifying = hasNotificationPermission,
        backgroundCheck = current.backgroundCheck,
        onAllowInstalls = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            }
        },
        onAllowNotifications = { requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS) },
        // The code path of Settings' "Check in the background": the setting, the job, and the notification permission it needs.
        onBackground = {
            val on = !settings.current.backgroundCheck
            settings.update { it.copy(backgroundCheck = on) }
            UpdateJobs.sync(context, settings.current)
            if (on && !hasNotificationPermission) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onFinish = {
            settings.update { it.copy(setupDone = true) }
            onFinish()
        }
    )
}

/**
 * What the guide draws from the state it is given: the logo, the title, how many steps are left, the tiles, then Skip and Start. Stateless, so a
 * test draws it with fixed data. Skip is offered while a step is not settled; Start always ends the guide.
 */
@Composable
fun SetupContent(
    canInstall: Boolean,
    notifying: Boolean,
    backgroundCheck: Boolean = false,
    onAllowInstalls: () -> Unit = {},
    onAllowNotifications: () -> Unit = {},
    onBackground: () -> Unit = {},
    onFinish: () -> Unit = {}
) {
    val steps = rememberSetupTiles(canInstall, notifying, backgroundCheck, onAllowInstalls, onAllowNotifications, onBackground)
    // The tile whose long press is open (its id), "" when none.
    var explained by rememberSaveable { mutableStateOf("") }
    val settled = canInstall && notifying
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(32.dp))
            LoadingMark(size = 120.dp)
            Spacer(Modifier.height(16.dp))
            // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            // The steps left are Install apps and Notifications; the check in the background is a choice, not a step.
            Text(steps.filter { it.id != "background" }.leftText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            SetupTileGrid(steps) { explained = it.id }

            Spacer(Modifier.height(28.dp))
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!settled) QuietButton(onClick = onFinish, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.setup_skip)) }
                BoldButton(onClick = onFinish, modifier = Modifier.weight(1f), filled = true) { Text(stringResource(R.string.setup_start)) }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    steps.firstOrNull { it.id == explained }?.let { tile ->
        SetupTileDialog(tile, onDismiss = { explained = "" })
    }
}
