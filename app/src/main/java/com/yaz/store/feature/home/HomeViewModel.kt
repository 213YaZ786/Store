package com.yaz.store.feature.home

// Temporary Home state. The catalog is loaded when the app opens and nothing is scheduled. Pattern: Android's AndroidViewModel and a
// StateFlow collected by the screen (https://developer.android.com/topic/libraries/architecture/viewmodel and
// https://developer.android.com/kotlin/flow/stateflow-and-sharedflow), as Files' ArchiveViewModel launches work in viewModelScope
// on Dispatchers.IO (YAZ/Files at HEAD, feature/archive/ArchiveViewModel.kt).
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.catalog.Installed
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.InstallQueue
import com.yaz.store.data.install.JobStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val loading: Boolean = false,
    /** False until a first load has ended. */
    val loaded: Boolean = false,
    val apps: List<AppStatus> = emptyList(),
    val error: CatalogError? = null,
    /** The repos come from the list kept at the last opening (GitHub's limit reached, or offline). */
    val listCached: Boolean = false,
    /** The release notes, date and sizes by [detailsKey] (repo and tag), read once per tag. */
    val details: Map<String, ReleaseDetails> = emptyMap(),
    /** The install queue: what each entry that was queued is doing now. */
    val jobs: List<InstallJob> = emptyList()
)

// The actions below hand the person's taps to the queue and the queue's list comes back in the state: Android's ViewModel guide
// (https://developer.android.com/topic/libraries/architecture/viewmodel) and Files' ArchiveViewModel (YAZ/Files at HEAD,
// feature/archive/ArchiveViewModel.kt), which hands its work to a runner and collects its StateFlow.
class HomeViewModel(private val app: Application, private val catalog: Catalog, private val queue: InstallQueue) : AndroidViewModel(app) {

    private val mutable = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutable.asStateFlow()
    private var job: Job? = null

    // The cards that popped in already, kept with the ViewModel so coming back from another screen does not pop them again
    // (ViewModel outlives the composition, https://developer.android.com/topic/libraries/architecture/viewmodel).
    val popped: MutableSet<String> = mutableSetOf()

    init {
        // The queue's entries ride along in the state; when one ends well the phone is read again, so its row shows the new version.
        viewModelScope.launch {
            var done = emptySet<Long>()
            queue.jobs.collect { jobs ->
                mutable.update { it.copy(jobs = jobs) }
                val now = jobs.filter { it.step == JobStep.DONE }.map { it.serial }.toSet()
                if (now.any { it !in done }) refreshInstalled()
                done = now
            }
        }
    }

    /** Installs or updates [status]'s app through the queue; false when it is queued already. */
    fun install(status: AppStatus): Boolean = queue.enqueue(status.entry, status.handOver)

    /** "Update all": every app whose state is UPDATE. */
    fun updateAll(): Int = queue.enqueueUpdates(mutable.value.apps)

    fun cancel(packageName: String) = queue.cancel(packageName)

    fun cancelAll() = queue.cancelAll()

    // The phone's side again, without GitHub: the installed versions and who holds each app.
    private fun refreshInstalled() {
        viewModelScope.launch {
            val entries = mutable.value.apps.map { it.entry }
            val statuses = withContext(Dispatchers.IO) { Installed.statuses(app, entries) }
            mutable.update { it.copy(apps = statuses) }
        }
    }

    /** Reads the account and the phone again; ignored while a load is running. */
    fun load() {
        if (job?.isActive == true) return
        mutable.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val result = catalog.load()
            val statuses = withContext(Dispatchers.IO) { Installed.statuses(app, result.entries) }
            mutable.update { it.copy(loading = false, loaded = true, apps = statuses, error = result.error, listCached = result.listCached) }
            // The notes come from GitHub's API, so only for an app with an update; a tag read once is kept for good.
            val notes = withContext(Dispatchers.IO) {
                statuses.filter { it.state == AppState.UPDATE }.mapNotNull { s -> catalog.details(s.entry)?.let { s.entry.detailsKey to it } }.toMap()
            }
            mutable.update { it.copy(details = it.details + notes) }
        }
    }

    // The app page asks for the notes, date and sizes of one release, whatever its state: Catalog.details keeps a tag for good,
    // so opening the page again costs nothing. Pattern: Files' ArchiveViewModel, which launches such work in viewModelScope on Dispatchers.IO
    // (YAZ/Files at HEAD, feature/archive/ArchiveViewModel.kt; https://developer.android.com/topic/libraries/architecture/viewmodel).
    fun ensureDetails(entry: StoreEntry) {
        if (mutable.value.details[entry.detailsKey] != null) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { catalog.details(entry) } ?: return@launch
            mutable.update { it.copy(details = it.details + (entry.detailsKey to found)) }
        }
    }
}
