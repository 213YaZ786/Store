package com.yaz.store.feature.home

// Temporary Home: a plain list of the catalog with Install, Update, Update all and Cancel and the state of each entry of the install
// queue, replaced by the real screens later. Compose's LazyColumn and LifecycleEventEffect
// (https://developer.android.com/develop/ui/compose/lists, https://developer.android.com/develop/ui/compose/lifecycle), the base kit's
// ScreenBanner, ZoneSurface, EmptyZone, LoadingMark and BoldButton/QuietButton.
// - The notification permission is asked at the first Install or Update tap with Compose's permission launcher
//   (https://developer.android.com/training/permissions/requesting, ActivityResultContracts.RequestPermission) on Android 13 and later
//   (https://developer.android.com/develop/ui/views/notifications/notification-permission); the queue is already running, a refusal changes nothing.
// - Android's confirmation screen, when it asks (an app Store does not hold yet), is started by the screen that is in front, as AOSP's ApiDemos
//   InstallApkSessionApi starts EXTRA_INTENT from its activity
//   (https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/content/InstallApkSessionApi.java).
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.InstallQueue
import com.yaz.store.data.install.JobStep
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.EmptyZone
import com.yaz.store.ui.component.LoadingMark
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ScreenBanner
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.navigationBarBottom
import com.yaz.store.ui.component.statusBarTop
import org.koin.compose.koinInject
import java.util.Date

@Composable
fun HomeScreen() {
    val catalog: Catalog = koinInject()
    val queue: InstallQueue = koinInject()
    val settings: SettingsStore = koinInject()
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val model: HomeViewModel = viewModel { HomeViewModel(app, catalog, queue) }
    val state by model.state.collectAsStateWithLifecycle()
    // Each time the app comes to the front: the account and the phone are read again (an unchanged answer comes from the ETag cache).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.load() }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askedBefore = { settings.current.notificationsAsked }
    // The first Install or Update tap asks for the notification permission, once; the install goes on whatever the answer.
    val afterTap = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedBefore() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.update { it.copy(notificationsAsked = true) }
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Android's confirmation screen, once per entry, while this screen is in front.
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = remember { mutableSetOf<Long>() }
    LaunchedEffect(state.jobs, resumed) {
        if (!resumed.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        state.jobs.filter { it.step == JobStep.CONFIRM && it.confirm != null && started.add(it.serial) }
            .forEach { job -> runCatching { context.startActivity(job.confirm) } }
    }

    HomeContent(
        state,
        onRetry = model::load,
        onInstall = { if (model.install(it)) afterTap() },
        onUpdateAll = { if (model.updateAll() > 0) afterTap() },
        onCancel = model::cancel,
        onCancelAll = model::cancelAll
    )
}

/** The list for a state: stateless, so a test can draw it with fixed data (Compose's state hoisting, https://developer.android.com/develop/ui/compose/state-hoisting). */
@Composable
fun HomeContent(
    state: HomeState,
    onRetry: () -> Unit,
    onInstall: (AppStatus) -> Unit = {},
    onUpdateAll: () -> Unit = {},
    onCancel: (String) -> Unit = {},
    onCancelAll: () -> Unit = {}
) {
    val context = LocalContext.current
    val error = state.error
    // An entry of the queue whose app is not in the list read from GitHub (the list changed under it) keeps its row until it ends.
    val orphans = state.jobs.filter { job -> state.apps.none { it.entry.packageName == job.entry.packageName } }
    val empty = state.apps.isEmpty() && orphans.isEmpty()
    when {
        empty && !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 56.dp) }
        empty && error != null ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyZone(title = "Store", message = errorLine(state, error, context), actionLabel = "Retry", onAction = onRetry)
            }
        empty ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyZone(title = "Store", message = "No app to list yet.") }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = statusBarTop(), bottom = navigationBarBottom() + 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { ScreenBanner(title = "Store") }
            if (error != null) item { Text(errorLine(state, error, context), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) }
            val waiting = state.apps.count { app -> app.state == AppState.UPDATE && state.jobs.none { it.entry.packageName == app.entry.packageName && it.step.active } }
            val running = state.jobs.count { it.step.active }
            if (waiting > 0 || running > 0) item { AllRow(waiting, running, onUpdateAll, onCancelAll) }
            items(orphans, key = { "job-${it.serial}" }) { JobRow(it, onCancel) }
            items(state.apps, key = { it.entry.repo }) { status ->
                AppRow(status, state.details[status.entry.repo], state.jobs.firstOrNull { it.entry.packageName == status.entry.packageName }, onInstall, onCancel)
            }
        }
    }
}

