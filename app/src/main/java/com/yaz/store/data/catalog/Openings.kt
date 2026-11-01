package com.yaz.store.data.catalog

// The "opening" signal of the catalogue's readers: each opening of the app (ON_START) and each pull to refresh begins a new generation, and the
// files asked of the repos (IconStore's icons and layers, RepoDescriptions' words) are asked again once, with GitHub's conditional request (the ETag
// sent back, 304 keeps the stored file, https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate).
// Pull to refresh "allows users to ... refresh the data" (https://developer.android.com/develop/ui/compose/components/pull-to-refresh); the generation is
// a StateFlow (https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) a screen reads to load its pictures again. The asked sets are the
// ones RepoFiles.conditional takes as `seen` (this app's data/catalog/RepoFiles.kt): "one request per key and opening" was "per process" before this class.
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class Openings {

    private val mutable = MutableStateFlow(0)

    /** The number of the current opening: it changes at each [begin]. */
    val generation: StateFlow<Int> = mutable.asStateFlow()

    private val sets = CopyOnWriteArrayList<MutableSet<String>>()

    // Source: RepoFiles.conditional's `seen` (this app's data/catalog/RepoFiles.kt), a concurrent set as IconStore.asked was; this one is
    // forgotten at each opening.
    /** A set of keys asked since the last opening: emptied by [begin]. */
    fun asked(): MutableSet<String> = ConcurrentHashMap.newKeySet<String>().also { sets.add(it) }

    // Source: pull to refresh and ON_START both re-read the data (https://developer.android.com/develop/ui/compose/components/pull-to-refresh);
    // the sets are emptied before the generation moves, so a reader woken by the new generation asks again.
    /** A new opening (or pull): every asked set is forgotten, then the generation moves. */
    fun begin() {
        sets.forEach { it.clear() }
        mutable.update { it + 1 }
    }
}
