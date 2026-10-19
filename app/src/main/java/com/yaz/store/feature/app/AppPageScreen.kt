package com.yaz.store.feature.app

// The wiring of the app page: the activity's HomeViewModel (the list and the queue are read once for every screen, as Files' screens share
// their ViewModels through Koin, https://insert-koin.io/docs/reference/koin-compose/compose) and the same icons and taps as Home. When the
// app is no longer in the list (it left the account, or the list was read again without it) the page closes itself.
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            onCancel = model::cancel
        )
    }
    WithIcons(remember { PhoneIcons(context, icons) }) {
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
