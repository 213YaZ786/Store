package com.yaz.store.feature.home

// Compose UI test on the JVM through Robolectric, as Compose's testing guide has it
// (https://developer.android.com/develop/ui/compose/testing, createComposeRule and the onNodeWithText finders;
// https://robolectric.org/getting-started/ for the runner) and as SMS and Contacts draw their screens (Now in Android's
// core/screenshot-testing, https://github.com/android/nowinandroid).
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.catalog.InstalledFacts
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// The plain Application: the app's own starts Koin, which a second test would start again.
// A phone-sized screen tall enough for the three cards.
@Config(sdk = [36], application = android.app.Application::class, qualifiers = "w411dp-h1200dp-xxhdpi")
class HomeContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun status(repo: String, label: String, state: AppState, installed: String?, offered: String, handOver: Boolean = false) = AppStatus(
        StoreEntry(
            repo = repo, tag = "v$offered", packageName = "com.yaz.$repo", label = label, description = "", versionCode = 2, versionName = offered,
            minSdk = 31, apkName = "$repo.apk", iconUrl = null
        ),
        state,
        installed?.let { InstalledFacts(1, it, emptySet(), null, null) },
        handOver
    )

    @Test
    fun `each app shows its label, package, state and both versions`() {
        val state = HomeState(
            loaded = true,
            apps = listOf(
                status("calculator", "Calculator", AppState.UPDATE, "0.3.5", "0.3.6"),
                status("dialer", "Dialer", AppState.NOT_INSTALLED, null, "0.14.20"),
                status("mtga", "MTGA", AppState.FOREIGN, "2.15.15", "2.15.15")
            )
        )
        compose.setContent { HomeContent(state, onRetry = {}) }
        compose.onNodeWithText("Calculator").assertIsDisplayed()
        compose.onNodeWithText("com.yaz.calculator").assertIsDisplayed()
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Installed 0.3.5, offered 0.3.6").assertIsDisplayed()
        compose.onNodeWithText("Not installed").assertIsDisplayed()
        compose.onNodeWithText("Installed -, offered 0.14.20").assertIsDisplayed()
        compose.onNodeWithText("Signed by another key, left alone").assertIsDisplayed()
    }

    @Test
    fun `an empty list says so, and an error offers a retry`() {
        var state by mutableStateOf(HomeState(loaded = true))
        var retried = 0
        compose.setContent { HomeContent(state, onRetry = { retried++ }) }
        compose.onNodeWithText("No app to list yet.").assertIsDisplayed()
        state = HomeState(loaded = true, error = CatalogError.Offline)
        compose.onNodeWithText("No connection.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
        state = HomeState(loaded = true, error = CatalogError.RateLimited(null))
        compose.onNodeWithText("GitHub's limit is reached. Try again later.").assertIsDisplayed()
    }

    // Compose UI test (https://developer.android.com/develop/ui/compose/testing): the notes of an update, the hand over note, no APK, the kept list.
    @Test
    fun `an update shows its notes and the hand over note, an app with no apk says so`() {
        val state = HomeState(
            loaded = true,
            apps = listOf(
                status("calculator", "Calculator", AppState.UPDATE, "0.3.5", "0.3.6", handOver = true),
                status("dialer", "Dialer", AppState.NO_APK, null, "0.14.20")
            ),
            details = mapOf("calculator" to ReleaseDetails("Faster sums\nand a fix", null, emptyMap()))
        )
        compose.setContent { HomeContent(state, onRetry = {}) }
        compose.onNodeWithText("A confirmation will show once.").assertIsDisplayed()
        compose.onNodeWithText("Faster sums\nand a fix").assertIsDisplayed()
        compose.onNodeWithText("No APK for this phone").assertIsDisplayed()
    }

    // Compose UI test (https://developer.android.com/develop/ui/compose/testing): the buttons of each state and the queue's step on a row.
    @Test
    fun `each state offers its button, update all counts the updates, a queued entry shows its step and a cancel`() {
        val apps = listOf(
            status("calculator", "Calculator", AppState.UPDATE, "0.3.5", "0.3.6"),
            status("dialer", "Dialer", AppState.NOT_INSTALLED, null, "0.14.20"),
            status("mtga", "MTGA", AppState.FOREIGN, "2.15.15", "2.15.15"),
            status("sms", "SMS", AppState.HAND_OVER, "0.15.6", "0.15.6", handOver = true)
        )
        val installed = mutableListOf<String>()
        var all = 0
        var cancelled = ""
        var state by mutableStateOf(HomeState(loaded = true, apps = apps))
        compose.setContent {
            HomeContent(state, onRetry = {}, onInstall = { installed += it.entry.repo }, onUpdateAll = { all++ }, onCancel = { cancelled = it })
        }
        compose.onNodeWithText("Update all (1)").performClick()
        compose.onNodeWithText("Update").performClick()
        compose.onNodeWithText("Install").performClick()
        compose.onNodeWithText("Hand over").performClick()
        assertEquals(1, all)
        assertEquals(listOf("calculator", "dialer", "sms"), installed)

        val running = InstallJob(apps[0].entry, handOver = false, serial = 1, step = JobStep.DOWNLOADING, progress = 0.42f)
        state = HomeState(loaded = true, apps = apps, jobs = listOf(running))
        compose.onNodeWithText("Downloading 42%").assertIsDisplayed()
        compose.onNodeWithText("Cancel all").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("com.yaz.calculator", cancelled)

        state = HomeState(loaded = true, apps = apps, jobs = listOf(running.copy(step = JobStep.FAILED, why = "No connection.")))
        compose.onNodeWithText("Failed: No connection.").assertIsDisplayed()
        // A failed entry offers its button again.
        compose.onNodeWithText("Update").assertIsDisplayed()
    }

    // Compose UI test (https://developer.android.com/develop/ui/compose/testing): a queue entry with no app in the list.
    @Test
    fun `an entry whose app is not in the list keeps its own row with a cancel, even when the list is empty`() {
        val entry = status("calculator", "Calculator", AppState.UPDATE, "0.3.5", "0.3.6").entry
        var cancelled = ""
        val job = InstallJob(entry, handOver = false, serial = 1, step = JobStep.CHECKING)
        compose.setContent { HomeContent(HomeState(loaded = true, jobs = listOf(job)), onRetry = {}, onCancel = { cancelled = it }) }
        compose.onNodeWithText("Calculator").assertIsDisplayed()
        compose.onNodeWithText("Checking").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("com.yaz.calculator", cancelled)
    }

    // Compose UI test (https://developer.android.com/develop/ui/compose/testing): the words of each step.
    @Test
    fun `every step of the queue has its words`() {
        val entry = status("calculator", "Calculator", AppState.UPDATE, "0.3.5", "0.3.6").entry
        fun words(step: JobStep, progress: Float = 0f, why: String? = null) = jobText(InstallJob(entry, false, 1, step, progress, why))
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
    fun `a limit with the kept list says the repos come from the last visit`() {
        val state = HomeState(
            loaded = true,
            apps = listOf(status("calculator", "Calculator", AppState.UP_TO_DATE, "0.3.6", "0.3.6")),
            error = CatalogError.RateLimited(null),
            listCached = true
        )
        compose.setContent { HomeContent(state, onRetry = {}) }
        compose.onNodeWithText("GitHub's limit is reached. Try again later. Repos from the last visit.").assertIsDisplayed()
    }

    @Test
    fun `a partial list keeps its apps and shows the error above them`() {
        val state = HomeState(
            loaded = true,
            apps = listOf(status("calculator", "Calculator", AppState.UP_TO_DATE, "0.3.6", "0.3.6")),
            error = CatalogError.Offline
        )
        compose.setContent { HomeContent(state, onRetry = {}) }
        compose.onNodeWithText("No connection.").assertIsDisplayed()
        compose.onNodeWithText("Calculator").assertIsDisplayed()
    }
}
