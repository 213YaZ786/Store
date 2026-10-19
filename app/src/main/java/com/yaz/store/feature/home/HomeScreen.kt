package com.yaz.store.feature.home

// Home: the Updates zone when some app waits, then Installed and Available. Sources:
// - the frame and its floating glass panes are Files' (YAZ/Files at HEAD, feature/browse/BrowseScreen.kt): FloatingFrame with FloatingTop,
//   the content running under them, and its PullToRefreshBox with the PullIndicator (Material 3,
//   https://developer.android.com/develop/ui/compose/components/pull-to-refresh);
// - the lists: GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/MainScreen.kt)
//   lists every app with one state button; Google Play's updates zone sits on top (HomeUpdates.kt); two columns side by side from the
//   expanded width class (this app's navigation/WindowWidth.kt, Material's window size classes,
//   https://developer.android.com/develop/adaptive/large-screens/support-different-screen-sizes), one scroll under the other below it;
// - the state is hoisted so a test draws HomeContent with fixed data (https://developer.android.com/develop/ui/compose/state-hoisting);
// - the notification permission is asked at the first Install or Update tap with Compose's permission launcher
//   (https://developer.android.com/training/permissions/requesting, ActivityResultContracts.RequestPermission) on Android 13 and later;
// - Android's confirmation screen, when it asks (an app Store does not hold yet), is started by the screen that is in front, as AOSP's ApiDemos
//   InstallApkSessionApi starts EXTRA_INTENT from its activity
//   (https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/content/InstallApkSessionApi.java).
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.yaz.store.R
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.IconStore
import com.yaz.store.data.catalog.OwnUpdate
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.feature.eggs.StoreEgg
import com.yaz.store.navigation.WidthClass
import com.yaz.store.ui.component.BannerAction
import com.yaz.store.ui.component.EmptyZone
import com.yaz.store.ui.component.FloatingAction
import com.yaz.store.ui.component.FloatingFrame
import com.yaz.store.ui.component.FloatingTop
import com.yaz.store.ui.component.LoadingMark
import com.yaz.store.ui.component.PullIndicator
import com.yaz.store.ui.component.QuietButton
import com.yaz.store.ui.component.ScreenBanner
import com.yaz.store.ui.component.TitlePill
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.navigationBarBottom
import com.yaz.store.ui.component.rememberRefreshHaptics
import com.yaz.store.ui.icon.AppIcons
import org.koin.compose.koinInject

@Composable
fun HomeScreen(model: HomeViewModel, onSettings: () -> Unit, onPage: (String) -> Unit) {
    val settings: SettingsStore = koinInject()
    val icons: IconStore = koinInject()
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val afterTap = notificationAsker(context, settings)
    ConfirmWhileInFront(state.jobs)

    WithIcons(remember { PhoneIcons(context, icons) }) {
        HomeContent(
            state,
            onRetry = model::load,
            onInstall = { if (model.install(it)) afterTap() },
            onUpdateAll = { if (model.updateAll() > 0) afterTap() },
            onCancel = model::cancel,
            onCancelAll = model::cancelAll,
            onOpen = { openApp(context, it) },
            onPage = { onPage(it.entry.packageName) },
            onSettings = onSettings,
            onOwnUpdate = { if (model.updateSelf()) afterTap() },
            onOwnLater = model::later,
            seen = model.popped
        )
    }
}

/**
 * The first Install or Update tap asks for the notification permission, once; the install goes on whatever the answer. The function it
 * returns is called after the tap.
 */
