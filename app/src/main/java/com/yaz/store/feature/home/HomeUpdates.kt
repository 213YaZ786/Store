package com.yaz.store.feature.home

// The Updates section on top of Home (segmented tiles under a centred title) and Home's title with its state mark. Sources:
// - Google Play's "Manage apps & device" updates and GrapheneOS' App Store updates screen (MIT, https://github.com/GrapheneOS/AppStore,
//   app/src/main/java/app/grapheneos/apps/ui/UpdatesScreen.kt): one "Update all" with the total size, one row per app with its versions,
//   its size and its own Update;
// - iOS App Store's update rows: one line (icon, name, version and size), a "more" link just left of the Update button; Google Play's updates
//   list is one compact line per app, the details on tap;
// - sizes are shown by android.text.format.Formatter
//   (https://developer.android.com/reference/android/text/format/Formatter#formatShortFileSize(android.content.Context,%20long)).
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import com.yaz.store.R
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.install.InstallJob
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.SegmentedGap
import com.yaz.store.ui.component.entrancePop
import com.yaz.store.ui.component.segmentedShape
import com.yaz.store.ui.component.sinkClickable
import com.yaz.store.ui.icon.AppIcons

/**
 * The Updates section: its title centred on the page (no zone), Update all (or Cancel all while the queue runs) under it, then one
 * segmented tile per app with an update (ui/component/SegmentedShapes.kt, as AppList).
 */
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.entrancePop(seen, "updates"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(R.string.updates_title)
            val updateAll = if (total != null) stringResource(R.string.update_all_size, Formatter.formatShortFileSize(context, total))
            else stringResource(R.string.update_all)
            val cancelAll = stringResource(R.string.cancel_all)
            if (waiting > 0 && running) {
                // Both side by side when they fit, else stacked full width.
                TwoActions(
                    updateAll, cancelAll,
                    first = { UpdateButton(onClick = onUpdateAll, label = updateAll, modifier = it) },
                    second = { QuietButton(onClick = onCancelAll, modifier = it, shape = ShapeDefaults.Large) { GroupLabel(cancelAll) } }
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (waiting > 0) UpdateButton(onClick = onUpdateAll, label = updateAll, modifier = Modifier.weight(1f))
                    if (running) QuietButton(onClick = onCancelAll, modifier = Modifier.weight(1f), shape = ShapeDefaults.Large) { Text(cancelAll) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SegmentedGap)) {
            updates.forEachIndexed { index, status ->
                // Icon, name, its words and the size of the download, no version numbers (the user, 2026-10-30), then Update; the tile
                // itself opens the app's page.
                AppRow(
                    status,
                    HomeModel.jobOf(status, jobs),
                    actions,
                    subtitle = status.entry.description,
                    size = HomeModel.sizeOf(status, details)?.let { Formatter.formatShortFileSize(context, it) },
                    shape = segmentedShape(index, updates.size),
                    seen = seen,
                    popKey = "update/${status.entry.packageName}",
                    popIndex = index
                )
            }
        }
    }
}

/**
 * The title of Home's top line, "Store", larger and at the start, with the state of the apps right after it: "N updates" when some wait,
 * "Up to date" when none does. Apple's HIG large titles sit at the start of the navigation bar
 * (https://developer.apple.com/design/human-interface-guidelines/navigation-bars) and the iOS App Store puts its account at the other end;
 * Material 3's top app bars have a start-aligned title (https://m3.material.io/components/app-bars/specs). Google Play's "Manage apps &
 * device" says "Updates available" at the head of its list; GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore,
 * app/src/main/java/app/grapheneos/apps/ui/UpdatesScreen.kt) counts the updates too. The count is a plural string
 * (https://developer.android.com/develop/ui/compose/resources#string-resources, pluralStringResource). "Up to date" tapped seven times
 * in a row plays the easter egg (feature/eggs); [onEgg] gets the middle of the pill in the window, where the wave starts. The count
 * starts again after two seconds without a tap, as Android's version does.
 */
