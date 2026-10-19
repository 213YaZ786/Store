package com.yaz.store.feature.home

// The rows of Home's two lists and the one state button of each. Sources:
// - the iOS App Store's lists (Apple's Apps and Updates tabs, seen for real): one compact row per app, the icon on the left, the name, a
//   one-line subtitle, the action button on the right, a hairline between rows that starts after the icon; Google Play's "Manage apps &
//   device" has the same compact rows;
// - GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/PackageListAdapter.kt):
//   every app listed once with a single state button (Install, Update, Open) on its row, the whole row opening the app's page;
// - komi-store (Apache-2.0, https://github.com/komi-store/komi-store, feature/apps/presentation/.../components/AppItemCard.kt):
//   the icon, the name, a short description and an install button, whose body opens the details page;
// - Material 3's list anatomy (https://m3.material.io/components/lists/specs: leading image, headline, supporting text, trailing element,
//   divider inset to the text) and Compose's HorizontalDivider (https://developer.android.com/develop/ui/compose/components/divider);
//   LinearProgressIndicator for the download (https://developer.android.com/develop/ui/compose/components/progress).
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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

/** What a row does with a tap, kept together so the screen passes one object down. */
class CardActions(
    val onPrimary: (AppStatus) -> Unit,
    val onOpen: (AppStatus) -> Unit,
    val onPage: (AppStatus) -> Unit,
    val onCancel: (String) -> Unit
)

/** A list's heading, then its apps as rows in one alphabetical zone. */
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
        ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().entrancePop(seen, "list/$title")) {
            Column {
                HomeModel.sorted(apps).forEachIndexed { index, status ->
                    if (index > 0) RowDivider()
                    AppRow(
                        status,
                        HomeModel.jobOf(status, jobs),
                        actions,
                        subtitle = status.entry.description,
                        version = status.installed?.versionName ?: status.entry.versionName
                    )
                }
            }
        }
    }
}

// Where the text of a row starts: 14 dp of padding, the 56 dp icon and a 12 dp gap. The hairline between two rows starts there too.
private val TextStart = 82.dp

@Composable
internal fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = TextStart), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/**
 * One app: icon, name, a line of words, its version when given, the state button on the right; the whole row opens the app's page (a
 * transparent tap target, no second zone). [below] sits under the row, aligned with the text; [beforeButton] sits just before the button.
 */
@Composable
internal fun AppRow(
    status: AppStatus,
    job: InstallJob?,
    actions: CardActions,
    subtitle: String,
    version: String?,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    beforeButton: (@Composable () -> Unit)? = null
) {
    val active = job != null && job.step.active
    Column(
        Modifier.fillMaxWidth()
            .sinkClickable(status.entry.label, depth = 0.98f) { actions.onPage(status) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(status, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    status.entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (version != null) {
                    Text(version, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (active) {
                QuietButton(onClick = { actions.onCancel(status.entry.packageName) }) { Text(stringResource(R.string.cancel)) }
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

/** The button of an app's state at its natural width (a row's), or as [modifier] sets it (the app page's full width). */
@Composable
internal fun ActionButton(status: AppStatus, actions: CardActions, modifier: Modifier = Modifier) {
    when (val action = HomeModel.action(status)) {
        HomeModel.Action.Open -> BoldButton(onClick = { actions.onOpen(status) }, modifier = modifier) { Text(stringResource(R.string.action_open)) }
        HomeModel.Action.Update -> UpdateButton(onClick = { actions.onPrimary(status) }, label = stringResource(R.string.action_update), modifier = modifier)
        HomeModel.Action.Install -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = modifier, filled = true) { Text(stringResource(R.string.action_install)) }
        HomeModel.Action.HandOver -> BoldButton(onClick = { actions.onPrimary(status) }, modifier = modifier) { Text(stringResource(R.string.action_hand_over)) }
        is HomeModel.Action.Disabled -> BoldButton(onClick = {}, modifier = modifier.widthIn(max = 150.dp), enabled = false) {
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
            QuietButton(onClick = { actions.onCancel(status.entry.packageName) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
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