@Composable
fun notificationAsker(context: Context, settings: SettingsStore): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.current.notificationsAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.update { it.copy(notificationsAsked = true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Android's confirmation screen, once per entry, while this screen is in front. */
@Composable
fun ConfirmWhileInFront(jobs: List<InstallJob>) {
    val context = LocalContext.current
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = remember { mutableSetOf<Long>() }
    LaunchedEffect(jobs, resumed) {
        if (!resumed.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        jobs.filter { it.step == JobStep.CONFIRM && it.confirm != null && started.add(it.serial) }
            .forEach { job -> runCatching { context.startActivity(job.confirm) } }
    }
}

/** Starts the app's launcher activity (PackageManager.getLaunchIntentForPackage, https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)). */
fun openApp(context: Context, status: AppStatus) {
    context.packageManager.getLaunchIntentForPackage(status.entry.packageName)?.let { runCatching { context.startActivity(it) } }
}

/** The screen for a state: stateless, so a test can draw it with fixed data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeState,
    onRetry: () -> Unit,
    onInstall: (AppStatus) -> Unit = {},
    onUpdateAll: () -> Unit = {},
    onCancel: (String) -> Unit = {},
    onCancelAll: () -> Unit = {},
    onOpen: (AppStatus) -> Unit = {},
    onPage: (AppStatus) -> Unit = {},
    onSettings: () -> Unit = {},
    onOwnUpdate: () -> Unit = {},
    onOwnLater: () -> Unit = {},
    seen: MutableSet<String> = remember { mutableSetOf() }
) {
    val context = LocalContext.current
    // Store's own package is in no list: its queue entry, if any, belongs to the update dialog alone (OwnUpdate).
    val ownJob = state.jobs.firstOrNull { OwnUpdate.isOwn(it.entry.packageName) }
    val listState = if (ownJob == null) state else state.copy(jobs = state.jobs - ownJob)
    OwnUpdateHost(state, ownJob, onOwnUpdate, onOwnLater, onCancel)
    var egg by remember { mutableStateOf<Offset?>(null) }
    val askedRefresh = rememberRefreshHaptics(state.loading, state.error != null)
    val pull = rememberPullToRefreshState()
    val actions = remember(onInstall, onOpen, onPage, onCancel) { CardActions(onInstall, onOpen, onPage, onCancel) }
    // An entry of the queue whose app is not in the list read from GitHub (the list changed under it) keeps its row until it ends.
    val orphans = listState.jobs.filter { job -> state.apps.none { it.entry.packageName == job.entry.packageName } }
    val empty = state.apps.isEmpty() && orphans.isEmpty()
    val error = state.error

    FloatingFrame(
        bottom = navigationBarBottom(),
        top = {
            FloatingTop(
                title = null,
                // The title and the state of the apps on one line (the status pill, HomeUpdates.kt).
                center = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TitlePill(stringResource(R.string.home_title))
                        StatusPill(HomeModel.updates(state.apps).size, HomeModel.allCurrent(state.apps), { egg = it }, seen)
                    }
                },
                trailing = { FloatingAction(AppIcons.Settings, stringResource(R.string.settings), onSettings) }
            )
        },
        overlay = { egg?.let { StoreEgg(it) { egg = null } } }
    ) { padding ->
        when {
            empty && !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 56.dp) }
            empty -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentAlignment = Alignment.Center) {
                EmptyZone(
                    title = if (error != null) errorTitle(context, error) else stringResource(R.string.empty_title),
                    message = if (error != null) errorDetail(context, error, state.listCached).orEmpty() else stringResource(R.string.empty_none),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry
                )
            }
            else -> PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = {
                    askedRefresh()
                    onRetry()
                },
                modifier = Modifier.fillMaxSize(),
                state = pull,
                // Under the floating panes, where the list starts, not behind them.
                indicator = {
                    PullIndicator(
                        state = pull,
                        isRefreshing = state.loading,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding())
                    )
                }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    BoxWithConstraints(Modifier.widthIn(max = 1100.dp).fillMaxSize()) {
                        val wide = WidthClass.of(maxWidth) == WidthClass.EXPANDED
                        HomeList(listState, orphans, padding, wide, actions, onUpdateAll, onCancelAll, onRetry, seen)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeList(
    state: HomeState,
    orphans: List<InstallJob>,
    padding: PaddingValues,
    wide: Boolean,
    actions: CardActions,
    onUpdateAll: () -> Unit,
    onCancelAll: () -> Unit,
    onRetry: () -> Unit,
    seen: MutableSet<String>
) {
    val context = LocalContext.current
    val error = state.error
    val updates = HomeModel.updates(state.apps)
    val installed = HomeModel.installed(state.apps)
    val available = HomeModel.available(state.apps)
    // The banner and every zone keep 12 dp of their own on the side (ScreenBanner), the lists the same.
    val side = Modifier.padding(horizontal = 12.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (error != null) {
            item("error") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScreenBanner(
                        title = errorTitle(context, error),
                        trailing = { BannerAction(AppIcons.Refresh, stringResource(R.string.retry), onRetry) }
                    )
                    errorDetail(context, error, state.listCached)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = side)
                    }
                }
            }
        }
        items(orphans, key = { "job-${it.serial}" }) { OrphanRow(it, actions.onCancel, side) }
        if (updates.isNotEmpty()) {
            item("updates") {
                UpdatesZone(updates, state.jobs, state.details, actions, onUpdateAll, onCancelAll, seen, side)
            }
        }
        item("lists") {
            val both = installed.isNotEmpty() && available.isNotEmpty()
            if (wide && both) {
                Row(side.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppList(R.string.installed_title, installed, state.jobs, actions, seen, Modifier.weight(1f))
                    AppList(R.string.available_title, available, state.jobs, actions, seen, Modifier.weight(1f))
                }
            } else {
                Column(side.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (installed.isNotEmpty()) AppList(R.string.installed_title, installed, state.jobs, actions, seen)
                    if (available.isNotEmpty()) AppList(R.string.available_title, available, state.jobs, actions, seen)
                }
            }
        }
    }
}

// The row of a queue entry with no app in the list: its name, package and step, and Cancel while it runs.
@Composable
private fun OrphanRow(job: InstallJob, onCancel: (String) -> Unit, modifier: Modifier) {
    ZoneSurface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job.entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(job.entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Progress(job)
            if (job.step.active) QuietButton(onClick = { onCancel(job.entry.packageName) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}
