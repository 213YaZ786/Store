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
//   the wavy LinearWavyProgress for the download (Material 3 Expressive, https://m3.material.io/components/progress-indicators/overview);
// - the update card while a job runs: Google Play's download row (a ring around the shrunken icon, Cancel in the Install button's place) and
//   GrapheneOS' App Store details screen (progress and Cancel); the buttons are Material 3 Expressive's Medium size (androidx
//   compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ButtonMediumTokens.kt: ContainerHeight 56 dp,
//   LeadingSpace and TrailingSpace 24 dp, which is ButtonDefaults.ContentPadding's horizontal padding), their label titleMedium; the ring is Modules'
//   CircularWavyProgress and the bar its LinearWavyProgress with the thick strokes of androidx's LinearThickWavyProgressIndicatorSample
//   (compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/ProgressIndicatorSamples.kt: 8 dp strokes, Round caps,
//   Modifier.height(14.dp)).
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import java.text.NumberFormat
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
import com.yaz.store.ui.component.CircularWavyProgress
import com.yaz.store.ui.component.LinearWavyProgress
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
    val onCancel: (String) -> Unit,
    /** The app's App info page in Android's settings: Enable of a disabled app, App info on its page. */
    val onInfo: (AppStatus) -> Unit = {}
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
    modifier: Modifier = Modifier,
    showTitle: Boolean = true
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The first section's title sits on Home's top line instead (showTitle = false), see HomeContent.
        if (showTitle) SectionTitle(title)
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
    var showDetails by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    // Google Play's download row: the icon shrinks inside its slot and a ring runs around it.
    val iconSize by animateDpAsState(if (active) 44.dp else 56.dp, label = "iconSize")
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
            // The slot keeps its 56 dp; the ring is Modules' CircularWavyProgress (determinate while it downloads, a full ring for the
            // other steps), thin enough to leave the shrunken icon free.
            Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) {
                AppIcon(status, iconSize)
                if (job != null && active) {
                    val ringStroke = rememberRoundStroke(3.dp)
                    CircularWavyProgress(
                        progress = { if (job.step == JobStep.DOWNLOADING) job.progress.coerceIn(0f, 1f) else 1f },
                        modifier = Modifier.size(IconSlot),
                        color = MaterialTheme.colorScheme.primary,
                        stroke = ringStroke,
                        trackStroke = ringStroke
                    )
                }
            }
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
                CancelButton(status.entry.packageName, actions)
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
        // The progress line starts at the card's content start, under the icon, and ends level with the button's end edge.
        if (job != null && active) Progress(job)
        val failed = job != null && job.step == JobStep.FAILED
        if (failed || below != null) {
            Column(Modifier.padding(start = TextStart - 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job != null && failed) {
                    Text(jobText(LocalContext.current, job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    // Details opens the source's ErrorDialog (GrapheneOS' App Store, ui/ErrorDialog.kt): "<App> was not installed", the reason, Copy and Close.
                    QuietButton(onClick = { showDetails = true }, shape = ShapeDefaults.Large) { Text(stringResource(R.string.details)) }
                    if (showDetails) {
                        ErrorDialog(
                            ErrorTemplate(
                                title = { ctx -> ctx.getString(R.string.error_not_installed, job.entry.label) },
                                message = { ctx ->
                                    val message = job.why ?: ctx.getString(R.string.job_failed_default)
                                    if (job.detail.isNullOrBlank()) message else message + "\n\n" + job.detail
                                }
                            ),
                            onDismiss = { showDetails = false }
                        )
                    }
                }
                below?.invoke(this)
            }
        }
    }
    }
}

// The 56 dp icon slot of a row, and Material 3 Expressive's Medium button height (ButtonMediumTokens.ContainerHeight, androidx
// compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ButtonMediumTokens.kt): the height is a minimum
// on the modifier given to BoldButton, whose GlassTarget passes it to the button, so the glass, the ripple and UpdateButton's glint share
// the same 56 dp bounds, and a label that wraps still grows the button. The 24 dp sides (LeadingSpace, TrailingSpace) are
// ButtonDefaults.ContentPadding's own.
private val IconSlot = 56.dp
private val MediumHeight = 56.dp

/** The label of a Medium button: titleMedium (16 sp), wrapping between words. */
@Composable
private fun MediumLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

/** Cancel in the place of the state button while a job runs: BoldButton, not filled, at the Medium size. */
@Composable
private fun CancelButton(packageName: String, actions: CardActions, modifier: Modifier = Modifier) {
    BoldButton(
        onClick = { actions.onCancel(packageName) },
        modifier = modifier.heightIn(min = MediumHeight),
        shape = ShapeDefaults.Large
    ) { MediumLabel(stringResource(R.string.cancel)) }
}

