package com.yaz.store.feature.home

// Temporary Home: a plain list of the catalog, replaced by the real screens later. Compose's LazyColumn and
// LifecycleEventEffect (https://developer.android.com/develop/ui/compose/lists,
// https://developer.android.com/develop/ui/compose/lifecycle), the base kit's ScreenBanner, ZoneSurface, EmptyZone and LoadingMark.
import android.app.Application
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.ui.component.EmptyZone
import com.yaz.store.ui.component.LoadingMark
import com.yaz.store.ui.component.ScreenBanner
import com.yaz.store.ui.component.ZoneSurface
import com.yaz.store.ui.component.navigationBarBottom
import com.yaz.store.ui.component.statusBarTop
import org.koin.compose.koinInject
import java.util.Date

@Composable
fun HomeScreen() {
    val catalog: Catalog = koinInject()
    val app = LocalContext.current.applicationContext as Application
    val model: HomeViewModel = viewModel { HomeViewModel(app, catalog) }
    val state by model.state.collectAsStateWithLifecycle()
    // Each time the app comes to the front: the account and the phone are read again (an unchanged answer comes from the ETag cache).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.load() }
    HomeContent(state, onRetry = model::load)
}

/** The list for a state: stateless, so a test can draw it with fixed data (Compose's state hoisting, https://developer.android.com/develop/ui/compose/state-hoisting). */
@Composable
fun HomeContent(state: HomeState, onRetry: () -> Unit) {
    val context = LocalContext.current
    val error = state.error
    when {
        state.apps.isEmpty() && !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 56.dp) }
        state.apps.isEmpty() && error != null ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyZone(title = "Store", message = errorLine(state, error, context), actionLabel = "Retry", onAction = onRetry)
            }
        state.apps.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyZone(title = "Store", message = "No app to list yet.") }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = statusBarTop(), bottom = navigationBarBottom() + 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { ScreenBanner(title = "Store") }
            if (error != null) item { Text(errorLine(state, error, context), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) }
            items(state.apps, key = { it.entry.repo }) { AppRow(it, state.details[it.entry.repo]) }
        }
    }
}

@Composable
private fun AppRow(status: AppStatus, details: ReleaseDetails?) {
    ZoneSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(status.entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status.entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stateText(status.state), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Installed ${status.installed?.versionName ?: "-"}, offered ${status.entry.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.handOver) Text("A confirmation will show once.", style = MaterialTheme.typography.bodySmall)
            details?.notes?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
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
