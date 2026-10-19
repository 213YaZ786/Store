package com.yaz.store.feature.home

// The Updates zone on top of Home and the "Up to date" state. Sources:
// - Google Play's "Manage apps & device" updates and GrapheneOS' App Store updates screen (MIT, https://github.com/GrapheneOS/AppStore,
//   app/src/main/java/app/grapheneos/apps/ui/UpdatesScreen.kt): one "Update all" with the total size, one row per app with its versions,
//   its size and its own Update;
// - iOS App Store's update rows: one line (icon, name, version and size), a "more" link just left of the Update button; Google Play's updates
//   list is one compact line per app, the details on tap;
// - sizes are shown by android.text.format.Formatter
//   (https://developer.android.com/reference/android/text/format/Formatter#formatShortFileSize(android.content.Context,%20long)).
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.pluralStringResource
import com.yaz.store.ui.component.FloatingPane
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.install.InstallJob
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.entrancePop
import com.yaz.store.ui.component.sinkClickable
import com.yaz.store.ui.icon.AppIcons

/** The Updates zone: Update all (or Cancel all while the queue runs), then one row per app with an update. */
@Composable
internal fun UpdatesZone(
    updates: List<AppStatus>,
    jobs: List<InstallJob>,
    details: Map<String, ReleaseDetails>,
    actions: CardActions,
    onUpdateAll: () -> Unit,
    onCancelAll: () -> Unit,
    seen: MutableSet<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val running = jobs.any { it.step.active }
    val waiting = updates.count { u -> jobs.none { it.entry.packageName == u.entry.packageName && it.step.active } }
    val total = HomeModel.totalSize(updates, details)
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth().entrancePop(seen, "updates")) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.updates_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting > 0) {
                    UpdateButton(
                        onClick = onUpdateAll,
                        label = if (total != null) stringResource(R.string.update_all_size, Formatter.formatShortFileSize(context, total))
                        else stringResource(R.string.update_all),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (running) QuietButton(onClick = onCancelAll, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel_all)) }
            }
            Column {
                updates.forEachIndexed { index, status ->
                    if (index > 0) RowDivider()
                    UpdateRow(status, HomeModel.jobOf(status, jobs), HomeModel.sizeOf(status, details), actions)
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(status: AppStatus, job: InstallJob?, size: Long?, actions: CardActions) {
    val context = LocalContext.current
    val moves = if (size != null) {
        stringResource(R.string.versions_move_size, status.installed?.versionName ?: "", status.entry.versionName, Formatter.formatShortFileSize(context, size))
    } else {
        stringResource(R.string.versions_move, status.installed?.versionName ?: "", status.entry.versionName)
    }
    // One line: icon, name, the versions and size, then Update; the row itself opens the app's page.
    AppRow(status, job, actions, subtitle = moves, version = null)
}

/**
 * The state of the apps beside the title, in the floating top pane: "N updates" when some wait, "Up to date" when none does. The App
 * Store on iOS puts its account beside the large title, and Google Play's "Manage apps & device" says "Updates available" at the head of
 * its list; GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/UpdatesScreen.kt)
 * counts the updates too. Here the state sits in a small pill of glass on the title's line (FloatingTop's center slot); the count is
 * a plural string (https://developer.android.com/develop/ui/compose/resources#string-resources, pluralStringResource).
 * "Up to date" tapped seven times in a row plays the easter egg (feature/eggs); [onEgg] gets the middle of the pill in the window, where
 * the wave starts. The count starts again after two seconds without a tap, as Android's version does.
 */
@Composable
internal fun StatusPill(updates: Int, allCurrent: Boolean, onEgg: (Offset) -> Unit, seen: MutableSet<String>) {
    if (updates > 0) {
        FloatingPane(shape = CircleShape, modifier = Modifier.entrancePop(seen, "status")) {
            Text(
                pluralStringResource(R.plurals.notice_updates, updates, updates),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    } else if (allCurrent) {
        UpToDatePill(onEgg, seen)
    }
}

// Source: StatusPill's, above.
@Composable
private fun UpToDatePill(onEgg: (Offset) -> Unit, seen: MutableSet<String>) {
    var taps by remember { mutableStateOf(0) }
    var middle by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.runtime.LaunchedEffect(taps) {
        if (taps in 1..6) {
            kotlinx.coroutines.delay(2000)
            taps = 0
        }
    }
    val label = stringResource(R.string.up_to_date)
    FloatingPane(
        shape = CircleShape,
        modifier = Modifier
            .entrancePop(seen, "uptodate")
            .onGloballyPositioned { middle = it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f) }
            .sinkClickable(label, depth = 0.97f) {
                taps++
                if (taps >= 7) {
                    taps = 0
                    onEgg(middle)
                }
            }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
