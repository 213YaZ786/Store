package com.yaz.store.feature.app

// The wiring of the app page: the activity's HomeViewModel (the list and the queue are read once for every screen, as Files' screens share
// their ViewModels through Koin, https://insert-koin.io/docs/reference/koin-compose/compose) and the same icons and taps as Home. When the
// app is no longer in the list (it left the account, or the list was read again without it) the page closes itself.
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaz.store.R
import com.yaz.store.data.catalog.IconStore
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.feature.home.CardActions
import com.yaz.store.feature.home.ConfirmWhileInFront
import com.yaz.store.feature.home.HomeModel
import com.yaz.store.feature.home.HomeViewModel
import com.yaz.store.feature.home.PhoneIcons
import com.yaz.store.feature.home.WithIcons
import com.yaz.store.feature.home.detailsKey
import com.yaz.store.feature.home.notificationAsker
import com.yaz.store.feature.home.openApp
import org.koin.compose.koinInject

@Composable
fun AppPageScreen(model: HomeViewModel, packageName: String, onBack: () -> Unit) {
    val settings: SettingsStore = koinInject()
    val icons: IconStore = koinInject()
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val status = state.apps.firstOrNull { it.entry.packageName == packageName }
    val afterTap = notificationAsker(context, settings)
    ConfirmWhileInFront(state.jobs)
    LaunchedEffect(status?.entry?.detailsKey) { status?.let { model.ensureDetails(it.entry) } }
    LaunchedEffect(status == null && state.loaded) { if (status == null && state.loaded) onBack() }
    if (status == null) return
    val actions = remember(model) {
        CardActions(
            onPrimary = { if (model.install(it)) afterTap() },
            onOpen = { openApp(context, it) },
            onPage = {},
            onCancel = model::cancel,
            onInfo = { runCatching { context.startActivity(appDetailsIntent(it.entry.packageName)) } }
        )
    }
    val opening by icons.generation.collectAsStateWithLifecycle()
    WithIcons(remember { PhoneIcons(context, icons) }, opening) {
        AppPageContent(
            status = status,
            job = HomeModel.jobOf(status, state.jobs),
            details = state.details[status.entry.detailsKey],
            actions = actions,
            onBack = onBack,
            onUninstall = { uninstall(context, it) },
            onSource = { openSource(context, it) },
            seen = model.popped
        )
    }
}

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/util/PackageManagerUtils.kt#appDetailsIntent
fun appDetailsIntent(pkgName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkgName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** PackageState.Status of the source, the ones Store has: an app not installed, disabled, out of date, being installed, up to date. */
internal enum class Status { NOT_INSTALLED, DISABLED, OUT_OF_DATE, INSTALLING, UP_TO_DATE }

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/ui/DetailsScreen.kt#setupButtons
// The secondary buttons of the page for its status, as texts and actions: App info (the source's "Settings") for an app that is out of date or up to
// date, none for one not installed or disabled (its primary button, Enable, opens the same page); Open and Uninstall beside it, as Google Play's page has
// them. The primary button is StateButton, drawn by AppPageContent, which wraps and balances these in a BalancedFlow.
internal fun setupButtons(
    status: Status,
    selfPkgName: Boolean,
    open: () -> Unit,
    appInfo: () -> Unit,
    uninstall: () -> Unit
): List<Pair<Int, () -> Unit>> {
    val secondary = ArrayList<Pair<Int, () -> Unit>>()
    fun setAction(text: Int, action: () -> Unit) {
        secondary.add(text to action)
    }
    // pkgName != selfPkgName: Store has no Open (it is open) and does not uninstall itself.
    if (status == Status.UP_TO_DATE && !selfPkgName) setAction(R.string.action_open, open)
    when (status) {
        Status.NOT_INSTALLED,
        Status.DISABLED -> Unit
        Status.OUT_OF_DATE,
        Status.UP_TO_DATE -> setAction(R.string.action_app_info, appInfo)
        else -> {}
    }
    if ((status == Status.OUT_OF_DATE || status == Status.UP_TO_DATE || status == Status.DISABLED) && !selfPkgName) setAction(R.string.action_uninstall, uninstall)
    return secondary
}

// not ported: setAction's View Button (its text, listener and visibility; here setAction adds one secondary button to the list)
// not ported: prevStatus (the early return that spares re-binding the Views; Compose skips what did not change)
// not ported: Status.SHARED_LIBRARY (Store lists no shared library)
// not ported: the INSTALLING secondary Cancel with `task.jobReferenceForMainThread.isCancelled` (StateButton's Cancel, the queue's cancel by package)
// not ported: the primary buttons of the source (Install, Update, Enable, Open, startPackageInstallFromUi): StateButton and ActionButton draw them in AppPageContent
