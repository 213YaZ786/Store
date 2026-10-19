package com.yaz.store.navigation

// Ported from Files (YAZ/Files at HEAD, navigation/FilesApp.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, navigation/CalculatorApp.kt):
// the welcome gate, the NavHost and PlainBack (the base kit's). Store's routes: the setup guide (first start only), Home, an app's page,
// Settings. The ViewModel of Home belongs to the activity (androidx.lifecycle.viewmodel.compose.viewModel with the activity as owner,
// https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-factories), so the list and the queue are read once for every
// screen. A route with an argument is Navigation Compose's own recipe (https://developer.android.com/guide/navigation/design#compose).
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.install.InstallQueue
import com.yaz.store.data.settings.SettingsStore
import com.yaz.store.feature.app.AppPageScreen
import com.yaz.store.feature.home.HomeScreen
import com.yaz.store.feature.home.HomeViewModel
import com.yaz.store.feature.settings.SettingsScreen
import com.yaz.store.feature.setup.SetupScreen
import org.koin.compose.koinInject

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val APP = "app/{package}"
    fun app(packageName: String) = "app/" + android.net.Uri.encode(packageName)
}

// Files' FilesApp.
@Composable
fun StoreApp() {
    val nav = rememberNavController()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val context = LocalContext.current
    val catalog: Catalog = koinInject()
    val queue: InstallQueue = koinInject()
    val model: HomeViewModel = viewModel(context as ComponentActivity) { HomeViewModel(context.applicationContext as Application, catalog, queue) }
    // Each time the app comes to the front: the account and the phone are read again (an unchanged answer comes from the ETag cache).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.load() }

    NavHost(nav, startDestination = if (settings.setupDone) Routes.HOME else Routes.SETUP) {
        composable(Routes.SETUP) {
            SetupScreen(model, onFinish = { nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } } })
        }
        composable(Routes.HOME) {
            HomeScreen(model, onSettings = { nav.navigate(Routes.SETTINGS) }, onPage = { nav.navigate(Routes.app(it)) })
        }
        composable(Routes.APP, arguments = listOf(navArgument("package") { type = NavType.StringType })) { entry ->
            AppPageScreen(model, entry.arguments?.getString("package").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
    PlainBack(nav)
}
