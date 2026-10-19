package com.yaz.store.feature.setup

// The setup guide at the first start. Ported from Files (YAZ/Files at HEAD, feature/main/WelcomeScreen.kt), itself from Calculator
// (https://github.com/213YaZ786/Calculator, feature/main/WelcomeScreen.kt), whose zones turn into a tick once settled and are read again
// when the app comes back from Android's own pages (LifecycleResumeEffect); the YAZ apps' SetupZone steps (Contacts at HEAD,
// feature/main/SetupZone.kt) ask the notification permission with Compose's permission launcher.
// Store's steps: allowing installs (PackageManager.canRequestPackageInstalls and Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES with this
// package, https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES), notifications
// (https://developer.android.com/develop/ui/views/notifications/notification-permission). Handing an app over stays on its card and its page.
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.store.R
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.LoadingMark
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.icon.AppIcons
import org.koin.compose.koinInject

@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val settings: SettingsStore = koinInject()
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    // The permissions are granted on Android's own screens: read again when the app comes back.
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val canInstall = remember(checks) { context.packageManager.canRequestPackageInstalls() }
    val notifying = remember(checks) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    SetupContent(
        canInstall = canInstall,
        notifying = notifying,
        onAllowInstalls = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            }
        },
        onAllowNotifications = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onFinish = {
            settings.update { it.copy(setupDone = true) }
            onFinish()
        }
    )
}

/** Stateless, so a test draws it with fixed data. */
@Composable
fun SetupContent(
    canInstall: Boolean,
    notifying: Boolean,
    onAllowInstalls: () -> Unit = {},
    onAllowNotifications: () -> Unit = {},
    onFinish: () -> Unit = {}
) {
    val settled = canInstall && notifying
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(32.dp))
        LoadingMark(size = 120.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))

        SetupZone(AppIcons.Update, stringResource(R.string.setup_installs), done = canInstall) {
            if (!canInstall) BoldButton(onClick = onAllowInstalls, filled = true) { Text(stringResource(R.string.setup_installs_allow)) }
        }
        Spacer(Modifier.height(16.dp))
        SetupZone(AppIcons.Notifications, stringResource(R.string.setup_notifications), done = notifying) {
            if (!notifying) BoldButton(onClick = onAllowNotifications) { Text(stringResource(R.string.setup_notifications_allow)) }
        }

        Spacer(Modifier.height(28.dp))
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!settled) QuietButton(onClick = onFinish, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.setup_skip)) }
            BoldButton(onClick = onFinish, modifier = Modifier.weight(1f), filled = true) { Text(stringResource(R.string.setup_start)) }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** One thing to settle, a tick once done. */
@Composable
private fun SetupZone(icon: ImageVector, title: String, done: Boolean, content: @Composable () -> Unit) {
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) AppIcons.CheckCircle else icon,
                    contentDescription = if (done) stringResource(R.string.done) else null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            content()
        }
    }
}
