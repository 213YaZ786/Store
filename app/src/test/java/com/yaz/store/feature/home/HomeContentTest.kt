package com.yaz.store.feature.home

// Compose UI test on the JVM through Robolectric, as Compose's testing guide has it
// (https://developer.android.com/develop/ui/compose/testing, createComposeRule and the onNodeWithText finders;
// https://robolectric.org/getting-started/ for the runner) and as SMS and Contacts draw their screens (Now in Android's
// core/screenshot-testing, https://github.com/android/nowinandroid).
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.feature.home.Fixtures.calculator
import com.yaz.store.feature.home.Fixtures.dialer
import com.yaz.store.feature.home.Fixtures.files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// The plain Application: the app's own starts Koin, which a second test would start again.
// A phone-sized screen tall enough for the cards.
@Config(sdk = [36], application = android.app.Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class HomeContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a row shows the name, the words, the version and the button of its state`() {
        val state = HomeState(loaded = true, apps = listOf(dialer, files, Fixtures.mtga, Fixtures.keyboard, Fixtures.voice, Fixtures.sms2))
        compose.setContent { HomeContent(state, onRetry = {}) }
        compose.onNodeWithText("Dialer").assertIsDisplayed()
        compose.onNodeWithText("Phone calls, history and speed dial").assertIsDisplayed()
        compose.onNodeWithText("0.14.20").assertIsDisplayed()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Install").assertIsDisplayed()
        compose.onNodeWithText("Hand over").assertIsDisplayed()
        compose.onNodeWithText("Signed by someone else").assertIsNotEnabled()
        compose.onNodeWithText("Needs Android 17").assertIsNotEnabled()
        compose.onNodeWithText("No build for this phone").assertIsNotEnabled()
    }

    @Test
    fun `the buttons act on their app, a tap on the row opens its page`() {
        val installed = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String>()
        compose.setContent {
            HomeContent(
                HomeState(loaded = true, apps = listOf(dialer, files)), onRetry = {},
                onInstall = { installed += it.entry.repo }, onOpen = { opened += it.entry.repo }, onPage = { pages += it.entry.repo }
            )
        }
        compose.onNodeWithText("Install").performClick()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Files").performClick()
        assertEquals(listOf("Files"), installed)
        assertEquals(listOf("Dialer"), opened)
        assertEquals(listOf("Files"), pages)
    }

    @Test
    fun `installed apps and available apps are two alphabetical lists with no family heading`() {
        val state = HomeState(loaded = true, apps = Fixtures.everything)
        compose.setContent { HomeContent(state, onRetry = {}) }
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("Available").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("YAZ", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("More", substring = false).fetchSemanticsNodes().size)
        val installed = HomeModel.sorted(HomeModel.installed(state.apps))
        assertEquals(listOf("Calculator", "Contacts", "Dialer", "MTGA", "Mushaf", "SMS"), installed.map { it.entry.label })
        val available = HomeModel.sorted(HomeModel.available(state.apps))
        assertEquals(listOf("Files", "Keyboard", "Voice"), available.map { it.entry.label })
    }

    @Test
    fun `the updates zone has update all with the total size, one row per app with its versions and notes`() {
        var all = 0
        val updated = mutableListOf<String>()
        compose.setContent {
            HomeContent(
                HomeState(loaded = true, apps = listOf(calculator, dialer), details = Fixtures.details), onRetry = {},
                onInstall = { updated += it.entry.repo }, onUpdateAll = { all++ }
            )
        }
        compose.onNodeWithText("Updates").assertIsDisplayed()
        compose.onNodeWithText("Update all · 4.2 MB").performClick()
        assertEquals(1, all)
        compose.onNodeWithText("0.3.5 → 0.3.6 · 4.2 MB").assertIsDisplayed()
        compose.onNodeWithText("Android will ask once").assertIsDisplayed()
        // The row's own Update (the list row below has one too).
        compose.onAllNodesWithTextCount("Update", 2)
        assertEquals(emptyList<String>(), updated)
    }

    @Test
    fun `the notes are cut to three lines and More opens them in place`() {
        compose.setContent { HomeContent(HomeState(loaded = true, apps = listOf(calculator), details = Fixtures.details), onRetry = {}) }
        compose.onNodeWithText("More").assertIsDisplayed()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Less").assertIsDisplayed()
        compose.onNodeWithText("Less").performClick()
        compose.onNodeWithText("More").assertIsDisplayed()
    }

    @Test
    fun `with no update the state says Up to date, and seven taps play the egg`() {
        compose.setContent { HomeContent(HomeState(loaded = true, apps = Fixtures.current), onRetry = {}) }
        compose.onNodeWithText("Up to date").assertIsDisplayed()
        repeat(6) { compose.onNodeWithText("Up to date").performClick() }
        compose.onNodeWithContentDescription("Store is open").assertDoesNotExist()
        // The clock is held, or the egg would play to its end before it is looked for (Compose testing: mainClock.autoAdvance).
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Up to date").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Store is open").assertExists()
        // It leaves by itself.
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithContentDescription("Store is open").assertDoesNotExist()
    }

    @Test
    fun `an update or a hand over means the app is not up to date`() {
        assertEquals(true, HomeModel.allCurrent(Fixtures.current))
        assertEquals(false, HomeModel.allCurrent(Fixtures.current + calculator))
        assertEquals(false, HomeModel.allCurrent(Fixtures.current + Fixtures.sms2))
        assertEquals(false, HomeModel.allCurrent(listOf(files)))
    }

    @Test
    fun `an empty list says so, and an error offers a retry`() {
        var state by mutableStateOf(HomeState(loaded = true))
        var retried = 0
        compose.setContent { HomeContent(state, onRetry = { retried++ }) }
        compose.onNodeWithText("No app to list yet.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        state = HomeState(loaded = true, error = CatalogError.Offline)
        compose.onNodeWithText("No connection").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(2, retried)
        state = HomeState(loaded = true, error = CatalogError.RateLimited(null))
        compose.onNodeWithText("GitHub's limit is reached").assertIsDisplayed()
        compose.onNodeWithText("Try again later.").assertIsDisplayed()
    }

    @Test
    fun `the kept list shows a banner with a retry and says the repos come from the last visit`() {
        var retried = 0
        val state = HomeState(loaded = true, apps = Fixtures.current, error = CatalogError.RateLimited(null), listCached = true)
        compose.setContent { HomeContent(state, onRetry = { retried++ }) }
        compose.onNodeWithText("GitHub's limit is reached").assertIsDisplayed()
        compose.onNodeWithText("Try again later. Showing the list from the last visit.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry").performClick()
        assertEquals(1, retried)
        compose.onNodeWithText("Dialer").assertIsDisplayed()
    }

    @Test
    fun `a queued entry shows its step and a cancel, a failed one its reason`() {
        var cancelled = ""
        var all = 0
        val running = InstallJob(calculator.entry, handOver = false, serial = 1, step = JobStep.DOWNLOADING, progress = 0.42f)
        var state by mutableStateOf(HomeState(loaded = true, apps = listOf(calculator, dialer), jobs = listOf(running), details = Fixtures.details))
        compose.setContent { HomeContent(state, onRetry = {}, onCancel = { cancelled = it }, onCancelAll = { all++ }) }
        compose.onAllNodesWithTextCount("Downloading 42%", 2)
        compose.onNodeWithText("Cancel all").performClick()
        assertEquals(1, all)
        state = HomeState(loaded = true, apps = listOf(calculator, dialer), jobs = listOf(running.copy(step = JobStep.FAILED, why = "No connection.")))
        compose.onAllNodesWithTextCount("Failed: No connection.", 2)
        assertEquals("", cancelled)
    }

    @Test
    fun `an entry whose app is not in the list keeps its own row with a cancel, even when the list is empty`() {
        var cancelled = ""
        val job = InstallJob(calculator.entry, handOver = false, serial = 1, step = JobStep.CHECKING)
        compose.setContent { HomeContent(HomeState(loaded = true, jobs = listOf(job)), onRetry = {}, onCancel = { cancelled = it }) }
        compose.onNodeWithText("Calculator").assertIsDisplayed()
        compose.onNodeWithText("Checking").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals("com.yaz.calculator", cancelled)
    }

    @Test
    fun `every step of the queue has its words`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        fun words(step: JobStep, progress: Float = 0f, why: String? = null) = jobText(context, InstallJob(calculator.entry, false, 1, step, progress, why))
        assertEquals("Waiting", words(JobStep.QUEUED))
        assertEquals("Downloading 0%", words(JobStep.DOWNLOADING))
        assertEquals("Downloading 100%", words(JobStep.DOWNLOADING, 1.7f))
        assertEquals("Checking", words(JobStep.CHECKING))
        assertEquals("Installing", words(JobStep.INSTALLING))
        assertEquals("Waiting for your confirmation", words(JobStep.CONFIRM))
        assertEquals("Installed", words(JobStep.DONE))
        assertEquals("Failed: The file is another version than the release says.", words(JobStep.FAILED, why = "The file is another version than the release says."))
    }

    @Test
    fun `android names its levels as people do`() {
        assertEquals("12", HomeModel.androidName(31))
        assertEquals("16", HomeModel.androidName(36))
        assertEquals("API 99", HomeModel.androidName(99))
        assertEquals(AppState.UPDATE, calculator.state)
    }

    // The nodes carrying [text], counted: an updates row and a list row can both carry the same word.
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, atLeast: Int) {
        val found = onAllNodesWithText(text, substring = false).fetchSemanticsNodes().size
        assertEquals("nodes with \"$text\": $found", true, found >= atLeast)
    }
}