@Composable
internal fun StatusPill(updates: Int, allCurrent: Boolean, onEgg: (Offset) -> Unit, seen: MutableSet<String>, onShowUpdates: () -> Unit = {}) {
    // "Store" in plain text, no capsule (the user, 2026-10-30), with its mark: a green tick when every app is up to date, a red warning
    // and the count when updates wait. Dialer's green of a protected network (ui/component/NetworkChip.kt, protectionColor) and the
    // theme's error red. The words and the mark are one tap with no zone: the count leads to the Updates zone, as Google Play's
    // "Updates available" leads to its list; "Up to date" counts the taps of the easter egg.
    val title = stringResource(R.string.home_title)
    if (updates > 0) {
        val count = pluralStringResource(R.plurals.notice_updates, updates, updates)
        Row(
            Modifier.entrancePop(seen, "status").sinkClickable(count, depth = 0.97f, onClick = onShowUpdates).padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TitleText(title, Modifier.weight(1f, fill = false))
            // TalkBack reads "Store, 1 update": the mark carries the words, the bare number is not read twice
            // (https://developer.android.com/develop/ui/compose/accessibility/semantics, clearAndSetSemantics).
            Icon(AppIcons.Warning, contentDescription = count, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            Text(
                updates.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {}
            )
        }
    } else if (allCurrent) {
        UpToDatePill(title, onEgg, seen)
    } else {
        TitleText(title, Modifier.padding(end = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

/**
 * Home's title: headlineMedium in the accent, start-aligned, one line, and a word too wide for its room shrinks last, as FloatingTop's
 * title does (Compose's TextAutoSize.StepBased, https://developer.android.com/develop/ui/compose/text/autosize); a heading for TalkBack
 * (https://developer.android.com/develop/ui/compose/accessibility/semantics, heading()).
 */
@Composable
private fun TitleText(title: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.headlineMedium
    BasicText(
        title,
        style = style.copy(color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Start),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        modifier = modifier.semantics { heading() }
    )
}

/**
 * Two buttons side by side when both labels fit on one line each, else stacked full width: Material 3's AlertDialogFlowRow
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt)
 * puts a dialog's buttons on a new line when they do not fit. The fit is measured, not guessed: the widest label on one line
 * (TextMeasurer, https://developer.android.com/develop/ui/compose/text/measure-text-layout, as SMS's PersonActions measures its tiles)
 * plus the button's 24 dp each side, twice, and the gap. [first] and [second] get the modifier to put on the button.
 */
@Composable
internal fun TwoActions(
    firstLabel: String,
    secondLabel: String,
    modifier: Modifier = Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(firstLabel, secondLabel, style, density) {
        with(density) { listOf(firstLabel, secondLabel).maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }.toDp() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= (widest + 48.dp) * 2 + 8.dp
        // One size for both labels (rememberGroupFontSize, UpdateButton.kt), from the width a button gets less its 24 dp each side.
        val labelSize = rememberGroupFontSize(listOf(firstLabel, secondLabel), style, (if (sideBySide) (maxWidth - 8.dp) / 2 else maxWidth) - 48.dp)
        CompositionLocalProvider(LocalGroupFontSize provides labelSize) {
            if (sideBySide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    first(Modifier.weight(1f))
                    second(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    first(Modifier.fillMaxWidth())
                    second(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// Source: StatusPill's, above.
@Composable
private fun UpToDatePill(title: String, onEgg: (Offset) -> Unit, seen: MutableSet<String>) {
    var taps by remember { mutableStateOf(0) }
    var middle by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.runtime.LaunchedEffect(taps) {
        if (taps in 1..6) {
            kotlinx.coroutines.delay(2000)
            taps = 0
        }
    }
    val label = stringResource(R.string.up_to_date)
    Row(
        Modifier
            .entrancePop(seen, "uptodate")
            .onGloballyPositioned { middle = it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f) }
            .sinkClickable(label, depth = 0.97f) {
                taps++
                if (taps >= 7) {
                    taps = 0
                    onEgg(middle)
                }
            }
            .padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TitleText(title, Modifier.weight(1f, fill = false))
        Icon(AppIcons.CheckCircle, contentDescription = label, tint = Color(0xFF1E8E3E), modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}
