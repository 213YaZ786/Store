package com.yaz.store.feature.home

// The rows of Home's two lists and the one state button of each. Sources:
// - the iOS App Store's lists (Apple's Apps and Updates tabs, seen for real): one compact row per app, the icon on the left, the name, a
//   one-line subtitle, the action button on the right, no line between rows here (ours: gaps only); Google Play's "Manage apps &
//   device" has the same compact rows;
// - GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/PackageListAdapter.kt):
//   every app listed once with a single state button (Install, Update, Open) on its row, the whole row opening the app's page;
// - komi-store (Apache-2.0, https://github.com/komi-store/komi-store, feature/apps/presentation/.../components/AppItemCard.kt):
//   the icon, the name, a short description and an install button, whose body opens the details page;
// - Material 3's list anatomy (https://m3.material.io/components/lists/specs: leading image, headline, supporting text, trailing element)
//   as segmented tiles (https://m3.material.io/components/lists/guidelines, "use gaps for contained lists"; ui/component/SegmentedShapes.kt);
//   LinearProgressIndicator for the download (https://developer.android.com/develop/ui/compose/components/progress).
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.OwnUpdate
import com.yaz.store.data.catalog.ReleaseDetails
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.SegmentedGap
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.entrancePop
import com.yaz.store.ui.component.rememberHaptics
import com.yaz.store.ui.component.segmentedShape

/** What a row does with a tap, kept together so the screen passes one object down. */
class CardActions(
    val onPrimary: (AppStatus) -> Unit,
    val onOpen: (AppStatus) -> Unit,
    val onPage: (AppStatus) -> Unit,
    val onCancel: (String) -> Unit
)

/**
 * A list's heading, always shown, centred on the page (no zone: a title does nothing), then its apps as segmented tiles, alphabetical:
 * each row its own tile, SegmentedGap between them, no lines (androidx material3 ListItemDefaults.segmentedShapes, see
 * ui/component/SegmentedShapes.kt; Android 16 Settings lists look the same). In two columns both lists have a title, so both start at
 * the same height.
 */
@Composable
internal fun AppList(
    title: Int,
    apps: List<AppStatus>,
    jobs: List<InstallJob>,
    actions: CardActions,
    seen: MutableSet<String>,
    details: Map<String, ReleaseDetails> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        val sorted = HomeModel.sorted(apps)
        Column(verticalArrangement = Arrangement.spacedBy(SegmentedGap)) {
            sorted.forEachIndexed { index, status ->
                AppRow(
                    status,
                    HomeModel.jobOf(status, jobs),
                    actions,
                    subtitle = status.entry.description,
                    // No version numbers (the user, 2026-10-30): the size of the download, only when an update waits.
                    size = if (status.state == AppState.UPDATE) HomeModel.sizeOf(status, details)?.let { Formatter.formatShortFileSize(context, it) } else null,
                    shape = segmentedShape(index, sorted.size),
                    seen = seen,
                    popKey = "row/$title/${status.entry.packageName}",
                    popIndex = index
                )
            }
        }
    }
}

/**
 * A section's title on the page: centred, titleLarge, in the accent, a heading for TalkBack
 * (https://developer.android.com/develop/ui/compose/accessibility/semantics, heading()); Modules' SettingsSectionTitle centres its
 * titles the same way.
 */
@Composable
internal fun SectionTitle(title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).semantics { heading() }
    )
}

// Where the text of a row starts: 14 dp of padding, the 56 dp icon and a 12 dp gap.
private val TextStart = 82.dp

/**
 * One app as a tile: icon, name, its words, the update's size when given, the state button on the right; the whole tile is the tap that
 * opens the app's page, so it is its own zone, of [shape] (segmentedShape). Its entrance pop ([seen], [popKey], [popIndex]) and its press
 * ripple follow that shape (ZoneSurface's clickable Surface clips to it). [below] sits under the row, aligned with the text;
 * [beforeButton] sits just before the button.
 */
