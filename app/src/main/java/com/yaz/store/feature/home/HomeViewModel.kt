package com.yaz.store.feature.home

// Temporary Home state. The catalog is loaded when the app opens and nothing is scheduled. Pattern: Android's AndroidViewModel and a
// StateFlow collected by the screen (https://developer.android.com/topic/libraries/architecture/viewmodel and
// https://developer.android.com/kotlin/flow/stateflow-and-sharedflow), as Files' ArchiveViewModel launches work in viewModelScope
// on Dispatchers.IO (YAZ/Files at HEAD, feature/archive/ArchiveViewModel.kt).
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.Catalog
import com.yaz.store.data.catalog.CatalogError
import com.yaz.store.data.catalog.Installed
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
    val error: CatalogError? = null
)

class HomeViewModel(private val app: Application, private val catalog: Catalog) : AndroidViewModel(app) {

    private val mutable = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutable.asStateFlow()
    private var job: Job? = null

    /** Reads the account and the phone again; ignored while a load is running. */
    fun load() {
        if (job?.isActive == true) return
        mutable.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val result = catalog.load()
            val statuses = withContext(Dispatchers.IO) { Installed.statuses(app, result.entries) }
            mutable.value = HomeState(loading = false, loaded = true, apps = statuses, error = result.error)
        }
    }
}
