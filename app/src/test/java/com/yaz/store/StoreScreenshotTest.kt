package com.yaz.store

// The screens as drawn on a phone and a tablet, by day and by night, compared with the pictures kept in src/test/screenshots: a block moved
// or cut in two fails the build. Taken from SMS's PersonPageScreenshotTest (YAZ/SMS, feature/thread), itself Now in Android's captureForDevice
// (https://github.com/android/nowinandroid, core/screenshot-testing/.../ScreenshotHelper.kt): the screen's size set through Robolectric's
// qualifiers, inspection mode on, one picture each (Roborazzi, https://github.com/takahirom/roborazzi).
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.data.settings.Settings
import com.yaz.store.feature.app.AppPageContent
import com.yaz.store.feature.home.CardActions
import com.yaz.store.feature.home.Fixtures
import com.yaz.store.feature.home.HomeContent
import com.yaz.store.feature.home.HomeState
import com.yaz.store.feature.settings.SettingsContent
import com.yaz.store.feature.setup.SetupContent
import com.yaz.store.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own starts Koin, which a second test would start again.
@Config(application = android.app.Application::class, sdk = [36])
class StoreScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Now in Android's DefaultTestDevices, phone and tablet.
    private val devices = listOf(
        "phone" to "w411dp-h914dp-420dpi",
        "tablet" to "w1280dp-h800dp-320dpi"
    )

    private val options = RoborazziOptions(
        // Pixel-perfect, as Now in Android's DefaultRoborazziOptions: a word changed is a change.
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    // As captureForDevice: the qualifiers first, then the content set on the activity again.
    private fun capture(name: String, body: @Composable () -> Unit) {
        devices.forEach { (device, qualifiers) ->
            RuntimeEnvironment.setQualifiers(qualifiers)
            listOf(false, true).forEach { night ->
                compose.activity.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        AppTheme(darkTheme = night, dynamicColor = false) {
                            // As AppSurface gives the app's windows: text takes the theme's colour, or it falls to black at night.
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { body() }
                            }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onRoot().captureRoboImage(
                    "src/test/screenshots/${name}_${device}_${if (night) "dark" else "light"}.png",
                    roborazziOptions = options
                )
            }
        }
    }

    @Test
    fun homeWithUpdates() = capture("home_updates") {
        HomeContent(HomeState(loaded = true, apps = Fixtures.everything, details = Fixtures.details), onRetry = {})
    }

    @Test
    fun homeUpToDate() = capture("home_current") {
        HomeContent(HomeState(loaded = true, apps = Fixtures.current + Fixtures.keyboard), onRetry = {})
    }

    @Test
    fun homeDownloading() = capture("home_downloading") {
        val running = InstallJob(Fixtures.calculator.entry, handOver = false, serial = 1, step = JobStep.DOWNLOADING, progress = 0.42f)
        HomeContent(HomeState(loaded = true, apps = Fixtures.everything, details = Fixtures.details, jobs = listOf(running)), onRetry = {})
    }

    @Test
    fun homeOffline() = capture("home_offline") {
        HomeContent(
            HomeState(loaded = true, apps = Fixtures.current, error = CatalogError.RateLimited(1_791_000_000L), listCached = true),
            onRetry = {}
        )
    }

    @Test
    fun homeEmpty() = capture("home_empty") {
        HomeContent(HomeState(loaded = true, error = CatalogError.Offline), onRetry = {})
    }

    private val actions = CardActions({}, {}, {}, {})

    @Test
    fun pageUpdate() = capture("page_update") {
        AppPageContent(Fixtures.calculator, null, Fixtures.notes, actions, onBack = {})
    }

    @Test
    fun pageUpToDate() = capture("page_current") {
        AppPageContent(Fixtures.dialer, null, null, actions, onBack = {})
    }

    @Test
    fun pageNotInstalled() = capture("page_install") {
        AppPageContent(Fixtures.files, null, Fixtures.notes, actions, onBack = {})
    }

    @Test
    fun settings() = capture("settings") {
        SettingsContent(Settings(), onBack = {})
    }

    @Test
    fun settingsOn() = capture("settings_on") {
        SettingsContent(Settings(backgroundCheck = true, wifiOnly = true, pureBlack = true), onBack = {})
    }

    @Test
    fun setupStart() = capture("setup_start") {
        SetupContent(canInstall = false, notifying = false)
    }

    @Test
    fun setupDone() = capture("setup_done") {
        SetupContent(canInstall = true, notifying = true)
    }
}
