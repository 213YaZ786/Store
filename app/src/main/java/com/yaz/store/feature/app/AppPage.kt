package com.yaz.store.feature.app

// The page of one app. Sources:
// - Accrescent (Apache-2.0, https://github.com/accrescent/accrescent, app/src/main/java/app/accrescent/client/ui/screens/AppDetailsScreen.kt):
//   the centred header with the icon, the name and the short description;
// - GrapheneOS AppStore (MIT, https://github.com/GrapheneOS/AppStore, app/src/main/res/layout/details_screen.xml): the full width primary
//   action under the header, the available and the installed versions;
// - the Apple App Store's Information strip and Droid-ify's Version/Size strip (GPL-3.0, idea only): the facts strip;
// - Google Play's app page (Open and Uninstall side by side);
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.OwnUpdate
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.feature.home.AppIcon
import com.yaz.store.feature.home.CardActions
import com.yaz.store.feature.home.GroupLabel
import com.yaz.store.feature.home.HomeModel
import com.yaz.store.feature.home.StateButton
import com.yaz.store.feature.home.TwoActions
import androidx.compose.ui.text.style.TextAlign
import com.yaz.store.feature.home.detailsKey
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.FloatingAction
import com.yaz.store.ui.component.FloatingFrame
import com.yaz.store.ui.component.FloatingTop
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
    val pageScroll = rememberScrollState()
    FloatingFrame(
        bottom = navigationBarBottom(),
        scroll = pageScroll,
        // No name in the top line: the centred header below carries it (Accrescent's AppDetailsScreen has no title over its header).
        top = { FloatingTop(null, leading = { FloatingAction(AppIcons.ArrowBack, stringResource(R.string.back), onBack) }) }
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(pageScroll)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(padding.calculateTopPadding()))

                // The header does nothing, so no zone: a centred column, icon, name, short description (Accrescent's AppDetailsScreen).
                Column(
                    Modifier.fillMaxWidth().entrancePop(seen, "page-head"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppIcon(status, 96.dp)
                    // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
                    Text(entry.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                    if (entry.description.isNotEmpty()) {
                        Text(entry.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }

                // The primary action full width under the header (GrapheneOS AppStore's details_screen.xml); only the buttons are taps.
                Column(Modifier.fillMaxWidth().entrancePop(seen, "page-actions", 1), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val action = HomeModel.action(status)
                    val installed = status.installed != null && status.state != AppState.FOREIGN
                    if (action == HomeModel.Action.Open && OwnUpdate.isOwn(entry.packageName) && !busy) {
                        // Store's own page when up to date: nothing to open (it is open) and Store does not uninstall itself.
                    } else if (action == HomeModel.Action.Open && !busy) {
                        // Open and Uninstall side by side, as Google Play's header has them, stacked when the words do not fit.
                        val open = stringResource(R.string.action_open)
                        val uninstall = stringResource(R.string.action_uninstall)
                        TwoActions(
                            open, uninstall,
                            first = { BoldButton(onClick = { actions.onOpen(status) }, modifier = it, shape = ShapeDefaults.Large) { GroupLabel(open) } },
                            second = { BoldButton(onClick = { onUninstall(status) }, modifier = it, shape = ShapeDefaults.Large) { GroupLabel(uninstall) } }
                        )
                    } else {
                        StateButton(status, job, actions, Modifier.fillMaxWidth())
                        if (installed && !busy) {
                            val open = stringResource(R.string.action_open)
                            val uninstall = stringResource(R.string.action_uninstall)
                            TwoActions(
                                open, uninstall,
                                first = { BoldButton(onClick = { actions.onOpen(status) }, modifier = it, shape = ShapeDefaults.Large) { GroupLabel(open) } },
                                second = { BoldButton(onClick = { onUninstall(status) }, modifier = it, shape = ShapeDefaults.Large) { GroupLabel(uninstall) } }
                            )
                        }
                    }
                }

                // The facts strip, no zone: up to three equal columns, a value over its label, as the App Store's Information strip (Age,
                // Category, Size...) and Droid-ify's Version/Size strip (GPL-3.0, idea only). The offered version, or "installed → offered"
                // when an update waits (GrapheneOS AppStore's details_screen.xml shows the available and the installed versions).
                val version = status.installed?.versionName.takeIf { status.state == AppState.UPDATE }
                    ?.let { "$it → ${entry.versionName}" } ?: entry.versionName
                val facts = listOfNotNull(
                    R.string.page_version to version,
                    HomeModel.sizeOf(status, details?.let { mapOf(entry.detailsKey to it) }.orEmpty())?.let { R.string.page_size to Formatter.formatShortFileSize(context, it) },
                    details?.publishedAt?.let { R.string.page_released to DateFormat.getMediumDateFormat(context).format(Date(it.toEpochMilli())) }
                )
                Row(Modifier.fillMaxWidth().entrancePop(seen, "page-facts", 2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.forEach { (label, value) -> Fact(label, value, Modifier.weight(1f)) }
                }

                // Plain lines, no zone: who updates the app, and its package.
                Column(Modifier.fillMaxWidth().entrancePop(seen, "page-lines", 3), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    installerWords(status)?.let { Line(stringResource(it)) }
                    Line(entry.packageName)
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

// One column of the facts strip: a value over its label, both centred, wrapping between words (the App Store's Information strip,
// Droid-ify's Version/Size strip); read as one item by TalkBack (https://developer.android.com/develop/ui/compose/accessibility/merging-clearing).
@Composable
private fun Fact(label: Int, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// A plain centred line under the facts.
@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