/** The button of an app's state at its natural width (a row's), or as [modifier] sets it (the app page's full width). */
@Composable
internal fun ActionButton(status: AppStatus, actions: CardActions, modifier: Modifier = Modifier) {
    val medium = modifier.heightIn(min = MediumHeight)
    when (val action = HomeModel.action(status)) {
        // Store's own row, up to date: no Open (it would only reopen Store), as GrapheneOS' App Store hides it for itself
        // (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/DetailsScreen.kt, UP_TO_DATE: pkgName != selfPkgName).
        HomeModel.Action.Open -> if (!OwnUpdate.isOwn(status.entry.packageName)) {
            BoldButton(onClick = { actions.onOpen(status) }, modifier = medium, shape = ShapeDefaults.Large) { MediumLabel(stringResource(R.string.action_open)) }
        }
        HomeModel.Action.Update -> UpdateButton(
            onClick = { actions.onPrimary(status) },
            label = stringResource(R.string.action_update),
            modifier = medium,
            shape = ShapeDefaults.Large,
            labelStyle = MaterialTheme.typography.titleMedium
        )
        HomeModel.Action.Install -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = medium, filled = true, shape = ShapeDefaults.Large) { MediumLabel(stringResource(R.string.action_install)) }
        HomeModel.Action.HandOver -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = medium, shape = ShapeDefaults.Large) { MediumLabel(stringResource(R.string.action_hand_over)) }
        // A disabled app: Enable opens its App info page, as GrapheneOS' DetailsScreen does (DISABLED: btn_enable_package, appDetailsIntent).
        HomeModel.Action.Enable -> BoldButton(onClick = { actions.onInfo(status) }, modifier = medium, filled = true, shape = ShapeDefaults.Large) { MediumLabel(stringResource(R.string.action_enable)) }
        is HomeModel.Action.Disabled -> BoldButton(onClick = {}, modifier = medium.widthIn(max = 150.dp), enabled = false, shape = ShapeDefaults.Large) {
            Text(
                if (action.sdk > 0) stringResource(action.reason, HomeModel.androidName(action.sdk)) else stringResource(action.reason),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The one button of an app on its page: Open, Update, Install, Hand over, or the reason it cannot be had, full width at the Medium size.
 * While the queue works on the app it shows the progress line above a full-width Cancel; a failed try shows its reason above the button,
 * which is offered again.
 */
@Composable
internal fun StateButton(status: AppStatus, job: InstallJob?, actions: CardActions, modifier: Modifier = Modifier) {
    if (job != null && job.step.active) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Progress(job)
            CancelButton(status.entry.packageName, actions, Modifier.fillMaxWidth())
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

/** A round-capped stroke of [width], as LinearThickWavyProgressIndicatorSample builds its thick one (androidx material3 samples, ProgressIndicatorSamples.kt). */
@Composable
private fun rememberRoundStroke(width: Dp): Stroke {
    val px = with(LocalDensity.current) { width.toPx() }
    return remember(px) { Stroke(width = px, cap = StrokeCap.Round) }
}

/**
 * The progress line of a running job: the percentage in a box as wide as the icon slot, so it sits centred under the icon, then the row's
 * 12 dp gap and Material 3 Expressive's thick wavy bar (Modules' LinearWavyProgress, from androidx material3's LinearWavyProgressIndicator,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt;
 * thick as LinearThickWavyProgressIndicatorSample: 8 dp strokes, Modifier.height(14.dp)) filling the rest of the width. Determinate with
 * the percentage while it downloads; indeterminate, the percentage slot empty, for the other active steps. TalkBack reads the step.
 */
@Composable
internal fun Progress(job: InstallJob, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = jobText(context, job)
    val thick = rememberRoundStroke(8.dp)
    val percent = remember { NumberFormat.getPercentInstance() }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val downloading = job.step == JobStep.DOWNLOADING
        // fixed size: the slot is exactly the icon's width, so the figure sits centred under the icon; the figure may spill past it, never cut.
        Box(Modifier.width(IconSlot), contentAlignment = Alignment.Center) {
            if (downloading) {
                Text(
                    percent.format(job.progress.coerceIn(0f, 1f).toDouble()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.wrapContentWidth(unbounded = true)
                )
            }
        }
        if (downloading) {
            LinearWavyProgress(
                progress = { job.progress.coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(14.dp),
                color = MaterialTheme.colorScheme.primary,
                stroke = thick,
                trackStroke = thick
            )
        } else {
            LinearWavyProgress(
                modifier = Modifier.weight(1f).height(14.dp),
                color = MaterialTheme.colorScheme.primary,
                stroke = thick,
                trackStroke = thick
            )
        }
    }
}
