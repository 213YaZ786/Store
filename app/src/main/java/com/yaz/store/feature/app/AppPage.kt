package com.yaz.store.feature.app

// The page of one app. Sources:
// - Google Play's app page header (icon, name, then Open and Uninstall side by side);
// - komi-store's details page (Apache-2.0, https://github.com/komi-store/komi-store, feature/details/presentation/.../DetailsRoot.kt):
//   the versions and sizes under the header;
// - the frame is Files' (YAZ/Files at HEAD, feature/settings/SettingsScreen.kt): FloatingFrame with FloatingTop, a Back round action,
//   the content running under the panes;
// - Uninstall is Android's ACTION_DELETE with a package: URI, which needs REQUEST_DELETE_PACKAGES
//   (https://developer.android.com/reference/android/content/Intent#ACTION_DELETE,
//   https://developer.android.com/reference/android/Manifest.permission#REQUEST_DELETE_PACKAGES);
// - Source opens the repo page in the browser through ACTION_VIEW, only for an https address on github.com
//   (https://developer.android.com/reference/android/content/Intent#ACTION_VIEW).
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.feature.home.AppIcon
import com.yaz.store.feature.home.CardActions
import com.yaz.store.feature.home.HomeModel
import com.yaz.store.feature.home.StateButton
import com.yaz.store.feature.home.detailsKey
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.FloatingAction
import com.yaz.store.ui.component.FloatingFrame
import com.yaz.store.ui.component.FloatingTop
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.entrancePop
import com.yaz.store.ui.component.navigationBarBottom
import com.yaz.store.ui.icon.AppIcons
import java.util.Date

/** Stateless, so a test draws it with fixed data. */
@Composable
fun AppPageContent(
    status: AppStatus,
    job: InstallJob?,
    details: ReleaseDetails?,
    actions: CardActions,
    onBack: () -> Unit,
    onUninstall: (AppStatus) -> Unit = {},
    onSource: (AppStatus) -> Unit = {},
    seen: MutableSet<String> = androidx.compose.runtime.remember { mutableSetOf() }
) {
    val context = LocalContext.current
    val entry = status.entry
    val busy = job != null && job.step.active
    FloatingFrame(
        bottom = navigationBarBottom(),
        top = { FloatingTop(entry.label, leading = { FloatingAction(AppIcons.ArrowBack, stringResource(R.string.back), onBack) }) }
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(padding.calculateTopPadding()))

                ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().entrancePop(seen, "page-head")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(status, 72.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                Text(entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val action = HomeModel.action(status)
                        val installed = status.installed != null && status.state != AppState.FOREIGN
                        if (action == HomeModel.Action.Open && !busy) {
                            // Open and Uninstall side by side, as Google Play's header has them.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BoldButton(onClick = { actions.onOpen(status) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_open)) }
                                BoldButton(onClick = { onUninstall(status) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_uninstall)) }
                            }
                        } else {
                            StateButton(status, job, actions, Modifier.fillMaxWidth())
                            if (installed && !busy) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BoldButton(onClick = { actions.onOpen(status) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_open)) }
                                    BoldButton(onClick = { onUninstall(status) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_uninstall)) }
                                }
                            }
                        }
                    }
                }

                if (entry.description.isNotEmpty()) {
                    Block(R.string.page_description, seen) { Text(entry.description, style = MaterialTheme.typography.bodyMedium) }
                }

                Block(null, seen) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(R.string.page_installed, status.installed?.versionName ?: stringResource(R.string.page_not_installed))
                        Field(R.string.page_offered, entry.versionName)
                        HomeModel.sizeOf(status, details?.let { mapOf(entry.detailsKey to it) }.orEmpty())?.let {
                            Field(R.string.page_size, Formatter.formatShortFileSize(context, it))
                        }
                        details?.publishedAt?.let { Field(R.string.page_released, DateFormat.getMediumDateFormat(context).format(Date(it.toEpochMilli()))) }
                        installerWords(status)?.let { Field(R.string.page_installer, stringResource(it)) }
                    }
                }

                BoldButton(onClick = { onSource(status) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.page_source)) }
                Spacer(Modifier.height(navigationBarBottom() + 16.dp))
            }
        }
    }
}

private fun installerWords(status: AppStatus): Int? = when {
    status.installed == null -> null
    status.state == AppState.FOREIGN -> R.string.installer_foreign
    status.handOver -> R.string.installer_hand_over
    else -> R.string.installer_store
}

/** A titled zone. */
@Composable
private fun Block(title: Int?, seen: MutableSet<String>, content: @Composable () -> Unit) {
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().entrancePop(seen, "page-${title ?: 0}", 1)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

// A label over its value: never a label in one column and its value in the next.
@Composable
private fun Field(label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Uninstall: Android asks the person itself. */
fun uninstall(context: Context, status: AppStatus) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", status.entry.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The repo page in the browser: an https address on github.com, nothing else. */
fun openSource(context: Context, status: AppStatus) {
    val uri = Uri.parse("https://github.com/${ReleaseNet.OWNER}/${status.entry.repo}")
    if (uri.scheme != "https" || uri.host != "github.com") return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
