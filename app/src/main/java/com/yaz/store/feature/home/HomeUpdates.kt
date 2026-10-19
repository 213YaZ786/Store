package com.yaz.store.feature.home

// The Updates zone on top of Home and the "Up to date" state. Sources:
// - Google Play's "Manage apps & device" updates and GrapheneOS' App Store updates screen (MIT, https://github.com/GrapheneOS/AppStore,
//   app/src/main/java/app/grapheneos/apps/ui/UpdatesScreen.kt): one "Update all" with the total size, one row per app with its versions,
//   its size and its own Update;
// - iOS App Store's account updates: the release notes inline under the app, cut short, with "more" opening them where they stand;
//   komi-store's expandable text (Apache-2.0, https://github.com/komi-store/komi-store,
//   core/presentation/.../components/markdown/ExpandableDetails.kt) is the same idea; here the notes are plain text, links stay text.
// - The cut is Text(maxLines, onTextLayout) and TextLayoutResult.hasVisualOverflow
//   (https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextLayoutResult#hasVisualOverflow()); sizes are shown by
//   android.text.format.Formatter (https://developer.android.com/reference/android/text/format/Formatter#formatShortFileSize(android.content.Context,%20long)).
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.install.InstallJob
import com.yaz.store.ui.component.BoldButton
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
                    BoldButton(onClick = onUpdateAll, modifier = Modifier.weight(1f), filled = true) {
                        Text(
                            if (total != null) stringResource(R.string.update_all_size, Formatter.formatShortFileSize(context, total))
                            else stringResource(R.string.update_all)
                        )
                    }
                }
                if (running) QuietButton(onClick = onCancelAll, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel_all)) }
            }
            Column {
                updates.forEachIndexed { index, status ->
                    if (index > 0) RowDivider()
                    UpdateRow(status, HomeModel.jobOf(status, jobs), details[status.entry.detailsKey], HomeModel.sizeOf(status, details), actions)
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(status: AppStatus, job: InstallJob?, details: ReleaseDetails?, size: Long?, actions: CardActions) {
    val context = LocalContext.current
    val moves = if (size != null) {
        stringResource(R.string.versions_move_size, status.installed?.versionName ?: "", status.entry.versionName, Formatter.formatShortFileSize(context, size))
    } else {
        stringResource(R.string.versions_move, status.installed?.versionName ?: "", status.entry.versionName)
    }
    val notes = details?.notes?.takeIf { it.isNotEmpty() }
    AppRow(status, job, actions, subtitle = moves, version = null, below = if (!status.handOver && notes == null) null else ({
        if (status.handOver) {
            Text(stringResource(R.string.hand_over_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notes?.let { Notes(status.entry.packageName, it) }
    }))
}

/** The notes of a release cut to three lines with "More" opening them where they stand; plain text, nothing in it opens. */
@Composable
internal fun Notes(key: String, notes: String) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var cut by remember(notes) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            notes,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) cut = it.hasVisualOverflow }
        )
        if (cut || open) {
            QuietButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.less else R.string.more)) }
        }
    }
}

/**
 * "Up to date", shown when no app waits. Tapped seven times in a row it plays the easter egg (feature/eggs); [onEgg] gets the middle of
 * the row in the window, where the wave starts. The count starts again after two seconds without a tap, as Android's version does.
 */
@Composable
internal fun UpToDateRow(onEgg: (Offset) -> Unit, seen: MutableSet<String>, modifier: Modifier = Modifier) {
    var taps by remember { mutableStateOf(0) }
    var middle by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.runtime.LaunchedEffect(taps) {
        if (taps in 1..6) {
            kotlinx.coroutines.delay(2000)
            taps = 0
        }
    }
    val label = stringResource(R.string.up_to_date)
    ZoneSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
