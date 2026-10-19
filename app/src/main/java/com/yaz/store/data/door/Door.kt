package com.yaz.store.data.door

// What Store's door does when one of our apps knocks. Sources:
// - The idea is Google Play's In-App Updates (the app asks the store through a bound service, the store downloads and installs:
//   https://developer.android.com/guide/playcore/in-app-updates), here a ContentProvider call that carries no data.
// - The shape of the call (a method name, a Bundle answer, the caller checked inside call()) is Contacts' CardsProvider
//   (YAZ/Contacts at HEAD, data/contacts/CardsProvider.kt); the caller's check is DoorGuard.
// - The catalog is the one the screens read (Catalog.load), kept in memory for a while so an app opened often does not read the
//   account each time; the state of an app is Installed.stateOf, the screens' own.
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.Installed
import com.yaz.store.data.catalog.InstalledFacts
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.trust.OurKeys
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Door(
    private val guard: DoorGuard,
    private val staging: Staging,
    /** The apps of the account, read now (Catalog.load). */
    private val load: suspend () -> List<StoreEntry>,
    /** The state of [StoreEntry] on this phone for the [Caller]: its facts are read for the caller's own package. */
    private val statusOf: (StoreEntry, Caller) -> AppStatus,
    /** A line for the debug log; nothing personal in it. */
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis
) {

    private val mutex = Mutex()
    @Volatile private var cached: List<StoreEntry>? = null
    @Volatile private var cachedAt = 0L

    /**
     * The answer to [method] from [callingPackage]: the state to send back, or null for an empty answer, which is what a caller that
     * is not ours, an unknown method or a failure gets. Nothing the caller sends is read.
     */
    fun handle(method: String, callingPackage: String?): String? {
        val caller = guard.check(callingPackage)
        if (caller == null) {
            log("$method refused, caller not ours")
            return null
        }
        val state = try {
            when (method) {
                OPENED -> runBlocking { opened(caller) }
                LEFT -> runBlocking { left(caller) }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        log("$method from ${caller.pkg} (entry ${caller.base}): ${state ?: "empty"}")
        return state
    }

    // Find the caller's entry; an update that Store may install silently is downloaded and checked now.
    private suspend fun opened(caller: Caller): String {
        val entry = entries().firstOrNull { it.packageName == caller.base } ?: return NONE
        val status = statusOf(entry, caller)
        return when {
            status.state == AppState.UP_TO_DATE -> UPTODATE
            // Hand over first: Android would ask, so the update waits for Home.
            status.state != AppState.UPDATE || status.handOver -> NONE
            else -> when (staging.stage(entry)) {
                StageResult.STAGED -> STAGED
                StageResult.REFUSED -> REFUSED
                StageResult.NONE -> NONE
            }
        }
    }

    // The app has been left: what is staged for it is committed, silently.
    private suspend fun left(caller: Caller): String = when (staging.commit(caller.base)) {
        CommitResult.INSTALLED -> INSTALLED
        CommitResult.PENDING -> PENDING
        CommitResult.FAILED -> FAILED
        CommitResult.NONE -> NONE
    }

    // The cached catalog first, else one Catalog.load.
    private suspend fun entries(): List<StoreEntry> = mutex.withLock {
        val kept = cached
        if (kept != null && now() - cachedAt < FRESH_MS) return kept
        val loaded = load()
        // An empty answer (offline) is not kept, so the next knock tries again.
        if (loaded.isNotEmpty()) {
            cached = loaded
            cachedAt = now()
        }
        loaded
    }

    companion object {
        // Source: the screens' own Installed.stateOf and Installed.handOver (data/catalog/Installed.kt, after GrapheneOS' App Store,
        // https://github.com/GrapheneOS/AppStore, core/InstallTask.kt findPackage); only the debug stand-in below is new.
        /**
         * The state of [entry] for [caller], given the [facts] of the caller's own package. Under the debug rule (a debug build only) the
         * caller is a debug app standing in for the real one: it is signed with the debug key, so it is read as signed by ours and held by
         * Store, which lets a debug build show every state of the door; the screens' Installed.stateOf does the rest.
         */
        fun status(entry: StoreEntry, caller: Caller, facts: InstalledFacts?, sdk: Int, store: String): AppStatus {
            val shown = if (caller.viaDebugRule && facts != null) {
                facts.copy(signers = setOf(OurKeys.DIGESTS.first()), installer = store)
            } else facts
            return AppStatus(entry, Installed.stateOf(entry, shown, sdk, store), shown, Installed.handOver(shown, store))
        }

        const val OPENED = "opened"
        const val LEFT = "left"

        /** The key of the answer. */
        const val STATE = "state"

        // opened
        const val NONE = "none"
        const val STAGED = "staged"
        const val UPTODATE = "uptodate"
        const val REFUSED = "refused"

        // left (NONE too)
        const val INSTALLED = "installed"
        const val PENDING = "pending"
        const val FAILED = "failed"

        private const val FRESH_MS = 15 * 60 * 1000L
    }
}