@Composable
internal fun AppRow(
    status: AppStatus,
    job: InstallJob?,
    actions: CardActions,
    subtitle: String,
    size: String?,
    shape: Shape,
    seen: MutableSet<String>,
    popKey: String,
    popIndex: Int,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    beforeButton: (@Composable () -> Unit)? = null
) {
    val active = job != null && job.step.active
    val haptics = rememberHaptics()
    ZoneSurface(
        shape = shape,
        onClick = {
            haptics.tick()
            actions.onPage(status)
        },
        modifier = Modifier.fillMaxWidth().entrancePop(seen, popKey, popIndex)
    ) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(status, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // The name and its words wrap between words, never cut (containers with text are adaptive, 2026-10-30).
                Text(
                    status.entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (size != null) {
                    Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (active) {
                QuietButton(onClick = { actions.onCancel(status.entry.packageName) }, shape = ShapeDefaults.Large) { Text(stringResource(R.string.cancel)) }
            } else if (beforeButton != null) {
                // The link and the button stay close, so the line of words keeps the room.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    beforeButton()
                    ActionButton(status, actions)
                }
            } else {
                ActionButton(status, actions)
            }
        }
        val failed = job != null && job.step == JobStep.FAILED
        if (active || failed || below != null) {
            Column(Modifier.padding(start = TextStart - 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job != null && active) Progress(job)
                if (job != null && failed) {
                    Text(jobText(LocalContext.current, job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                below?.invoke(this)
            }
        }
    }
    }
}

/** The button of an app's state at its natural width (a row's), or as [modifier] sets it (the app page's full width). */
@Composable
internal fun ActionButton(status: AppStatus, actions: CardActions, modifier: Modifier = Modifier) {
    when (val action = HomeModel.action(status)) {
        // Store's own row, up to date: no Open (it would only reopen Store), as GrapheneOS' App Store hides it for itself
        // (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/DetailsScreen.kt, UP_TO_DATE: pkgName != selfPkgName).
        HomeModel.Action.Open -> if (!OwnUpdate.isOwn(status.entry.packageName)) {
            BoldButton(onClick = { actions.onOpen(status) }, modifier = modifier, shape = ShapeDefaults.Large) { Text(stringResource(R.string.action_open)) }
        }
        HomeModel.Action.Update -> UpdateButton(onClick = { actions.onPrimary(status) }, label = stringResource(R.string.action_update), modifier = modifier, shape = ShapeDefaults.Large)
        HomeModel.Action.Install -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = modifier, filled = true, shape = ShapeDefaults.Large) { Text(stringResource(R.string.action_install)) }
        HomeModel.Action.HandOver -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = modifier, shape = ShapeDefaults.Large) { Text(stringResource(R.string.action_hand_over)) }
        is HomeModel.Action.Disabled -> BoldButton(onClick = {}, modifier = modifier.widthIn(max = 150.dp), enabled = false, shape = ShapeDefaults.Large) {
            Text(
                if (action.sdk > 0) stringResource(action.reason, HomeModel.androidName(action.sdk)) else stringResource(action.reason),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The one button of an app on its page: Open, Update, Install, Hand over, or the reason it cannot be had. While the queue works on the
 * app it shows the step, a bar and Cancel; a failed try shows its reason above the button, which is offered again.
 */
@Composable
internal fun StateButton(status: AppStatus, job: InstallJob?, actions: CardActions, modifier: Modifier = Modifier) {
    if (job != null && job.step.active) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Progress(job)
            QuietButton(onClick = { actions.onCancel(status.entry.packageName) }, modifier = Modifier.fillMaxWidth(), shape = ShapeDefaults.Large) { Text(stringResource(R.string.cancel)) }
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (job != null && job.step == JobStep.FAILED) {
            Text(jobText(LocalContext.current, job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ActionButton(status, actions, Modifier.fillMaxWidth())
    }
}

/** The step in words and, while it downloads, its bar (Material's LinearProgressIndicator). */
@Composable
internal fun Progress(job: InstallJob, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(jobText(LocalContext.current, job), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (job.step == JobStep.DOWNLOADING) {
            LinearProgressIndicator(progress = { job.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        } else if (job.step.active) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
    }
}