// Update all and Cancel all share the row, each over the whole width when alone.
@Composable
private fun AllRow(waiting: Int, running: Int, onUpdateAll: () -> Unit, onCancelAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (waiting > 0) BoldButton(onClick = onUpdateAll, modifier = Modifier.weight(1f), filled = true) { Text("Update all ($waiting)") }
        if (running > 0) QuietButton(onClick = onCancelAll, modifier = Modifier.weight(1f)) { Text("Cancel all") }
    }
}

// The row of a queue entry with no app in the list: AppRow's own name, package and step lines and Cancel (this file), a Compose Column in a
// ZoneSurface (https://developer.android.com/develop/ui/compose/layouts/basics).
@Composable
private fun JobRow(job: InstallJob, onCancel: (String) -> Unit) {
    ZoneSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(job.entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(job.entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(jobText(job), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (job.step.active) QuietButton(onClick = { onCancel(job.entry.packageName) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun AppRow(status: AppStatus, details: ReleaseDetails?, job: InstallJob?, onInstall: (AppStatus) -> Unit, onCancel: (String) -> Unit) {
    ZoneSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(status.entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status.entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stateText(status.state), style = MaterialTheme.typography.bodyMedium)
            if (job != null) {
                Text(jobText(job), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Installed ${status.installed?.versionName ?: "-"}, offered ${status.entry.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.handOver) Text("A confirmation will show once.", style = MaterialTheme.typography.bodySmall)
            details?.notes?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val label = actionLabel(status.state)
            if (job?.step?.active == true) {
                QuietButton(onClick = { onCancel(status.entry.packageName) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            } else if (label != null) {
                BoldButton(onClick = { onInstall(status) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

/** The button of a state: nothing for an app that is up to date, foreign, too new or without an APK. */
internal fun actionLabel(state: AppState): String? = when (state) {
    AppState.NOT_INSTALLED -> "Install"
    AppState.UPDATE -> "Update"
    AppState.HAND_OVER -> "Hand over"
    else -> null
}

/** What an entry of the queue is doing, in words. */
internal fun jobText(job: InstallJob): String = when (job.step) {
    JobStep.QUEUED -> "Waiting"
    JobStep.DOWNLOADING -> "Downloading ${(job.progress * 100).toInt().coerceIn(0, 100)}%"
    JobStep.CHECKING -> "Checking"
    JobStep.INSTALLING -> "Installing"
    JobStep.CONFIRM -> "Waiting for your confirmation"
    JobStep.DONE -> "Installed"
    JobStep.FAILED -> "Failed: ${job.why ?: "The installation could not be done."}"
}

private fun stateText(state: AppState): String = when (state) {
    AppState.NOT_INSTALLED -> "Not installed"
    AppState.UP_TO_DATE -> "Up to date"
    AppState.UPDATE -> "Update available"
    AppState.HAND_OVER -> "Installed elsewhere, to hand over"
    AppState.FOREIGN -> "Signed by another key, left alone"
    AppState.TOO_NEW -> "Needs a newer Android"
    AppState.NO_APK -> "No APK for this phone"
}

// The error, and when the repos came from the list kept at the last opening, a word saying so
// (a plain string template, https://kotlinlang.org/docs/strings.html#string-templates).
private fun errorLine(state: HomeState, error: CatalogError, context: Context): String =
    errorText(error, context) + if (state.listCached) " Repos from the last visit." else ""

private fun errorText(error: CatalogError, context: Context): String = when (error) {
    CatalogError.Offline -> "No connection."
    is CatalogError.RateLimited -> error.resetAt?.let {
        "GitHub's limit is reached. Try again at ${DateFormat.getTimeFormat(context).format(Date(it * 1000))}."
    } ?: "GitHub's limit is reached. Try again later."
    CatalogError.Refused -> "GitHub's answer was refused."
}
