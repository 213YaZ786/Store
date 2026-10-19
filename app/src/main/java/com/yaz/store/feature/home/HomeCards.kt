package com.yaz.store.feature.home

// The cards of Home's two lists and the one state button of each. Sources:
// - GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/PackageListAdapter.kt):
//   every app listed once with a single state button (Install, Update, Open) on its row;
// - komi-store (Apache-2.0, https://github.com/komi-store/komi-store, feature/apps/presentation/.../components/AppItemCard.kt and
//   core/presentation/.../components/cards/KomiRepoCard.kt): a card with the icon, the name, a short description and an install button,
//   whose body opens the details page;
// - Compose's FlowRow, so the cards wrap and never scroll sideways
//   (https://developer.android.com/develop/ui/compose/layouts/flow), and LinearProgressIndicator for the download
//   (https://developer.android.com/develop/ui/compose/components/progress).
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.entrancePop
import com.yaz.store.ui.component.sinkClickable

/** What a card does with a tap, kept together so the screen passes one object down. */
class CardActions(
    val onPrimary: (AppStatus) -> Unit,
    val onOpen: (AppStatus) -> Unit,
    val onPage: (AppStatus) -> Unit,
    val onCancel: (String) -> Unit
)

/** A list's heading, then its groups (YAZ, More), each with its cards wrapped over the width. */
@Composable
internal fun AppList(
    title: Int,
    apps: List<AppStatus>,
    jobs: List<InstallJob>,
    actions: CardActions,
    seen: MutableSet<String>,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )
        HomeModel.groups(apps).forEach { group ->
            Text(
                stringResource(group.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            CardFlow(group.apps, jobs, actions, seen, keyPrefix = title.toString())
        }
    }
}

/** The cards of one group, as many to a row as the width holds (at least [MinCard] each) and wrapping onto the next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardFlow(apps: List<AppStatus>, jobs: List<InstallJob>, actions: CardActions, seen: MutableSet<String>, keyPrefix: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val perRow = ((maxWidth + gap) / (MinCard + gap)).toInt().coerceAtLeast(1)
        val itemWidth: Dp = (maxWidth - gap * (perRow - 1)) / perRow
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = perRow
        ) {
            apps.forEachIndexed { index, status ->
                AppCard(status, HomeModel.jobOf(status, jobs), actions, seen, "$keyPrefix/${status.entry.packageName}", index, Modifier.width(itemWidth))
            }
        }
    }
}

private val MinCard = 320.dp

@Composable
internal fun AppCard(
    status: AppStatus,
    job: InstallJob?,
    actions: CardActions,
    seen: MutableSet<String>,
    key: String,
    index: Int,
    modifier: Modifier = Modifier
) {
    ZoneSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .entrancePop(seen, key, index)
            .sinkClickable(status.entry.label, depth = 0.97f) { actions.onPage(status) }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(status, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        status.entry.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (status.entry.description.isNotEmpty()) {
                        Text(
                            status.entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        status.installed?.versionName ?: status.entry.versionName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StateButton(status, job, actions, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The one button of an app: Open, Update, Install, Hand over, or the reason it cannot be had. While the queue works on the app it shows
 * the step, a bar and Cancel; a failed try shows its reason above the button, which is offered again.
 */
@Composable
internal fun StateButton(status: AppStatus, job: InstallJob?, actions: CardActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (job != null && job.step.active) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Progress(job)
            QuietButton(onClick = { actions.onCancel(status.entry.packageName) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (job != null && job.step == JobStep.FAILED) {
            Text(jobText(LocalContext.current, job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when (val action = HomeModel.action(status)) {
            HomeModel.Action.Open -> BoldButton(onClick = { actions.onOpen(status) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open)) }
            HomeModel.Action.Update -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = Modifier.fillMaxWidth(), filled = true) { Text(stringResource(R.string.action_update)) }
            HomeModel.Action.Install -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = Modifier.fillMaxWidth(), filled = true) { Text(stringResource(R.string.action_install)) }
            HomeModel.Action.HandOver -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_hand_over)) }
            is HomeModel.Action.Disabled -> BoldButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) {
                Text(
                    if (action.sdk > 0) stringResource(action.reason, HomeModel.androidName(action.sdk)) else stringResource(action.reason),
                    textAlign = TextAlign.Center
                )
            }
        }
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
